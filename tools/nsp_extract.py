"""Unpack an NSP (PFS0) for hactoolnet, which (2019's) opens NCAs but not NSPs, and write the title keys of its common
tickets as hactoolnet's title.keys lines.

Usage: nsp_extract.py <nsp> <out dir> [--only-largest]   (--only-largest: just the biggest NCA - the base game's program
NCA, ~16 GB for Tears of the Kingdom - and the tickets)

Then: hactoolnet --titlekeys <out>/title.keys -t nca <update program nca> --basenca <base program nca>
          --exefsdir Dump/<title id>/exefs --romfs Dump/<title id>/romfs.bin
A common ticket (signature 0x10004, RSA-2048) holds the title key at 0x180 and the rights id at 0x2A0.
"""
import os
import struct
import sys


def entries(f):
    head = f.read(16)
    if head[:4] != b'PFS0':
        sys.exit('not an NSP (PFS0)')
    n, strsize = struct.unpack_from('<II', head, 4)
    table = f.read(n * 24)
    strings = f.read(strsize)
    data = 16 + n * 24 + strsize
    for i in range(n):
        off, size, name_off = struct.unpack_from('<QQI', table, i * 24)
        name = strings[name_off:strings.index(b'\0', name_off)].decode()
        yield name, data + off, size


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    nsp, out = sys.argv[1], sys.argv[2]
    only_largest = '--only-largest' in sys.argv[3:]
    os.makedirs(out, exist_ok=True)
    with open(nsp, 'rb') as f:
        files = list(entries(f))
        ncas = [e for e in files if e[0].endswith('.nca')]
        largest = max(ncas, key=lambda e: e[2])[0] if ncas else None
        keys = []
        for name, off, size in files:
            if only_largest and name.endswith('.nca') and name != largest:
                continue
            path = os.path.join(out, name)
            if os.path.exists(path) and os.path.getsize(path) == size:
                print(f'{name}: already there')
            else:
                f.seek(off)
                with open(path + '.part', 'wb') as o:
                    left = size
                    while left:
                        chunk = f.read(min(left, 64 << 20))
                        if not chunk:
                            sys.exit(f'{name}: the NSP ends early')
                        o.write(chunk)
                        left -= len(chunk)
                os.replace(path + '.part', path)
                print(f'{name}: {size:,} bytes')
            if name.endswith('.tik'):
                t = open(path, 'rb').read()
                if struct.unpack_from('<I', t, 0)[0] == 0x10004 and len(t) >= 0x2B0:
                    keys.append(f'{t[0x2A0:0x2B0].hex()} = {t[0x180:0x190].hex()}')
    with open(os.path.join(out, 'title.keys'), 'w') as k:
        k.write(''.join(line + '\n' for line in keys))
    print(f'{len(keys)} title key(s); program NCA (the largest): {largest}')


if __name__ == '__main__':
    main()
