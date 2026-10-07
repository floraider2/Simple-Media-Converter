#!/bin/bash
# Konfiguriert FFmpeg für eine Android-ABI: nur ASF-Leser + WMV/VC-1/WMA-Decoder, nur LGPL.
# Aufruf (Git Bash unter Windows, NDK 30): configure_abi.sh <abi> <ffmpeg-quelle> <ausgabe>
set -e
export TMPDIR=/tmp TMP=/tmp TEMP=/tmp
ABI="$1"; SRC="$2"; OUT="$3"
NDK="$(cygpath -m "$LOCALAPPDATA")/Android/Sdk/ndk/30.0.16248370"
TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
API=26
case "$ABI" in
  arm64-v8a)   ARCH=aarch64; CPU=armv8-a; TRIPLE=aarch64-linux-android ;;
  armeabi-v7a) ARCH=arm;     CPU=armv7-a; TRIPLE=armv7a-linux-androideabi ;;
  x86_64)      ARCH=x86_64;  CPU=x86-64;  TRIPLE=x86_64-linux-android ;;
esac
# ARM: NEON-Routinen von FFmpeg (deutlich schnelleres VC-1/WMV). x86_64 ohne Assembler (bräuchte nasm).
case "$ABI" in
  arm64-v8a|armeabi-v7a) ASM="--disable-x86asm --enable-neon" ;;
  *)                     ASM="--disable-x86asm --disable-asm" ;;
esac
mkdir -p "$OUT" && cd "$OUT"
"$SRC/configure" \
  --enable-cross-compile --target-os=android --arch=$ARCH --cpu=$CPU \
  --cc="$TC/clang.exe --target=$TRIPLE$API" \
  --cxx="$TC/clang++.exe --target=$TRIPLE$API" \
  --ar="$TC/llvm-ar.exe" --ranlib="$TC/llvm-ranlib.exe" --nm="$TC/llvm-nm.exe" --strip="$TC/llvm-strip.exe" \
  --host-cc="$TC/clang.exe --target=$TRIPLE$API" $ASM \
  --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-network \
  --disable-avdevice --disable-avfilter --disable-swscale --disable-swresample \
  --disable-debug --enable-pic --enable-static --disable-shared \
  --enable-demuxer=asf \
  --enable-decoder=wmv1,wmv2,wmv3,vc1,wmav1,wmav2,wmapro,wmavoice,wmalossless \
  --enable-parser=vc1
# Git Bash bildet /tmp auf den Windows-Temp-Ordner ab; make.exe aus dem NDK braucht Windows-Pfade.
sed -i "s#/tmp/#$(cygpath -m /tmp)/#g" Makefile ffbuild/config.mak
