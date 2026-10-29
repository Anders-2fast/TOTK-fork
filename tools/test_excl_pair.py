"""Exclusive pairs (LDXP / LDAXP / STXP / STLXP): the lifted C against what the architecture says, in sequences
(test_lift.py compares single instructions with Unicorn and so skips exclusives: a store depends on the load before it).

Usage: test_excl_pair.py   (CC=clang by default; on Windows CC=wsl runs WSL's clang on the same files)

Each case lifts real encodings (checked against Capstone's disassembly first), runs them on a 16-byte aligned buffer
and checks the registers, the status of the store and the memory.
"""
import os
import subprocess
import sys

import capstone

from lifter import Lifter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def ldxp(sf, rt, rt2, rn, acq=False):
    return (0xC87F0000 if sf else 0x887F0000) | (acq << 15) | (rt2 << 10) | (rn << 5) | rt


def stxp(sf, rs, rt, rt2, rn, rel=False):
    return (0xC8200000 if sf else 0x88200000) | (rs << 16) | (rel << 15) | (rt2 << 10) | (rn << 5) | rt


CASES = [  # (encoding, the disassembly Capstone must give)
    (ldxp(1, 0, 1, 2), 'ldxp x0, x1, [x2]'),
    (stxp(1, 3, 4, 5, 2), 'stxp w3, x4, x5, [x2]'),
    (ldxp(1, 0, 1, 2, acq=True), 'ldaxp x0, x1, [x2]'),
    (stxp(1, 3, 4, 5, 2, rel=True), 'stlxp w3, x4, x5, [x2]'),
    (ldxp(0, 0, 1, 2), 'ldxp w0, w1, [x2]'),
    (stxp(0, 3, 4, 5, 2), 'stxp w3, w4, w5, [x2]'),
    (ldxp(1, 2, 6, 2), 'ldxp x2, x6, [x2]'),
]

MAIN = r'''
#include <stdio.h>
static uint64_t mem[4] __attribute__((aligned(16)));
static int fails;
#define CHECK(cond) do { if (!(cond)) { printf("FAIL line %d: %s\n", __LINE__, #cond); fails++; } } while (0)
static void reset(Cpu *c) {
    memset(c, 0, sizeof *c);
    c->excl_addr = ~0ull;
    mem[0] = 0x1111111122222222ull; mem[1] = 0x3333333344444444ull; mem[2] = mem[3] = 0;
    c->x[2] = (uint64_t)(uintptr_t)mem;
}
int main(void) {
    static Cpu cpu __attribute__((aligned(16)));
    Cpu *c = &cpu;
    // LDXP then STXP: loads both halves, stores both, status 0
    reset(c); t0(c);
    CHECK(c->x[0] == 0x1111111122222222ull && c->x[1] == 0x3333333344444444ull);
    c->x[4] = 0xaaaaaaaabbbbbbbbull; c->x[5] = 0xccccccccddddddddull; c->x[3] = 7; t1(c);
    CHECK(c->x[3] == 0 && mem[0] == 0xaaaaaaaabbbbbbbbull && mem[1] == 0xccccccccddddddddull);
    // a second STXP without a load: the monitor is clear - status 1, memory untouched
    c->x[4] = 1; c->x[5] = 2; t1(c);
    CHECK(c->x[3] == 1 && mem[0] == 0xaaaaaaaabbbbbbbbull);
    // LDAXP, another thread changes the high half, STLXP fails
    reset(c); t2(c);
    mem[1] ^= 1;
    c->x[4] = 5; c->x[5] = 6; t3(c);
    CHECK(c->x[3] == 1 && mem[0] == 0x1111111122222222ull && mem[1] == (0x3333333344444444ull ^ 1));
    // ... and succeeds when nothing changed
    reset(c); t2(c); c->x[4] = 5; c->x[5] = 6; t3(c);
    CHECK(c->x[3] == 0 && mem[0] == 5 && mem[1] == 6);
    // W pair: one 64-bit exclusive, Wt the low half, the X registers zero-extended
    reset(c); c->x[0] = c->x[1] = ~0ull; t4(c);
    CHECK(c->x[0] == 0x22222222ull && c->x[1] == 0x11111111ull);
    c->x[4] = 0xffffffff00000005ull; c->x[5] = 0x0000000700000006ull; t5(c);
    CHECK(c->x[3] == 0 && mem[0] == 0x0000000600000005ull && mem[1] == 0x3333333344444444ull);
    // the base register loaded over: the address is read first
    reset(c); t6(c);
    CHECK(c->x[2] == 0x1111111122222222ull && c->x[6] == 0x3333333344444444ull);
    printf(fails ? "%d failed\n" : "exclusive pairs: all passed\n", fails);
    return fails != 0;
}
'''


def main():
    md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
    lifter = Lifter(0x7000000000)
    out = ['#include "cpu.h"']
    for k, (w, want) in enumerate(CASES):
        ins = next(md.disasm(w.to_bytes(4, 'little'), 0))
        got = f'{ins.mnemonic} {ins.op_str}'
        if got != want:
            sys.exit(f'{w:08x}: Capstone says "{got}", expected "{want}"')
        out.append(f'static void t{k}(Cpu *c) {{ {lifter.lift(w, 0x7000001000).code} }}  /* {got} */')
    work = os.path.join(ROOT, 'build', 'test_excl_pair')
    os.makedirs(work, exist_ok=True)
    src = os.path.join(work, 'test.c')
    with open(src, 'w', newline='\n') as f:
        f.write('\n'.join(out) + MAIN)
    exe = os.path.join(work, 'test')
    inc = os.path.join(ROOT, 'runtime')
    cc = os.environ.get('CC', 'clang')
    if cc == 'wsl':  # (Windows: WSL's clang, the paths turned into /mnt/... ones)
        wp = lambda p: subprocess.check_output(['wsl', 'wslpath', '-a', p.replace('\\', '/')], text=True).strip()
        cmd = ['wsl', 'sh', '-c', f'clang -O1 -mcx16 -I "{wp(inc)}" -o "{wp(exe)}" "{wp(src)}" && "{wp(exe)}"']
    else:
        cmd = ['sh', '-c', f'{cc} -O1 -I "{inc}" -o "{exe}" "{src}" && "{exe}"']
    return subprocess.call(cmd)


if __name__ == '__main__':
    sys.exit(main())
