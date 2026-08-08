#pragma once

#include "support/bits.hpp"

namespace ravenemu::cgb {

class SpeedController {
public:
    explicit SpeedController(bool cgb_mode) : cgb_mode_(cgb_mode) {}

    [[nodiscard]] int peripheral_shift() const noexcept { return double_speed_ ? 1 : 0; }
    [[nodiscard]] int read_key1() const noexcept {
        if (!cgb_mode_) return 0xff;
        return (double_speed_ ? 0x80 : 0) | (armed_ ? 1 : 0) | 0x7e;
    }
    void write_key1(int value) noexcept { if (cgb_mode_) armed_ = (value & 1) != 0; }
    bool on_stop() noexcept {
        if (!cgb_mode_ || !armed_) return false;
        double_speed_ = !double_speed_;
        armed_ = false;
        return true;
    }
    void save(BinaryWriter& out) const { out.i32(double_speed_ ? 1 : 0); out.i32(armed_ ? 1 : 0); }
    void load(BinaryReader& in) { double_speed_ = in.i32() != 0; armed_ = in.i32() != 0; }

private:
    bool cgb_mode_{};
    bool double_speed_{};
    bool armed_{};
};

} // namespace ravenemu::cgb
