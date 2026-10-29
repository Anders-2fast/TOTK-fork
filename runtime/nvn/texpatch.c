// Known game textures edited on upload (texpatch.h). BOTW: none yet. LAS Recomp's version recognised the game's
// button prompt glyphs by content and redrew their labels in the controller's style (Xbox / PlayStation); a BOTW
// version would do the same with BOTW's own UI archives.
#include "texpatch.h"

int texpatch_identify(const NvnTexture *t) { (void)t; return -1; }

uint32_t texpatch_generation(void) { return 0; }

void texpatch_apply(int id, uint32_t lv, uint8_t *blocks, uint32_t w, uint32_t h, uint32_t row) {
    (void)id, (void)lv, (void)blocks, (void)w, (void)h, (void)row;
}
