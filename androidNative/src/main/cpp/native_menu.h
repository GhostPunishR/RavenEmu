#ifndef RAVEN_NATIVE_MENU_H
#define RAVEN_NATIVE_MENU_H

/* All menu state transitions and drawing occur on the EGL/emulation thread.
 * Inputs: 0 toggle, 1 previous, 2 next, 3 activate, 4 close.
 * Action: 0 nothing, 1 resume, 2 library, 3 save, 4 load. */
int raven_menu_input(int command);
int raven_menu_visible(void);
void raven_menu_draw(int width, int height);
#endif
