#pragma once

#include "apu/envelope.hpp"

namespace ravenemu::gba {

class SquareChannel {
public:
    explicit SquareChannel(bool sweep) : has_sweep_(sweep) {}
    void trigger() {
        enabled = envelope.dac_enabled();
        if (length_counter == 0) length_counter = 64;
        timer_ = (2048 - frequency) * 4;
        envelope.trigger();
        if (has_sweep_) {
            sweep_shadow_ = frequency;
            sweep_timer_ = sweep_period_ == 0 ? 8 : sweep_period_;
            sweep_active_ = sweep_period_ != 0 || sweep_shift_ != 0;
            if (sweep_shift_ != 0) compute_sweep(false);
        }
    }
    void write_sweep(int value) noexcept {
        sweep_period_ = (value >> 4) & 7;
        sweep_negate_ = (value & 8) != 0;
        sweep_shift_ = value & 7;
    }
    void tick(int cycles) noexcept {
        if (!enabled) return;
        timer_ -= cycles;
        if (timer_ > 0) return;
        const auto period = std::max(1, (2048 - frequency) * 4);
        const auto steps = 1 + (-timer_) / period;
        timer_ += steps * period;
        duty_step_ = (duty_step_ + steps) & 7;
    }
    void clock_length() noexcept {
        if (length_enabled && length_counter > 0 && --length_counter == 0) enabled = false;
    }
    void clock_sweep() {
        if (!has_sweep_ || !sweep_active_) return;
        if (sweep_timer_ > 0) --sweep_timer_;
        if (sweep_timer_ != 0) return;
        sweep_timer_ = sweep_period_ == 0 ? 8 : sweep_period_;
        if (sweep_period_ != 0) compute_sweep(true);
    }
    [[nodiscard]] int output() const noexcept {
        static constexpr std::array masks{0x80, 0x81, 0xe1, 0x7e};
        return enabled && ((masks[static_cast<std::size_t>(duty)] >> duty_step_) & 1) != 0
            ? envelope.volume : 0;
    }
    void reset() noexcept { enabled = false; timer_ = 0; duty_step_ = 0; length_counter = 0; }
    bool enabled{};
    int duty{2};
    int frequency{};
    int length_counter{};
    bool length_enabled{};
    Envelope envelope;
private:
    void compute_sweep(bool apply) noexcept {
        const auto delta = sweep_shadow_ >> sweep_shift_;
        const auto next = sweep_negate_ ? sweep_shadow_ - delta : sweep_shadow_ + delta;
        if (next > 2047) { enabled = false; return; }
        if (apply && sweep_shift_ != 0) { sweep_shadow_ = next; frequency = next; }
    }
    bool has_sweep_{};
    int timer_{};
    int duty_step_{};
    int sweep_period_{};
    bool sweep_negate_{};
    int sweep_shift_{};
    int sweep_timer_{};
    int sweep_shadow_{};
    bool sweep_active_{};
};

} // namespace ravenemu::gba
