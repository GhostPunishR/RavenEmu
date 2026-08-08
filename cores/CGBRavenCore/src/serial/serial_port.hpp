#pragma once

#include "interrupt/interrupt_controller.hpp"

namespace ravenemu::cgb {

class SerialPort {
public:
    explicit SerialPort(InterruptController& interrupts) : interrupts_(interrupts) {}

    void tick(int cycles) noexcept {
        if (remaining_cycles_ <= 0) return;
        remaining_cycles_ -= cycles;
        if (remaining_cycles_ <= 0) {
            remaining_cycles_ = 0; data_ = 0xff; control_ &= 0x7f;
            interrupts_.request(Interrupt::serial);
        }
    }
    [[nodiscard]] int read_data() const noexcept { return data_; }
    void write_data(int value) noexcept { data_ = byte(value); }
    [[nodiscard]] int read_control() const noexcept { return control_ | 0x7e; }
    void write_control(int value) noexcept {
        control_ = (value & 0x81) | 0x7e;
        remaining_cycles_ = (value & 0x81) == 0x81 ? 4096 : 0;
    }
    void save(BinaryWriter& out) const {
        out.i32(data_); out.i32(control_); out.i32(remaining_cycles_);
    }
    void load(BinaryReader& in) {
        data_ = byte(in.i32()); control_ = in.i32(); remaining_cycles_ = in.i32();
    }

private:
    InterruptController& interrupts_;
    int data_{};
    int control_{0x7e};
    int remaining_cycles_{};
};

} // namespace ravenemu::cgb
