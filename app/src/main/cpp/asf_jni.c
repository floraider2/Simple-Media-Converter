// Brücke zu FFmpeg für WMV/WMA (ASF-Container): lesen, dekodieren, Bilder in Android-Puffer schreiben,
// Ton als 16-Bit-PCM liefern. Nur das, was die App braucht – siehe AsfDecoder.kt.

#include <errno.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/channel_layout.h"
#include "libavutil/mathematics.h"

#define TAG "AsfDecoder"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define IO_BUFFER 65536

enum { RESULT_EOF = 0, RESULT_VIDEO = 1, RESULT_AUDIO = 2, RESULT_ERROR = -1, RESULT_AUDIO_TOO_BIG = -2 };

typedef struct {
    int fd;
    AVFormatContext *fmt;
    AVIOContext *io;
    int video_index, audio_index;
    AVCodecContext *video, *audio;
    int decode_video, decode_audio;
    AVPacket *packet;
    AVFrame *frame;
    int64_t start_us;  // Startzeit der Datei, wird von allen Zeitstempeln abgezogen
    int64_t pts_us;    // Zeitstempel des zuletzt gelieferten Bilds bzw. Tonblocks
    int audio_bytes;   // Bytes im Tonpuffer nach RESULT_AUDIO
    int input_ended;   // Ende der Datei erreicht, Decoder werden geleert
    int video_ended, audio_ended;
} Ctx;

// ───────────── Lesen über den Dateideskriptor ─────────────

static int read_fd(void *opaque, uint8_t *buf, int size) {
    Ctx *c = opaque;
    ssize_t n;
    do { n = read(c->fd, buf, size); } while (n < 0 && errno == EINTR);
    if (n == 0) return AVERROR_EOF;
    return n < 0 ? AVERROR(errno) : (int) n;
}

static int64_t seek_fd(void *opaque, int64_t offset, int whence) {
    Ctx *c = opaque;
    if (whence == AVSEEK_SIZE) {
        struct stat st;
        return fstat(c->fd, &st) == 0 ? st.st_size : AVERROR(errno);
    }
    off_t r = lseek(c->fd, offset, whence & ~AVSEEK_FORCE);
    return r < 0 ? AVERROR(errno) : r;
}

// ───────────── Hilfen ─────────────

static AVCodecContext *open_decoder(AVStream *stream) {
    const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) return NULL;
    AVCodecContext *dec = avcodec_alloc_context3(codec);
    if (!dec) return NULL;
    if (avcodec_parameters_to_context(dec, stream->codecpar) < 0) goto fail;
    dec->pkt_timebase = stream->time_base;
    dec->thread_count = 0; // so viele Threads wie sinnvoll
    if (avcodec_open2(dec, codec, NULL) < 0) goto fail;
    return dec;
fail:
    avcodec_free_context(&dec);
    return NULL;
}

static void close_ctx(Ctx *c) {
    if (!c) return;
    avcodec_free_context(&c->video);
    avcodec_free_context(&c->audio);
    av_packet_free(&c->packet);
    av_frame_free(&c->frame);
    avformat_close_input(&c->fmt);
    if (c->io) {
        av_freep(&c->io->buffer);
        avio_context_free(&c->io);
    }
    if (c->fd >= 0) close(c->fd);
    free(c);
}

static int64_t to_us(Ctx *c, AVStream *stream, int64_t ts) {
    if (ts == AV_NOPTS_VALUE) return c->pts_us;
    return av_rescale_q(ts, stream->time_base, AV_TIME_BASE_Q) - c->start_us;
}

static inline int16_t clip16(int v) { return v < -32768 ? -32768 : v > 32767 ? 32767 : (int16_t) v; }

/** Tonblock in 16-Bit-PCM, verschachtelt (L R L R …). Gibt Bytes zurück oder -1. */
static int write_pcm(const AVFrame *f, int channels, uint8_t *out, int capacity) {
    int samples = f->nb_samples;
    int bytes = samples * channels * 2;
    if (bytes > capacity) return -1;
    int16_t *dst = (int16_t *) out;
    for (int i = 0; i < samples; i++) {
        for (int ch = 0; ch < channels; ch++) {
            int16_t v;
            switch (f->format) {
                case AV_SAMPLE_FMT_FLTP: {
                    float s = ((const float *) f->extended_data[ch])[i];
                    v = clip16((int) lrintf(s * 32767.0f));
                    break;
                }
                case AV_SAMPLE_FMT_FLT: {
                    float s = ((const float *) f->extended_data[0])[i * channels + ch];
                    v = clip16((int) lrintf(s * 32767.0f));
                    break;
                }
                case AV_SAMPLE_FMT_S16P: v = ((const int16_t *) f->extended_data[ch])[i]; break;
                case AV_SAMPLE_FMT_S16:  v = ((const int16_t *) f->extended_data[0])[i * channels + ch]; break;
                case AV_SAMPLE_FMT_S32P: v = (int16_t) (((const int32_t *) f->extended_data[ch])[i] >> 16); break;
                case AV_SAMPLE_FMT_S32:  v = (int16_t) (((const int32_t *) f->extended_data[0])[i * channels + ch] >> 16); break;
                default: return -1;
            }
            *dst++ = v;
        }
    }
    return bytes;
}

