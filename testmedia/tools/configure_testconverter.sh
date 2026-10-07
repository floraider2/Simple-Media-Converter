#!/bin/bash
# Nur für Testdaten: ffmpeg-Programm für das Handy, das WMV/WMA aus Testbild und Testton erzeugt.
set -e
export TMPDIR=/tmp TMP=/tmp TEMP=/tmp
SRC="$1"; OUT="$2"
NDK="$(cygpath -m "$LOCALAPPDATA")/Android/Sdk/ndk/30.0.16248370"
TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
mkdir -p "$OUT" && cd "$OUT"
"$SRC/configure" \
  --enable-cross-compile --target-os=android --arch=aarch64 --cpu=armv8-a \
  --cc="$TC/clang.exe --target=aarch64-linux-android26" --cxx="$TC/clang++.exe --target=aarch64-linux-android26" \
  --ar="$TC/llvm-ar.exe" --ranlib="$TC/llvm-ranlib.exe" --nm="$TC/llvm-nm.exe" --strip="$TC/llvm-strip.exe" \
  --host-cc="$TC/clang.exe --target=aarch64-linux-android26" --disable-x86asm --disable-asm \
  --disable-everything --disable-autodetect --disable-doc --disable-network --disable-ffplay --disable-ffprobe \
  --enable-ffmpeg --enable-avfilter --enable-swscale --enable-swresample --enable-static --disable-shared \
  --enable-indev=lavfi --enable-filter=testsrc2,color,overlay,sine,amerge,pan,aresample,aformat,format,scale,null,anull,trim,atrim \
  --enable-demuxer=mov,matroska,ogg,wav,mp3,asf,flac \
  --enable-decoder=h264,hevc,vp9,aac,opus,flac,mp3float,pcm_s16le,wmv1,wmv2,wmv3,vc1,wmav1,wmav2 \
  --enable-parser=h264,hevc,aac,opus,mpegaudio \
  --enable-encoder=wmv1,wmv2,wmav1,wmav2,pcm_s16le,rawvideo \
  --enable-muxer=asf,matroska,mov,mp4,wav,rawvideo \
  --enable-bsf=h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc \
  --enable-protocol=file --enable-protocol=pipe
sed -i "s#/tmp/#$(cygpath -m /tmp)/#g" Makefile ffbuild/config.mak
