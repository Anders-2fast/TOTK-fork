// Guest window placement on Android (guest_window.h).
#include "guest_window.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

int gw_read_maps(GwRange *out, int max) {
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return -1;
    char line[4096];
    int n = 0;
    bool line_start = true;  // (a line longer than the buffer comes in pieces: only the first one has the range)
    while (fgets(line, sizeof line, f)) {
        bool at_start = line_start;
        line_start = strchr(line, '\n') != NULL;
        unsigned long long lo, hi;
        if (!at_start || sscanf(line, "%llx-%llx", &lo, &hi) != 2) continue;
        if (n && out[n - 1].hi == lo) {
            out[n - 1].hi = hi;
            continue;
        }
        if (n == max) {
            n = -1;
            break;
        }
        out[n++] = (GwRange){lo, hi};
    }
    fclose(f);
    return n;
}

int gw_holes(const GwRange *maps, int n, uint64_t floor, uint64_t ceil, uint64_t min_size, GwRange *out, int max) {
    int k = 0;
    uint64_t prev = 0;
    for (int i = 0; i <= n && k < max; i++) {
        uint64_t lo = prev, hi = i < n ? maps[i].lo : UINT64_MAX;
        if (i < n && maps[i].hi > prev) prev = maps[i].hi;
        if (lo < floor) lo = floor;
        if (hi > ceil) hi = ceil;
        if (hi > lo && hi - lo >= min_size) out[k++] = (GwRange){lo, hi};
    }
    return k;
}

uint64_t gw_reserve(uint64_t size, uint64_t align, uint64_t floor, uint64_t ceil) {
    enum { MAX_MAPS = 1 << 16, MAX_HOLES = 256 };
    GwRange *maps = malloc(MAX_MAPS * sizeof *maps), holes[MAX_HOLES];
    uint64_t base = 0;
    for (int attempt = 0; maps && attempt < 8 && !base; attempt++) {
        int n = gw_read_maps(maps, MAX_MAPS);
        if (n < 0) break;
        int h = gw_holes(maps, n, floor, ceil, size, holes, MAX_HOLES);
        for (int i = 0; i < h && !base; i++) {
            uint64_t a = (holes[i].lo + align - 1) & ~(align - 1);
            if (a < holes[i].lo || a + size > holes[i].hi) continue;
            void *p = mmap((void *)(uintptr_t)a, size, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0);
            if (p == MAP_FAILED) continue;  // (mapped by another thread since the read: the next hole, or read again)
            if (p != (void *)(uintptr_t)a) {
                munmap(p, size);
                continue;
            }
            base = a;
        }
    }
    free(maps);
    return base;
}
