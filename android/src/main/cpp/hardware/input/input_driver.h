#ifndef RAVEN_NATIVE_INPUT_H
#define RAVEN_NATIVE_INPUT_H

#include <stdatomic.h>
#include <stdint.h>

/* Low 16 bits: held Libretro joypad buttons. High 16 bits: rising edges
 * waiting for a frame. Packing both keeps set/clear/frame capture atomic. */
typedef struct raven_input {
    _Atomic uint32_t live;
    uint16_t frame; /* Only the emulation owner reads/writes this snapshot. */
} raven_input;

#define RAVEN_INPUT_INITIALIZER { ATOMIC_VAR_INIT(0), 0 }

/* UI/controller producers may call these while the emulation owner runs.
 * A release keeps a quick press pending; clear explicitly discards it. */
void raven_input_set_buttons(raven_input *input, unsigned buttons);
void raven_input_clear_buttons(raven_input *input);

/* Owner only: capture held buttons and consume pending presses immediately
 * before retro_run. Every input callback then sees the same frame snapshot. */
uint16_t raven_input_begin_frame(raven_input *input);
int16_t raven_input_pressed(const raven_input *input, unsigned id);

#endif
