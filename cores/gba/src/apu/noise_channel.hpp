#pragma once

#include "apu/envelope.hpp"

namespace ravenemu::gba {

class NoiseChannel {
public:
    void trigger() noexcept {
        enabled = envelope.dac_enabled();
        if (length_counter == 0) length_counter = 64;
        timer_ = period(); lfsr_ = 0x7fff; envelope.trigger();
    }
    void write_polynomial(int value) noexcept {
        shift_clock_ = (value >> 4) & 15;
        width_mode_ = (value & 8) != 0;
        divisor_code_ = value & 7;
    }
    void tick(int cycles) noexcept {
        if (!enabled) return;
        timer_ -= cycles;
        const auto timer_period = period();
        while (timer_ <= 0) {
            timer_ += timer_period;
            const auto feedback = (lfsr_ & 1) ^ ((lfsr_ >> 1) & 1);
            lfsr_ = (lfsr_ >> 1) | (feedback << 14);
            if (width_mode_) lfsr_ = (lfsr_ & ~0x40) | (feedback << 6);
        }
    }
    void clock_length() noexcept {
        if (length_enabled && length_counter > 0 && --length_counter == 0) enabled = false;
    }
    [[nodiscard]] int output() const noexcept { return enabled && (lfsr_ & 1) == 0 ? envelope.volume : 0; }
    void reset() noexcept { enabled = false; timer_ = 0; lfsr_ = 0x7fff; length_counter = 0; }
    bool enabled{};
    int length_counter{};
    bool length_enabled{};
    Envelope envelope;
private:
    [[nodiscard]] int period() const noexcept {
        const auto divisor = divisor_code_ == 0 ? 8 : divisor_code_ * 16;
        return std::max(1, divisor << shift_clock_);
    }
    int shift_clock_{};
    bool width_mode_{};
    int divisor_code_{};
    int timer_{};
    int lfsr_{0x7fff};
};

} // namespace ravenemu::gba
