#pragma once
#include <stdbool.h>

// LED: N short blinks every 2 s where N = role+1 (single=1, tx=2, rx=3).
//      Fast 5 Hz blink while at least one client is streaming.
// Button (BOOT): hold >= 3 s to cycle role single -> tx -> rx and reboot.
void status_io_start(void);
void status_io_set_streaming(bool streaming);
void status_io_set_connected(bool connected);   // rx role: joined the TX's AP
