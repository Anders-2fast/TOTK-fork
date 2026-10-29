// What the runtime asks about the game's own state (menus, logs, the renderers). Nothing of BOTW's state is mapped
// yet: these say "not in play" until hooks into the game's code find out (docs/botw/BOTW-PORT.md).
#include <stdint.h>

#include "../hle.h"

int g_start_screen;  // runtime/ui/launcher.mm: the start screen is up - before the first boot nothing is loaded here

// A save is loaded and the game is in play (not the title screen, a loading screen or the start screen).
int game_in_play(void) { return 0; }

// Current level / area name for logs ("-" when not in play).
const char *game_level_name(void) { return "-"; }
