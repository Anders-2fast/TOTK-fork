// Runtime edits of known game textures, made on upload (runtime/nvn/texpatch.c): where controller-style button
// prompts and similar UI edits go. Shared by the Metal and Vulkan backends.
#pragma once
#include <stdbool.h>
#include <stdint.h>

#include "nvn.h"

#define TEXPATCH_UNKNOWN (-2)  // (backend's cached id before the first identification)

// Which of the known textures `t` is (by its contents), or -1.
int texpatch_identify(const NvnTexture *t);
// The backends' cached marker for `t` (TexBackend.tp): texpatch_identify + 1, or -1 for "checked, none" (0 stays
// "not checked yet"). Kept until the texture's contents change: identifying compares the data of every texture shaped
// like a known one, too slow to repeat at every upload check (-1 was stored as 0: every texture without a patch was
// identified again each time).
static inline int texpatch_marker(const NvnTexture *t) {
    int id = texpatch_identify(t);
    return id >= 0 ? id + 1 : -1;
}
// Changes whenever the wanted look of the known textures changes: backends re-upload identified textures when it
// differs from the value they uploaded with.
uint32_t texpatch_generation(void);
// Rewrites level `lv` of known texture `id` in place: `blocks` = its linear data in the texture's own format
// (rows of 4x4 blocks, `row` bytes apart; RGBA8 rows for uncompressed).
void texpatch_apply(int id, uint32_t lv, uint8_t *blocks, uint32_t w, uint32_t h, uint32_t row);
