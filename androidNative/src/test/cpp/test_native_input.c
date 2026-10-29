#include "native_input.h"
#include <libretro.h>
#include <stdio.h>

#define BUTTON(id) (1u << (id))
#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition); \
        return 0; \
    } \
} while (0)

static int initial_and_invalid_buttons(void) {
    raven_input input = RAVEN_INPUT_INITIALIZER;
    CHECK(raven_input_begin_frame(&input) == 0);
    for (unsigned id = 0; id < 16; ++id) CHECK(!raven_input_pressed(&input, id));
    CHECK(!raven_input_pressed(&input, 16));
    CHECK(!raven_input_pressed(&input, 256));
    raven_input_set_buttons(&input, 0xffff0000u);
    CHECK(raven_input_begin_frame(&input) == 0);
    return 1;
}

static int brief_press_lasts_one_frame(void) {
    raven_input input = RAVEN_INPUT_INITIALIZER;
    raven_input_set_buttons(&input, BUTTON(RETRO_DEVICE_ID_JOYPAD_A));
    raven_input_set_buttons(&input, 0);
    /* Neither a release nor extra screen refreshes consume the pending tap. */
    CHECK(!raven_input_pressed(&input, RETRO_DEVICE_ID_JOYPAD_A));
    CHECK(raven_input_begin_frame(&input) == BUTTON(RETRO_DEVICE_ID_JOYPAD_A));
    CHECK(raven_input_pressed(&input, RETRO_DEVICE_ID_JOYPAD_A));
    CHECK(raven_input_begin_frame(&input) == 0);
    return 1;
}

static int held_then_released(void) {
    raven_input input = RAVEN_INPUT_INITIALIZER;
    raven_input_set_buttons(&input, BUTTON(RETRO_DEVICE_ID_JOYPAD_B));
    for (int frame = 0; frame < 4; ++frame) {
        CHECK(raven_input_begin_frame(&input) == BUTTON(RETRO_DEVICE_ID_JOYPAD_B));
        /* Repeating the held mask must not queue another press. */
        raven_input_set_buttons(&input, BUTTON(RETRO_DEVICE_ID_JOYPAD_B));
    }
    raven_input_set_buttons(&input, 0);
    CHECK(raven_input_begin_frame(&input) == 0);
    return 1;
}

static int callbacks_share_one_snapshot(void) {
    raven_input input = RAVEN_INPUT_INITIALIZER;
    raven_input_set_buttons(&input, BUTTON(RETRO_DEVICE_ID_JOYPAD_A));
    raven_input_begin_frame(&input);
    raven_input_set_buttons(&input, BUTTON(RETRO_DEVICE_ID_JOYPAD_B));
    CHECK(raven_input_pressed(&input, RETRO_DEVICE_ID_JOYPAD_A));
    CHECK(!raven_input_pressed(&input, RETRO_DEVICE_ID_JOYPAD_B));
    CHECK(raven_input_begin_frame(&input) == BUTTON(RETRO_DEVICE_ID_JOYPAD_B));
    raven_input_clear_buttons(&input);
    /* A concurrent lifecycle clear takes effect at the next frame boundary. */
    CHECK(raven_input_pressed(&input, RETRO_DEVICE_ID_JOYPAD_B));
    CHECK(raven_input_begin_frame(&input) == 0);
    return 1;
}

static int combinations_keep_each_press(void) {
    raven_input input = RAVEN_INPUT_INITIALIZER;
    unsigned diagonal = BUTTON(RETRO_DEVICE_ID_JOYPAD_UP) | BUTTON(RETRO_DEVICE_ID_JOYPAD_RIGHT);
    raven_input_set_buttons(&input, diagonal);
    raven_input_set_buttons(&input, diagonal | BUTTON(RETRO_DEVICE_ID_JOYPAD_A));
    raven_input_set_buttons(&input, BUTTON(RETRO_DEVICE_ID_JOYPAD_A));
    CHECK(raven_input_begin_frame(&input) == (diagonal | BUTTON(RETRO_DEVICE_ID_JOYPAD_A)));
    CHECK(raven_input_begin_frame(&input) == BUTTON(RETRO_DEVICE_ID_JOYPAD_A));
    raven_input_set_buttons(&input, 0);
    CHECK(raven_input_begin_frame(&input) == 0);
    return 1;
}

static int clear_discards_held_and_pending(void) {
    raven_input input = RAVEN_INPUT_INITIALIZER;
    raven_input_set_buttons(&input, BUTTON(RETRO_DEVICE_ID_JOYPAD_START));
    raven_input_set_buttons(&input, 0);
    raven_input_set_buttons(&input, BUTTON(RETRO_DEVICE_ID_JOYPAD_SELECT));
    raven_input_clear_buttons(&input);
    CHECK(raven_input_begin_frame(&input) == 0);
    CHECK(raven_input_begin_frame(&input) == 0);
    /* A fresh press after resuming is still accepted. */
    raven_input_set_buttons(&input, BUTTON(RETRO_DEVICE_ID_JOYPAD_SELECT));
    CHECK(raven_input_begin_frame(&input) == BUTTON(RETRO_DEVICE_ID_JOYPAD_SELECT));
    return 1;
}

static int gba_shoulders_use_libretro_slots(void) {
    _Static_assert(RETRO_DEVICE_ID_JOYPAD_L == 10, "Libretro L slot changed");
    _Static_assert(RETRO_DEVICE_ID_JOYPAD_R == 11, "Libretro R slot changed");
    raven_input input = RAVEN_INPUT_INITIALIZER;
    raven_input_set_buttons(&input, BUTTON(10));
    raven_input_set_buttons(&input, BUTTON(10) | BUTTON(11));
    raven_input_set_buttons(&input, 0);
    CHECK(raven_input_begin_frame(&input) == (BUTTON(10) | BUTTON(11)));
    CHECK(raven_input_pressed(&input, RETRO_DEVICE_ID_JOYPAD_L));
    CHECK(raven_input_pressed(&input, RETRO_DEVICE_ID_JOYPAD_R));
    CHECK(!raven_input_pressed(&input, RETRO_DEVICE_ID_JOYPAD_X));
    CHECK(!raven_input_pressed(&input, RETRO_DEVICE_ID_JOYPAD_Y));
    CHECK(raven_input_begin_frame(&input) == 0);
    return 1;
}

int main(void) {
    if (!initial_and_invalid_buttons() || !brief_press_lasts_one_frame() ||
        !held_then_released() || !callbacks_share_one_snapshot() ||
        !combinations_keep_each_press() || !clear_discards_held_and_pending() ||
        !gba_shoulders_use_libretro_slots()) return 1;
    puts("Native joypad: 7 input scenarios passed");
    return 0;
}
