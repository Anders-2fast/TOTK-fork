#!/bin/bash
# android/build_romfs.sh <game folder with romfs/>: its romfs.bin built here, on the computer, into <game folder>/romfs.bin
# (tools/romfs_build.py: runtime/romfs_build.c's image, the one the app's setup builds, in Python). For
# android/push_game.sh ROMFS_HOST=1, which pushes that one file instead of romfs/: TOTK's romfs is 304k files, which adb
# copies slowly and lost the connection on halfway once. Skipped when it's there (.romfs.bin.built beside it); delete
# that to build it again.
# On Windows, Defender scans each file the builder opens (~50 ms apiece: hours for TOTK's 304k). The game's own image is
# the same thing and comes out of hactoolnet in 3 minutes: hactoolnet -t nca <update nca> --basenca <game nca> --romfs
# <game folder>/romfs.bin, then touch <game folder>/.romfs.bin.built (docs/TOTK-PORT.md).
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=${1:?usage: android/build_romfs.sh <folder with romfs/>}
[ -d "$SRC/romfs" ] || { echo "no $SRC/romfs"; exit 1; }
if [ -f "$SRC/.romfs.bin.built" ]; then
    echo "$SRC/romfs.bin is there"
    exit 0
fi
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) PY=tools/venv/Scripts/python.exe ;; *) PY=tools/venv/bin/python ;; esac
[ -x "$PY" ] || PY=python3
echo "building $SRC/romfs.bin..."
# (into romfs.bin.part, renamed when complete: an interrupted build doesn't leave a romfs.bin that looks done)
"$PY" tools/romfs_build.py "$SRC/romfs" "$SRC/romfs.bin.part"
mv "$SRC/romfs.bin.part" "$SRC/romfs.bin"
touch "$SRC/.romfs.bin.built"
ls -la "$SRC/romfs.bin"
