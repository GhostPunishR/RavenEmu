#pragma once

#include "interrupt/interrupt_controller.hpp"

namespace ravenemu::cgb {

class Joypad {
public:
    explicit Joypad(InterruptController& interrupts) : interrupts_(interrupts) {}

    void set_button(Button button, bool pressed) noexcept {
        bool action{};
        int bit{};
        switch (button) {
        case Button::a: action = true; bit = 1; break;
        case Button::b: action = true; bit = 2; break;
        case Button::select: action = true; bit = 4; break;
        case Button::start: action = true; bit = 8; break;
        case Button::right: bit = 1; break;
        case Button::left: bit = 2; break;
        case Button::up: bit = 4; break;
        case Button::down: bit = 8; break;
        case Button::l: case Button::r: case Button::x: case Button::y: return;
        }
        auto& state = action ? action_state_ : direction_state_;
        const bool was_pressed = (state & bit) != 0;
        state = pressed ? state | bit : state & ~bit;
        if (pressed && !was_pressed) stop_wake_pending_ = true;
        const bool selected = (action && (select_ & 0x20) == 0) || (!action && (select_ & 0x10) == 0);
        if (pressed && !was_pressed && selected) interrupts_.request(Interrupt::joypad);
    }

    [[nodiscard]] int read() const noexcept {
        int lines = 0x0f;
        if ((select_ & 0x10) == 0) lines &= ~direction_state_;
        if ((select_ & 0x20) == 0) lines &= ~action_state_;
        return 0xc0 | select_ | (lines & 0x0f);
    }
    void write(int value) noexcept { select_ = value & 0x30; }

    [[nodiscard]] bool take_stop_wake() noexcept {
        const bool pending = stop_wake_pending_;
        stop_wake_pending_ = false;
        return pending;
    }

    void save(BinaryWriter& out) const { out.i32(select_); }
    void load(BinaryReader& in) { select_ = in.i32() & 0x30; stop_wake_pending_ = false; }

private:
    InterruptController& interrupts_;
    int select_{0x30};
    int action_state_{};
    int direction_state_{};
    bool stop_wake_pending_{};
};

} // namespace ravenemu::cgb
