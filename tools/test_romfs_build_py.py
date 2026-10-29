#!/usr/bin/env python3
"""tools/romfs_build.py against runtime/romfs_build.c (android/tools/romfs_tool.c, built with cc - or WSL's with --wsl,
from Git Bash on Windows): the same romfs.bin byte for byte, on a synthetic game folder and on any real ones given.

Usage: test_romfs_build_py.py [--wsl] [--cc cc] [romfs folder ...]
"""
import argparse
import hashlib
import os
import random
import shutil
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'tools'))
import romfs_build  # noqa: E402

p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
p.add_argument('--cc', default='cc')
p.add_argument('--wsl', action='store_true', help='build and run the C tool in WSL (Windows)')
p.add_argument('folders', nargs='*', type=Path, help='real romfs folders to compare too (e.g. a few of the game\'s)')
args = p.parse_args()
out = root / 'build' / 'romfs-py-tests'
shutil.rmtree(out, ignore_errors=True)
out.mkdir(parents=True, exist_ok=True)  # (Windows: a folder still open elsewhere outlives rmtree)


def host(path):  # a path as the C tool sees it
    if not args.wsl:
        return str(path)
    return subprocess.run(['wsl', 'wslpath', '-u', str(path).replace('\\', '/')], check=True, capture_output=True,
                          text=True).stdout.strip()


def run_c(cmd):
    subprocess.run((['wsl', '--cd', str(root).replace('\\', '/'), '-e'] if args.wsl else []) + cmd, check=True,
                   cwd=root, capture_output=True)


tool = out / 'romfs_tool'
run_c([args.cc, '-O2', '-Iruntime', '-o', host(tool), 'android/tools/romfs_tool.c', 'runtime/romfs_build.c'])

# synthetic: odd sizes (16-byte alignment), empty files and folders, hidden entries the image leaves out, one folder of
# 40 files (the prime bucket count past 19), names that sort differently by bytes than by case
game = out / 'game'
rng = random.Random(1)
files = {'a.bin': 1, 'B.bin': 0, 'zz/deep/x/y.dat': 77, 'Pack/ZsDic.pack.zs': 4099, 'Mals/USen.Product.142.sarc.zs': 17,
         'Event/Movie/Op.webm': 5000, '.hidden': 3, 'sub/.DS_Store': 5, '_under/x': 33}
files.update({f'many/f{i:03d}.bfres': rng.randrange(0, 300) for i in range(40)})
for rel, size in files.items():
    f = game / rel
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_bytes(rng.randbytes(size))
(game / 'empty').mkdir(exist_ok=True)

ok = True
for i, folder in enumerate([game] + args.folders):
    c_out, py_out = out / f'c{i}.bin', out / f'py{i}.bin'
    run_c([host(tool), host(folder), host(c_out)])
    romfs_build.build(str(folder), str(py_out))
    a, b = hashlib.sha256(c_out.read_bytes()).hexdigest(), hashlib.sha256(py_out.read_bytes()).hexdigest()
    same = a == b
    ok &= same
    print(f'{"same" if same else "DIFFERENT"}: {folder} ({c_out.stat().st_size} bytes)')
print('OK' if ok else 'FAILED')
sys.exit(0 if ok else 1)
