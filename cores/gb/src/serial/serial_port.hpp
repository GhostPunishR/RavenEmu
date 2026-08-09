#pragma once

#include "interrupt/interrupt_controller.hpp"

namespace ravenemu::cgb {

class SerialPort {
public:
    SerialPort(InterruptController& interrupts, bool cgb_mode)
        : interrupts_(interrupts), cgb_mode_(cgb_mode) {}

    /**
     * Avance l'horloge série interne en cycles CPU. Les périodes restent 512
     * cycles (8 KHz) et 16 cycles (256 KHz CGB) : en double vitesse le nombre
     * de cycles CPU par seconde double lui aussi, ce qui double naturellement
     * la fréquence série réelle.
     */
    void tick(int cycles) noexcept {
        if (!internal_transfer_active() || cycles <= 0) return;
        int remaining = cycles;
        while (remaining > 0 && bits_remaining_ > 0) {
            const int slice = std::min(bit_timer_, remaining);
            bit_timer_ -= slice;
            remaining -= slice;
            if (bit_timer_ == 0) {
                shift_bit(1); // ligne SIN au repos = niveau haut
                if (bits_remaining_ > 0) bit_timer_ = bit_period();
            }
        }
    }

    /** Front d'horloge fourni par un périphérique externe/link cable. */
    void clock_external_bit(bool incoming_high) noexcept {
        if (!external_transfer_active()) return;
        shift_bit(incoming_high ? 1 : 0);
    }

    [[nodiscard]] int read_data() const noexcept { return data_; }
    void write_data(int value) noexcept { data_ = byte(value); }

    [[nodiscard]] int read_control() const noexcept {
        const int writable = cgb_mode_ ? (control_ & 0x83) : (control_ & 0x81);
        return writable | (cgb_mode_ ? 0x7c : 0x7e);
    }

    void write_control(int value) noexcept {
        const int mask = cgb_mode_ ? 0x83 : 0x81;
        control_ = value & mask;
        if ((control_ & 0x80) == 0) {
            bits_remaining_ = 0;
            bit_timer_ = 0;
            return;
        }
        bits_remaining_ = 8;
        bit_timer_ = internal_transfer_active() ? bit_period() : 0;
    }

    [[nodiscard]] bool transfer_active() const noexcept { return (control_ & 0x80) != 0; }
    [[nodiscard]] bool fast_clock() const noexcept { return cgb_mode_ && (control_ & 0x02) != 0; }
    [[nodiscard]] bool internal_clock() const noexcept { return (control_ & 0x01) != 0; }
    [[nodiscard]] int bits_remaining() const noexcept { return bits_remaining_; }

    void save(BinaryWriter& out) const {
        out.i32(data_);
        out.i32(control_);
        out.i32(bits_remaining_);
        out.i32(bit_timer_);
    }

    void load(BinaryReader& in) {
        data_ = byte(in.i32());
        control_ = in.i32() & (cgb_mode_ ? 0x83 : 0x81);
        bits_remaining_ = std::clamp(in.i32(), 0, 8);
        bit_timer_ = std::max(0, in.i32());
    }

private:
    [[nodiscard]] bool internal_transfer_active() const noexcept {
        return transfer_active() && internal_clock() && bits_remaining_ > 0;
    }
    [[nodiscard]] bool external_transfer_active() const noexcept {
        return transfer_active() && !internal_clock() && bits_remaining_ > 0;
    }
    [[nodiscard]] int bit_period() const noexcept { return fast_clock() ? 16 : 512; }

    void shift_bit(int incoming) noexcept {
        data_ = byte((data_ << 1) | (incoming & 1));
        if (bits_remaining_ > 0) --bits_remaining_;
        if (bits_remaining_ == 0) {
            control_ &= ~0x80;
            bit_timer_ = 0;
            interrupts_.request(Interrupt::serial);
        }
    }

    InterruptController& interrupts_;
    bool cgb_mode_{};
    int data_{};
    int control_{};
    int bits_remaining_{};
    int bit_timer_{};
};

} // namespace ravenemu::cgb
