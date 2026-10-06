/*
 * JNI-Brücke zu LAME: Kotlin liefert 16-Bit-PCM, wir geben MP3-Bytes zurück.
 * Gegenstück: com.simpleconverter.app.convert.audio.Lame
 */
#include <jni.h>
#include <stdint.h>
#include "lame.h"

#define FN(name) Java_com_simpleconverter_app_convert_audio_Lame_##name

JNIEXPORT jlong JNICALL
FN(nativeInit)(JNIEnv *env, jclass clazz, jint sampleRate, jint channels, jint bitrateKbps, jint quality) {
    lame_global_flags *lame = lame_init();
    if (lame == NULL) return 0;
    lame_set_in_samplerate(lame, sampleRate);
    lame_set_num_channels(lame, channels);
    lame_set_mode(lame, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(lame, bitrateKbps);
    lame_set_VBR(lame, vbr_off);
    lame_set_quality(lame, quality);
    lame_set_write_id3tag_automatic(lame, 0);
    lame_set_bWriteVbrTag(lame, 1); /* Info-Tag im ersten Frame: Player kennen die genaue Länge */
    if (lame_init_params(lame) < 0) {
        lame_close(lame);
        return 0;
    }
    return (jlong) (intptr_t) lame;
}

JNIEXPORT jint JNICALL
FN(nativeEncode)(JNIEnv *env, jclass clazz, jlong handle, jshortArray pcm, jint frames, jint channels, jbyteArray out) {
    lame_global_flags *lame = (lame_global_flags *) (intptr_t) handle;
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *buffer = (*env)->GetByteArrayElements(env, out, NULL);
    int size = (*env)->GetArrayLength(env, out);
    int written;
    if (channels == 2) {
        written = lame_encode_buffer_interleaved(lame, samples, frames, (unsigned char *) buffer, size);
    } else {
        written = lame_encode_buffer(lame, samples, samples, frames, (unsigned char *) buffer, size);
    }
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, buffer, 0);
    return written;
}

JNIEXPORT jint JNICALL
FN(nativeFlush)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray out) {
    lame_global_flags *lame = (lame_global_flags *) (intptr_t) handle;
    jbyte *buffer = (*env)->GetByteArrayElements(env, out, NULL);
    int written = lame_encode_flush(lame, (unsigned char *) buffer, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, buffer, 0);
    return written;
}

JNIEXPORT jint JNICALL
FN(nativeLameTag)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray out) {
    lame_global_flags *lame = (lame_global_flags *) (intptr_t) handle;
    jbyte *buffer = (*env)->GetByteArrayElements(env, out, NULL);
    size_t written = lame_get_lametag_frame(lame, (unsigned char *) buffer, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, buffer, 0);
    return (jint) written;
}

JNIEXPORT void JNICALL
FN(nativeClose)(JNIEnv *env, jclass clazz, jlong handle) {
    lame_close((lame_global_flags *) (intptr_t) handle);
}
