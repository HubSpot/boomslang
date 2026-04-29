/*
 * aviator-cpython: replacement for wasi-sdk 20's <setjmp.h>. The sysroot
 * header emits an unconditional #error unless -mllvm -wasm-enable-sjlj is
 * set and the runtime implements the WASM exception-handling proposal.
 * Chicory (our runtime) doesn't.
 *
 * This header is installed at /build/ft_include/setjmp.h and that path is
 * placed first in the compiler's include search path, so every
 *   #include <setjmp.h>
 * in freetype's source picks up THIS file instead of the sysroot one.
 *
 * Freetype uses setjmp/longjmp narrowly for out-of-memory recovery in the
 * monochrome rasterizer (ftraster.c) and anti-aliased rasterizer
 * (ftgrays.c). For valid font files the branches are unreachable; the
 * stubs below map setjmp -> 0 and longjmp -> abort(), which is
 * observationally equivalent for well-formed inputs. Malformed fonts
 * abort the process instead of jumping to an error handler.
 */
#ifndef _AVIATOR_SETJMP_H
#define _AVIATOR_SETJMP_H
/* Poison the conventional guards so a later <setjmp.h> include is a no-op. */
#define _SETJMP_H 1
#define __SETJMP_H 1
#define _BITS_SETJMP_H 1

#include <stdlib.h>

typedef int jmp_buf[1];
typedef int sigjmp_buf[1];

static inline int __aviator_setjmp(jmp_buf env) { (void)env; return 0; }
static inline void __aviator_longjmp(jmp_buf env, int val) {
    (void)env; (void)val; abort();
}

#define setjmp(env)             __aviator_setjmp(env)
#define longjmp(env, val)       __aviator_longjmp(env, val)
#define sigsetjmp(env, savemask)    __aviator_setjmp(env)
#define siglongjmp(env, val)        __aviator_longjmp(env, val)
/* Some GCC/glibc headers use the __-prefixed forms. */
#define _setjmp(env)            __aviator_setjmp(env)
#define _longjmp(env, val)      __aviator_longjmp(env, val)
#define __sigsetjmp(env, sm)    __aviator_setjmp(env)

#endif /* _AVIATOR_SETJMP_H */
