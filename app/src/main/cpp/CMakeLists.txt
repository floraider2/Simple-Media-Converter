cmake_minimum_required(VERSION 3.22.1)
project(simpleconverter C)

# LAME 3.100 (LGPL) – nur der Encoder aus libmp3lame, siehe lame/README.
set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lame)
file(GLOB LAME_SOURCES ${LAME_DIR}/libmp3lame/*.c)

add_library(mp3 SHARED mp3_jni.c ${LAME_SOURCES})

target_include_directories(mp3 PRIVATE ${LAME_DIR} ${LAME_DIR}/include ${LAME_DIR}/libmp3lame)
target_compile_definitions(mp3 PRIVATE HAVE_CONFIG_H)
target_compile_options(mp3 PRIVATE -O3 -w)
target_link_libraries(mp3 PRIVATE log m)
