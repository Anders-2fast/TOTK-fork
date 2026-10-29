// C library functions the game imports that its own SDK doesn't export (tools/imports.py lists them as unresolved).
#include <stdlib.h>

#include "../hle.h"

// getentropy(buf, len): OpenSSL / gRPC (online services' libraries; Pokémon Scarlet's) seed from it. Up to 256 bytes a
// call, as POSIX says; 0 on success, -1 (EIO) past that.
HLE_FUNC("getentropy", libc_getentropy) {
    uint64_t len = c->x[1];
    if (len > 256) {
        c->x[0] = (uint64_t)-1;
        return;
    }
#if defined(__ANDROID__) || defined(__APPLE__)
    arc4random_buf((void *)c->x[0], len);
#else
    for (uint64_t i = 0; i < len; i++) ((uint8_t *)c->x[0])[i] = (uint8_t)rand();
#endif
    c->x[0] = 0;
}