// ───────────── JNI ─────────────

#define JNI(name) Java_com_simpleconverter_app_convert_wmv_AsfDecoder_##name

JNIEXPORT jlong JNICALL JNI(nativeOpen)(JNIEnv *env, jobject thiz, jint fd) {
    Ctx *c = calloc(1, sizeof(Ctx));
    if (!c) return 0;
    c->fd = fd;
    c->video_index = c->audio_index = -1;
    uint8_t *buffer = av_malloc(IO_BUFFER);
    c->io = avio_alloc_context(buffer, IO_BUFFER, 0, c, read_fd, NULL, seek_fd);
    c->fmt = avformat_alloc_context();
    if (!buffer || !c->io || !c->fmt) goto fail;
    c->fmt->pb = c->io;
    c->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (avformat_open_input(&c->fmt, NULL, av_find_input_format("asf"), NULL) < 0) goto fail;
    if (avformat_find_stream_info(c->fmt, NULL) < 0) goto fail;
    c->video_index = av_find_best_stream(c->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    c->audio_index = av_find_best_stream(c->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (c->video_index < 0 && c->audio_index < 0) goto fail;
    c->start_us = c->fmt->start_time == AV_NOPTS_VALUE ? 0 : c->fmt->start_time;
    c->packet = av_packet_alloc();
    c->frame = av_frame_alloc();
    if (!c->packet || !c->frame) goto fail;
    return (jlong) (intptr_t) c;
fail:
    LOGW("Datei lässt sich nicht als ASF öffnen");
    close_ctx(c);
    return 0;
}

/** [Dauer µs, Breite, Höhe, Bildrate×1000, Samplerate, Kanäle] – fehlende Spuren mit 0. */
JNIEXPORT jlongArray JNICALL JNI(nativeInfo)(JNIEnv *env, jobject thiz, jlong handle) {
    Ctx *c = (Ctx *) (intptr_t) handle;
    jlong v[6] = {0};
    v[0] = c->fmt->duration == AV_NOPTS_VALUE ? 0 : c->fmt->duration;
    if (c->video_index >= 0) {
        AVStream *s = c->fmt->streams[c->video_index];
        v[1] = s->codecpar->width;
        v[2] = s->codecpar->height;
        AVRational r = s->avg_frame_rate.num ? s->avg_frame_rate : s->r_frame_rate;
        v[3] = r.den ? (jlong) r.num * 1000 / r.den : 0;
    }
    if (c->audio_index >= 0) {
        AVStream *s = c->fmt->streams[c->audio_index];
        v[4] = s->codecpar->sample_rate;
        v[5] = s->codecpar->ch_layout.nb_channels;
    }
    jlongArray out = (*env)->NewLongArray(env, 6);
    (*env)->SetLongArrayRegion(env, out, 0, 6, v);
    return out;
}

/** Namen der Codecs: [Video, Ton], leer, wenn die Spur fehlt. */
JNIEXPORT jobjectArray JNICALL JNI(nativeCodecs)(JNIEnv *env, jobject thiz, jlong handle) {
    Ctx *c = (Ctx *) (intptr_t) handle;
    jobjectArray out = (*env)->NewObjectArray(env, 2, (*env)->FindClass(env, "java/lang/String"), NULL);
    int idx[2] = {c->video_index, c->audio_index};
    for (int i = 0; i < 2; i++) {
        const char *name = idx[i] >= 0 ? avcodec_get_name(c->fmt->streams[idx[i]]->codecpar->codec_id) : "";
        (*env)->SetObjectArrayElement(env, out, i, (*env)->NewStringUTF(env, name));
    }
    return out;
}

/** Decoder öffnen; nicht gewünschte Spuren werden beim Lesen übersprungen. */
JNIEXPORT jboolean JNICALL JNI(nativeStart)(JNIEnv *env, jobject thiz, jlong handle, jboolean video, jboolean audio) {
    Ctx *c = (Ctx *) (intptr_t) handle;
    c->decode_video = video && c->video_index >= 0;
    c->decode_audio = audio && c->audio_index >= 0;
    if (c->decode_video && !(c->video = open_decoder(c->fmt->streams[c->video_index]))) return JNI_FALSE;
    if (c->decode_audio && !(c->audio = open_decoder(c->fmt->streams[c->audio_index]))) return JNI_FALSE;
    c->video_ended = !c->decode_video;
    c->audio_ended = !c->decode_audio;
    for (unsigned i = 0; i < c->fmt->nb_streams; i++) {
        int keep = ((int) i == c->video_index && c->decode_video) || ((int) i == c->audio_index && c->decode_audio);
        c->fmt->streams[i]->discard = keep ? AVDISCARD_DEFAULT : AVDISCARD_ALL;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JNI(nativeSeek)(JNIEnv *env, jobject thiz, jlong handle, jlong time_us) {
    Ctx *c = (Ctx *) (intptr_t) handle;
    int r = av_seek_frame(c->fmt, -1, time_us + c->start_us, AVSEEK_FLAG_BACKWARD);
    if (c->video) avcodec_flush_buffers(c->video);
    if (c->audio) avcodec_flush_buffers(c->audio);
    return r >= 0;
}

/**
 * Nächstes Bild oder nächsten Tonblock dekodieren. Ton landet direkt in [audio_out];
 * ein Bild bleibt im Kontext, bis es mit nativeCopyYuv/nativeCopyRgba abgeholt wird.
 */
JNIEXPORT jint JNICALL JNI(nativeRead)(JNIEnv *env, jobject thiz, jlong handle, jobject audio_out) {
    Ctx *c = (Ctx *) (intptr_t) handle;
    for (;;) {
        if (!c->video_ended) {
            int r = avcodec_receive_frame(c->video, c->frame);
            if (r == 0) {
                c->pts_us = to_us(c, c->fmt->streams[c->video_index], c->frame->best_effort_timestamp);
                return RESULT_VIDEO;
            }
            if (r == AVERROR_EOF) c->video_ended = 1;
            else if (r != AVERROR(EAGAIN)) return RESULT_ERROR;
        }
        if (!c->audio_ended) {
            int r = avcodec_receive_frame(c->audio, c->frame);
            if (r == 0) {
                c->pts_us = to_us(c, c->fmt->streams[c->audio_index], c->frame->best_effort_timestamp);
                uint8_t *out = (*env)->GetDirectBufferAddress(env, audio_out);
                jlong capacity = (*env)->GetDirectBufferCapacity(env, audio_out);
                c->audio_bytes = write_pcm(c->frame, c->audio->ch_layout.nb_channels, out, (int) capacity);
                av_frame_unref(c->frame);
                return c->audio_bytes < 0 ? RESULT_AUDIO_TOO_BIG : RESULT_AUDIO;
            }
            if (r == AVERROR_EOF) c->audio_ended = 1;
            else if (r != AVERROR(EAGAIN)) return RESULT_ERROR;
        }
        if (c->video_ended && c->audio_ended) return RESULT_EOF;
        if (c->input_ended) continue; // Decoder werden noch geleert

        int r = av_read_frame(c->fmt, c->packet);
        if (r < 0) {
            // Dateiende (oder kaputter Rest): Decoder leeren, damit die letzten Bilder kommen.
            c->input_ended = 1;
            if (c->video) avcodec_send_packet(c->video, NULL);
            if (c->audio) avcodec_send_packet(c->audio, NULL);
            continue;
        }
        AVCodecContext *dec = c->packet->stream_index == c->video_index ? c->video
                            : c->packet->stream_index == c->audio_index ? c->audio : NULL;
        if (dec) {
            r = avcodec_send_packet(dec, c->packet);
            // Einzelne kaputte Pakete überspringen statt abzubrechen.
            if (r < 0 && r != AVERROR(EAGAIN) && r != AVERROR_INVALIDDATA) {
                av_packet_unref(c->packet);
                return RESULT_ERROR;
            }
        }
        av_packet_unref(c->packet);
    }
}

JNIEXPORT jlong JNICALL JNI(nativePtsUs)(JNIEnv *env, jobject thiz, jlong handle) {
    return ((Ctx *) (intptr_t) handle)->pts_us;
}

JNIEXPORT jint JNICALL JNI(nativeAudioBytes)(JNIEnv *env, jobject thiz, jlong handle) {
    return ((Ctx *) (intptr_t) handle)->audio_bytes;
}

static int is_yuv420(int format) { return format == AV_PIX_FMT_YUV420P || format == AV_PIX_FMT_YUVJ420P; }

/** Aktuelles Bild in die drei Ebenen eines YUV_420_888-Bilds kopieren (Android 10+). */
JNIEXPORT jboolean JNICALL JNI(nativeCopyYuv)(JNIEnv *env, jobject thiz, jlong handle,
        jobject y, jint y_stride, jobject u, jint u_stride, jint u_pixel, jobject v, jint v_stride, jint v_pixel,
        jint max_w, jint max_h) {
    Ctx *c = (Ctx *) (intptr_t) handle;
    AVFrame *f = c->frame;
    if (!is_yuv420(f->format)) { av_frame_unref(f); return JNI_FALSE; }
    uint8_t *dy = (*env)->GetDirectBufferAddress(env, y);
    uint8_t *du = (*env)->GetDirectBufferAddress(env, u);
    uint8_t *dv = (*env)->GetDirectBufferAddress(env, v);
    // Nie über das Zielbild hinaus schreiben, falls sich die Bildgröße unterwegs ändert.
    int w = f->width < max_w ? f->width : max_w, h = f->height < max_h ? f->height : max_h;
    for (int row = 0; row < h; row++) memcpy(dy + row * y_stride, f->data[0] + row * f->linesize[0], w);
    int cw = (w + 1) / 2, ch = (h + 1) / 2;
    for (int row = 0; row < ch; row++) {
        const uint8_t *su = f->data[1] + row * f->linesize[1];
        const uint8_t *sv = f->data[2] + row * f->linesize[2];
        uint8_t *ru = du + row * u_stride, *rv = dv + row * v_stride;
        if (u_pixel == 1 && v_pixel == 1) {
            memcpy(ru, su, cw);
            memcpy(rv, sv, cw);
        } else {
            for (int x = 0; x < cw; x++) { ru[x * u_pixel] = su[x]; rv[x * v_pixel] = sv[x]; }
        }
    }
    av_frame_unref(f);
    return JNI_TRUE;
}

static inline uint8_t clip8(int v) { return v < 0 ? 0 : v > 255 ? 255 : (uint8_t) v; }

/**
 * Aktuelles Bild als RGBA (BT.601) – wenn die Fläche kein beschreibbares YUV anbietet.
 * Je zwei Pixel teilen sich U und V; die Farbanteile werden deshalb nur einmal pro Paar gerechnet.
 */
JNIEXPORT jboolean JNICALL JNI(nativeCopyRgba)(JNIEnv *env, jobject thiz, jlong handle, jobject rgba, jint stride,
        jint max_w, jint max_h) {
    Ctx *c = (Ctx *) (intptr_t) handle;
    AVFrame *f = c->frame;
    if (!is_yuv420(f->format)) { av_frame_unref(f); return JNI_FALSE; }
    uint8_t *dst = (*env)->GetDirectBufferAddress(env, rgba);
    const int full = f->format == AV_PIX_FMT_YUVJ420P;
    const int ym = full ? 256 : 298, yo = full ? 0 : 16;
    int w = f->width < max_w ? f->width : max_w, h = f->height < max_h ? f->height : max_h;
    for (int row = 0; row < h; row++) {
        const uint8_t *restrict sy = f->data[0] + row * f->linesize[0];
        const uint8_t *restrict su = f->data[1] + (row >> 1) * f->linesize[1];
        const uint8_t *restrict sv = f->data[2] + (row >> 1) * f->linesize[2];
        uint32_t *restrict d = (uint32_t *) (dst + row * stride);
        for (int x = 0; x < w; x += 2) {
            const int uu = su[x >> 1] - 128, vv = sv[x >> 1] - 128;
            const int r = 409 * vv + 128, g = -100 * uu - 208 * vv + 128, b = 516 * uu + 128;
            int y0 = (sy[x] - yo) * ym;
            d[x] = clip8((y0 + r) >> 8) | (uint32_t) clip8((y0 + g) >> 8) << 8 | (uint32_t) clip8((y0 + b) >> 8) << 16 | 0xFF000000u;
            if (x + 1 < w) {
                int y1 = (sy[x + 1] - yo) * ym;
                d[x + 1] = clip8((y1 + r) >> 8) | (uint32_t) clip8((y1 + g) >> 8) << 8 | (uint32_t) clip8((y1 + b) >> 8) << 16 | 0xFF000000u;
            }
        }
    }
    av_frame_unref(f);
    return JNI_TRUE;
}

/** Aktuelles Bild verwerfen (z. B. vor dem Anfang des Ausschnitts). */
JNIEXPORT void JNICALL JNI(nativeDropFrame)(JNIEnv *env, jobject thiz, jlong handle) {
    av_frame_unref(((Ctx *) (intptr_t) handle)->frame);
}

JNIEXPORT void JNICALL JNI(nativeClose)(JNIEnv *env, jobject thiz, jlong handle) {
    close_ctx((Ctx *) (intptr_t) handle);
}
