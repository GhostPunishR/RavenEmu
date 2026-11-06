#include "input_driver.h"

#define BUTTON_MASK UINT32_C(0xffff)
#define PENDING_SHIFT 16u

void raven_input_set_buttons(raven_input *input, unsigned buttons) {
    const uint32_t held = (uint32_t)buttons & BUTTON_MASK;
    uint32_t previous = atomic_load_explicit(&input->live, memory_order_relaxed);
    uint32_t next;
    do {
        const uint32_t pressed = held & ~(previous & BUTTON_MASK);
        const uint32_t pending = (previous >> PENDING_SHIFT) | pressed;
        next = held | (pending << PENDING_SHIFT);
    } while (!atomic_compare_exchange_weak_explicit(
        &input->live, &previous, next, memory_order_relaxed, memory_order_relaxed));
}

void raven_input_clear_buttons(raven_input *input) {
    /* Do not mutate the owner's snapshot from the UI during a running frame. */
    atomic_store_explicit(&input->live, 0, memory_order_relaxed);
}

uint16_t raven_input_begin_frame(raven_input *input) {
    uint32_t previous = atomic_load_explicit(&input->live, memory_order_relaxed);
    while (!atomic_compare_exchange_weak_explicit(
        &input->live, &previous, previous & BUTTON_MASK,
        memory_order_relaxed, memory_order_relaxed)) {}
    input->frame = (uint16_t)((previous & BUTTON_MASK) | (previous >> PENDING_SHIFT));
    return input->frame;
}

int16_t raven_input_pressed(const raven_input *input, unsigned id) {
    return id < 16u && (input->frame & (UINT32_C(1) << id)) ? 1 : 0;
}
