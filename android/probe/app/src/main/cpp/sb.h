// A growable text buffer for the probe's report.
#pragma once
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    char *s;
    size_t n, cap;
} Sb;

__attribute__((format(printf, 2, 3))) static inline void sb_printf(Sb *b, const char *fmt, ...) {
    for (;;) {
        va_list ap;
        va_start(ap, fmt);
        size_t room = b->cap - b->n;
        int k = vsnprintf(b->s ? b->s + b->n : NULL, b->s ? room : 0, fmt, ap);
        va_end(ap);
        if (k < 0) return;
        if (b->s && (size_t)k < room) {
            b->n += (size_t)k;
            return;
        }
        size_t cap = b->cap ? b->cap * 2 : 4096;
        while (cap < b->n + (size_t)k + 1) cap *= 2;
        char *s = realloc(b->s, cap);
        if (!s) return;
        b->s = s;
        b->cap = cap;
    }
}

static inline const char *sb_str(Sb *b) { return b->s ? b->s : ""; }
