#pragma once

#include "ravenemu/binary_io.hpp"

#include <algorithm>

namespace ravenemu::cgb {

/** Contrôleur KEY1 et transition de fréquence du CGB.
 *
 * Les périphériques LCD/APU restent cadencés sur l'horloge de base. Le CPU,
 * DIV/TIMA et le port série interne voient la fréquence doublée après la
 * transition. La transition elle-même bloque le CPU pendant une durée proche
 * de celle documentée par Nintendo (environ 16 ms vers double, 32 ms vers
 * normal), exprimée ici en dots de l'horloge de base pour rester indépendante
 * de la vitesse CPU courante.
 */
class SpeedController {
public:
    explicit SpeedController(bool cgb_mode) : cgb_mode_(cgb_mode) {}

    [[nodiscard]] int peripheral_shift() const noexcept { return double_speed_ ? 1 : 0; }
    [[nodiscard]] bool double_speed() const noexcept { return double_speed_; }
    [[nodiscard]] bool switching() const noexcept { return switch_dots_remaining_ > 0; }
    [[nodiscard]] int switch_dots_remaining() const noexcept { return switch_dots_remaining_; }

    [[nodiscard]] int read_key1() const noexcept {
        if (!cgb_mode_) return 0xff;
        return (double_speed_ ? 0x80 : 0) | (armed_ ? 1 : 0) | 0x7e;
    }

    void write_key1(int value) noexcept {
        if (cgb_mode_ && !switching()) armed_ = (value & 1) != 0;
    }

    /**
     * Appelé par STOP. Retourne true si STOP a lancé une transition de vitesse
     * et ne doit donc pas entrer en veille normale.
     */
    bool begin_switch_from_stop() noexcept {
        if (!cgb_mode_ || !armed_ || switching()) return false;
        armed_ = false;
        switch_dots_remaining_ = double_speed_ ? switch_to_normal_dots : switch_to_double_dots;
        return true;
    }

    /** Avance la transition avec l'horloge périphérique (4,194304 MHz). */
    void tick_peripheral(int dots) noexcept {
        if (switch_dots_remaining_ <= 0 || dots <= 0) return;
        switch_dots_remaining_ = std::max(0, switch_dots_remaining_ - dots);
        if (switch_dots_remaining_ == 0) double_speed_ = !double_speed_;
    }

    void save(detail::BinaryWriter& out) const {
        out.i32(double_speed_ ? 1 : 0);
        out.i32(armed_ ? 1 : 0);
        out.i32(switch_dots_remaining_);
    }

    void load(detail::BinaryReader& in) {
        double_speed_ = in.i32() != 0;
        armed_ = in.i32() != 0;
        switch_dots_remaining_ = std::max(0, in.i32());
    }

private:
    // 4,194,304 dots/s : 16 ms ~= 67,109 dots ; 32 ms ~= 134,218 dots.
    static constexpr int switch_to_double_dots = 67'109;
    static constexpr int switch_to_normal_dots = 134'218;

    bool cgb_mode_{};
    bool double_speed_{};
    bool armed_{};
    int switch_dots_remaining_{};
};

} // namespace ravenemu::cgb
