/* Handgeschriebene config.h für den Android-Build (ersetzt ./configure). */
#ifndef LAME_CONFIG_H
#define LAME_CONFIG_H

#define STDC_HEADERS 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_UNISTD_H 1
#define HAVE_MEMORY_H 1

#define HAVE_INT8_T 1
#define HAVE_INT16_T 1
#define HAVE_INT32_T 1
#define HAVE_INT64_T 1
#define HAVE_UINT8_T 1
#define HAVE_UINT16_T 1
#define HAVE_UINT32_T 1
#define HAVE_UINT64_T 1

#define SIZEOF_SHORT 2
#define SIZEOF_INT 4
#define SIZEOF_LONG_LONG 8
#define SIZEOF_FLOAT 4
#define SIZEOF_DOUBLE 8

#define TAKEHIRO_IEEE754_HACK 1
#define USE_FAST_LOG 1

/* Nur Encoder – kein mpglib-Decoder, keine Frontend-Teile. */
#undef HAVE_MPGLIB
#undef DECODE_ON_THE_FLY

/* Sonst von ./configure erzeugt (configure.in, AH_BOTTOM). */
typedef double ieee754_float64_t;
typedef float ieee754_float32_t;

#endif
