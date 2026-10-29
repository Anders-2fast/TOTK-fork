// Address space ranges of SDKs made for the 36-bit address space (BOTW 1.0.0: main.npdm address space type 1).
// There nnosInitialize sets up two nn::os::detail::AddressSpaceAllocator over fixed ranges of that layout:
// [0x8000000, 0x1000000000) (where shared / transfer memory is mapped) and [0x8000000, 0x80000000) (alias stacks of
// threads and fibers, svcMapMemory targets). The allocators pick random addresses in them and check them with
// svcQueryMemory. Those ranges are outside the guest window here (on Android below 4 GB, where the app process
// lives), so any range outside the window becomes the alias region (ALIAS_BASE, rt.h); allocations there don't
// collide, they are checked against what is mapped. (runtime/loader.c routes the SDK's own calls here.)
#include "../hle.h"

#define CTOR "_ZN2nn2os6detail21AddressSpaceAllocatorC1Emmm"

// AddressSpaceAllocator(this, begin, end, guard size)
HLE_FUNC(CTOR, address_space_allocator_ctor) {
    static uint64_t fn;
    if (!fn) fn = loader_find_symbol(CTOR);
    uint64_t begin = c->x[1], end = c->x[2];
    if (begin < GUEST_LO || end > GUEST_HI) {
        LOG("[os] address space allocator %llx-%llx -> alias region %llx-%llx\n", (unsigned long long)begin,
            (unsigned long long)end, (unsigned long long)ALIAS_BASE, (unsigned long long)(ALIAS_BASE + ALIAS_SIZE));
        c->x[1] = ALIAS_BASE;
        c->x[2] = ALIAS_BASE + ALIAS_SIZE;
    }
    guest_call(c, fn);
}
