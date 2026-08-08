#pragma once

#include "interrupt/interrupt_controller.hpp"

namespace ravenemu::cgb {

class Timer {
public:
    explicit Timer(InterruptController& interrupts) : interrupts_(interrupts) {}

    void tick(int cycles) noexcept {
        for (int i = 0; i < cycles; ++i) {
            if (reload_delay_ > 0 && --reload_delay_ == 0) {
                tima_ = tma_; interrupts_.request(Interrupt::timer); reload_delay_ = -1;
            }
            set_div_counter(div_counter_ + 1);
        }
    }
    [[nodiscard]] int read_div() const noexcept { return (div_counter_ >> 8) & 0xff; }
    void write_div() noexcept { set_div_counter(0); }
    [[nodiscard]] int read_tima() const noexcept { return tima_; }
    void write_tima(int value) noexcept { if (reload_delay_ > 0) reload_delay_ = -1; tima_ = byte(value); }
    [[nodiscard]] int read_tac() const noexcept { return tac_ | 0xf8; }
    void write_tac(int value) noexcept {
        const bool before = signal(div_counter_, tac_);
        tac_ = (value & 7) | 0xf8;
        if (before && !signal(div_counter_, tac_)) increment_tima();
    }
    [[nodiscard]] int tma() const noexcept { return tma_; }
    void set_tma(int value) noexcept { tma_ = byte(value); }
    void save(BinaryWriter& out) const {
        out.i32(div_counter_); out.i32(tima_); out.i32(tma_); out.i32(read_tac()); out.i32(reload_delay_);
    }
    void load(BinaryReader& in) {
        div_counter_ = word(in.i32()); tima_ = byte(in.i32()); tma_ = byte(in.i32());
        tac_ = in.i32() | 0xf8; reload_delay_ = in.i32();
    }

private:
    static int selected_mask(int tac) noexcept {
        switch (tac & 3) { case 0: return 1 << 9; case 1: return 1 << 3; case 2: return 1 << 5; default: return 1 << 7; }
    }
    static bool signal(int counter, int tac) noexcept {
        return (tac & 4) != 0 && (counter & selected_mask(tac)) != 0;
    }
    void increment_tima() noexcept { tima_ = byte(tima_ + 1); if (tima_ == 0) reload_delay_ = 4; }
    void set_div_counter(int value) noexcept {
        const bool before = signal(div_counter_, tac_);
        div_counter_ = word(value);
        if (before && !signal(div_counter_, tac_)) increment_tima();
    }

    InterruptController& interrupts_;
    int div_counter_{0xab00};
    int tima_{};
    int tma_{};
    int tac_{0xf8};
    int reload_delay_{-1};
};

} // namespace ravenemu::cgb
