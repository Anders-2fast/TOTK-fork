// romfs_tool <romfs folder> <romfs.bin>: the game data image the start screen builds during setup
// (runtime/romfs_build.c), as a command-line tool - for game files copied straight into the app's data folder over adb
// (android/push_game.sh), built on the device itself instead of a third copy of 6 GB.
#include <stdio.h>
#include <stdlib.h>

#include "romfs_build.h"

static void progress(uint64_t done, uint64_t total) {
    static int last = -1;
    int pct = total ? (int)(done * 100 / total) : 0;
    if (pct / 10 != last / 10) fprintf(stderr, "%d%%\n", pct);
    last = pct;
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s <romfs folder> <romfs.bin>\n", argv[0]);
        return 2;
    }
    char why[256] = "";
    if (!romfs_build(argv[1], argv[2], progress, why, sizeof why)) {
        fprintf(stderr, "romfs_tool: %s\n", why);
        return 1;
    }
    fprintf(stderr, "romfs_tool: %s written\n", argv[2]);
    return 0;
}
