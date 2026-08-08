#pragma once

#include "support/bits.hpp"

namespace ravenemu::gba {

class WaveChannel {
public:
    void trigger() noexcept {
        enabled = dac_enabled;
        if (length_counter == 0) length_counter = 256;
        timer_ = (2048 - frequency) * 2;
        position_ = 0;
    }
    void tick(int cycles) noexcept {
        if (!enabled) return;
        timer_ -= cycles;
        if (timer_ > 0) return;
        const auto period = std::max(1, (2048 - frequency) * 2);
        const auto steps = 1 + (-timer_) / period;
        timer_ += steps * period;
        position_ = (position_ + steps) & 31;
    }
    void clock_length() noexcept {
        if (length_enabled && length_counter > 0 && --length_counter == 0) enabled = false;
    }
    [[nodiscard]] int output() const noexcept {
        if (!enabled || !dac_enabled || volume_code == 0) return 0;
        const auto sample = wave_ram[static_cast<std::size_t>(position_)];
        return volume_code == 1 ? sample : volume_code == 2 ? sample >> 1 : sample >> 2;
    }
    void reset() noexcept { enabled = false; timer_ = 0; position_ = 0; length_counter = 0; }
    bool enabled{};
    bool dac_enabled{};
    int frequency{};
    int length_counter{};
    bool length_enabled{};
    int volume_code{};
    std::array<int, 32> wave_ram{};
private:
    int timer_{};
    int position_{};
};

} // namespace ravenemu::gba
