#!/usr/bin/env python3
"""romfs.bin from a romfs/ folder on the computer: runtime/romfs_build.c's romfs_build (the image the app's setup builds)
in Python, the same image byte for byte (tools/test_romfs_build_py.py checks it against the C).

Usage: romfs_build.py <romfs folder> <romfs.bin>

For android/build_romfs.sh: TOTK's romfs is 304k files, which adb copies slowly (and lost the connection on once), and
which WSL's cc read through /mnt at about one folder in four minutes; Python reads them natively on Windows. Layout as
in romfs_build.c: a 0x50-byte header, file data from 0x200 (each file 16-byte aligned), then the directory hash table,
directory entries, file hash table and file entries; directories and files sorted by name, children before siblings'
children.
"""
import os
import shutil
import stat
import struct
import sys

EMPTY = 0xFFFFFFFF
DATA_START = 0x200


class Dir:
    def __init__(self, name, path, parent):
        self.name, self.path, self.parent = name, path, parent
        self.dirs, self.files, self.off = [], [], 0


class File:
    def __init__(self, name, path, size, parent):
        self.name, self.path, self.size, self.parent = name, path, size, parent
        self.off = self.data_off = 0


def scan(path, name, parent, depth=0):
    """romfs_build.c scan(): hidden entries left out, folders and files each sorted by name (strcmp: bytes)."""
    if depth > 32:
        return None
    try:
        entries = list(os.scandir(path))
    except OSError:
        return None
    d = Dir(name, path, parent)
    for e in entries:
        if e.name.startswith('.'):
            continue
        try:
            st = os.stat(e.path)  # (follows links, as stat() does)
        except OSError:
            continue
        if stat.S_ISDIR(st.st_mode):
            c = scan(e.path, e.name.encode(), d, depth + 1)
            if c:
                d.dirs.append(c)
        elif stat.S_ISREG(st.st_mode):
            d.files.append(File(e.name.encode(), e.path, st.st_size, d))
    d.dirs.sort(key=lambda x: x.name)
    d.files.sort(key=lambda x: x.name)
    return d


def flatten(d, dirs, files):
    files += d.files
    dirs += d.dirs
    for c in d.dirs:
        flatten(c, dirs, files)


def name_hash(parent, name):
    h = parent ^ 123456789
    for ch in name:
        h = (((h >> 5) | (h << 27)) & 0xFFFFFFFF) ^ ch
    return h


def bucket_count(n):
    if n < 3:
        return 3
    if n < 19:
        return n | 1
    c = n
    while any(c % p == 0 for p in (2, 3, 5, 7, 11, 13, 17)):
        c += 1
    return c


def align4(x): return (x + 3) & ~3
def align16(x): return (x + 15) & ~15


def build(src, out_path, progress=None):
    root = scan(src, b'', None)
    if root is None:
        raise SystemExit(f"can't read {src}")
    dirs, files = [root], []
    flatten(root, dirs, files)
    if not files:
        raise SystemExit('no game files found')

    dsize = fsize = 0
    for d in dirs:
        d.off = dsize
        dsize += 0x18 + align4(len(d.name))
    for f in files:
        f.off = fsize
        fsize += 0x20 + align4(len(f.name))
    data = total = 0
    for f in files:
        data = align16(data)
        f.data_off = data
        data += f.size
        total += f.size

    nbd, nbf = bucket_count(len(dirs)), bucket_count(len(files))
    dbuckets, fbuckets = [EMPTY] * nbd, [EMPTY] * nbf
    dtab, ftab = bytearray(dsize), bytearray(fsize)
    sib_of = {}  # (siblings by position: a search per entry would be quadratic in a folder of thousands)
    for d in dirs:
        for i, c in enumerate(d.dirs):
            sib_of[id(c)] = d.dirs[i + 1].off if i + 1 < len(d.dirs) else EMPTY
        for i, f in enumerate(d.files):
            sib_of[id(f)] = d.files[i + 1].off if i + 1 < len(d.files) else EMPTY
    for d in dirs:
        parent = d.parent.off if d.parent else 0
        h = name_hash(parent, d.name) % nbd
        struct.pack_into('<6I', dtab, d.off, parent, sib_of.get(id(d), EMPTY), d.dirs[0].off if d.dirs else EMPTY,
                         d.files[0].off if d.files else EMPTY, dbuckets[h], len(d.name))
        dtab[d.off + 0x18:d.off + 0x18 + len(d.name)] = d.name
        dbuckets[h] = d.off
    for f in files:  # (in table order, as the C: each bucket's chain starts at the last entry put in it)
        h = name_hash(f.parent.off, f.name) % nbf
        struct.pack_into('<IIQQII', ftab, f.off, f.parent.off, sib_of[id(f)], f.data_off, f.size, fbuckets[h],
                         len(f.name))
        ftab[f.off + 0x20:f.off + 0x20 + len(f.name)] = f.name
        fbuckets[h] = f.off

    dhash_off = (DATA_START + data + 3) & ~3
    dmeta_off = dhash_off + 4 * nbd
    fhash_off = dmeta_off + dsize
    fmeta_off = fhash_off + 4 * nbf
    hdr = struct.pack('<10Q', 0x50, dhash_off, 4 * nbd, dmeta_off, dsize, fhash_off, 4 * nbf, fmeta_off, fsize,
                      DATA_START).ljust(DATA_START, b'\0')

    done = 0
    try:
        with open(out_path, 'wb') as out:
            out.write(hdr)
            pos = 0
            for f in files:
                if f.data_off > pos:
                    out.write(b'\0' * (f.data_off - pos))
                with open(f.path, 'rb') as inp:
                    shutil.copyfileobj(inp, out, 4 << 20)
                    if out.tell() != DATA_START + f.data_off + f.size:
                        raise SystemExit(f"can't read {f.path}")
                pos = f.data_off + f.size
                done += f.size
                if progress:
                    progress(done, total)
            out.write(b'\0' * (dhash_off - DATA_START - pos))
            out.write(struct.pack(f'<{nbd}I', *dbuckets))
            out.write(dtab)
            out.write(struct.pack(f'<{nbf}I', *fbuckets))
            out.write(ftab)
    except BaseException:
        if os.path.exists(out_path):
            os.remove(out_path)
        raise
    return len(dirs), len(files), total


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    last = [-1]

    def progress(done, total):  # (romfs_tool's: every 10%)
        pct = done * 100 // total if total else 0
        if pct // 10 != last[0] // 10:
            print(f'{pct}%', file=sys.stderr, flush=True)
        last[0] = pct

    nd, nf, total = build(sys.argv[1], sys.argv[2], progress)
    print(f'romfs_build.py: {sys.argv[2]} written ({nd} folders, {nf} files, {total / 2**30:.1f} GiB)', file=sys.stderr)


if __name__ == '__main__':
    main()
