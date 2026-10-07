cmake_minimum_required(VERSION 3.22.1)
project(simpleconverter C ASM)

# LAME 3.100 (LGPL) – nur der Encoder aus libmp3lame, siehe lame/README.
set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lame)
file(GLOB LAME_SOURCES ${LAME_DIR}/libmp3lame/*.c)

add_library(mp3 SHARED mp3_jni.c ${LAME_SOURCES})

target_include_directories(mp3 PRIVATE ${LAME_DIR} ${LAME_DIR}/include ${LAME_DIR}/libmp3lame)
target_compile_definitions(mp3 PRIVATE HAVE_CONFIG_H)
target_compile_options(mp3 PRIVATE -O3 -w)
target_link_libraries(mp3 PRIVATE log m)

# FFmpeg 9.0.2 (LGPL 2.1+) – nur ASF-Leser und WMV/VC-1/WMA-Decoder, siehe ffmpeg/README.md.
set(FFMPEG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg)
set(FFMPEG_SRC ${FFMPEG_DIR}/src)
include(${FFMPEG_DIR}/sources.cmake)

# ARM: mit den NEON-Routinen von FFmpeg (.S-Dateien), x86_64 nur C.
add_library(ffmpeg STATIC ${FFMPEG_SOURCES_${ANDROID_ABI}})
# Generierte Konfiguration der ABI zuerst, damit config.h und die *_list.c-Dateien gefunden werden.
target_include_directories(ffmpeg PUBLIC ${FFMPEG_DIR}/config/${ANDROID_ABI} ${FFMPEG_SRC})
target_compile_definitions(ffmpeg PRIVATE
    HAVE_AV_CONFIG_H _ISOC11_SOURCE _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE strtod=avpriv_strtod PIC)
target_compile_options(ffmpeg PRIVATE
    # Interne FFmpeg-Symbole verbergen: nötig für die Assembler-Routinen in einer .so und macht sie kleiner.
    -fvisibility=hidden
    $<$<COMPILE_LANGUAGE:C>:-std=c17 -O3 -fomit-frame-pointer -fno-math-errno -fno-signed-zeros -pthread -w>
    $<$<COMPILE_LANGUAGE:ASM>:-Qunused-arguments>)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    # Wie FFmpegs eigener Build: ARM- statt Thumb-Befehle (die Assembler-Routinen setzen das voraus).
    target_compile_options(ffmpeg PRIVATE -marm)
endif()

add_library(asf SHARED asf_jni.c)
target_compile_options(asf PRIVATE -O3 -Wall)
target_link_libraries(asf PRIVATE ffmpeg log m)
