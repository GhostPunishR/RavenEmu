#pragma once

#include "ravenemu/binary_io.hpp"

namespace ravenemu::cgb {

/** Modèle du registre CGB RP (FF56).
 *
 * Le cœur expose l'état logique du phototransistor et de la LED sans dépendre
 * d'Android. Une future couche plateforme pourra relier deux instances ou un
 * périphérique externe. En l'absence de source IR, le récepteur voit "pas de
 * lumière", donc le bit d'entrée vaut 1 lorsque la réception est activée.
 */
class InfraredPort {
public:
    explicit InfraredPort(bool cgb_mode) : cgb_mode_(cgb_mode) {}

    [[nodiscard]] int read() const noexcept {
        if (!cgb_mode_) return 0xff;
        const bool receiver_enabled = (control_ & 0xc0) == 0xc0;
        const int input = receiver_enabled && !light_detected_ ? 0x02 : 0x00;
        return (control_ & 0xc1) | input | 0x3c;
    }

    void write(int value) noexcept {
        if (!cgb_mode_) return;
        control_ = value & 0xc1;
    }

    [[nodiscard]] bool led_on() const noexcept { return cgb_mode_ && (control_ & 1) != 0; }
    [[nodiscard]] bool receiver_enabled() const noexcept { return cgb_mode_ && (control_ & 0xc0) == 0xc0; }

    /** true = lumière IR détectée ; false = aucune lumière. */
    void set_light_detected(bool detected) noexcept { light_detected_ = detected; }
    [[nodiscard]] bool light_detected() const noexcept { return light_detected_; }

    void save(detail::BinaryWriter& out) const {
        out.i32(control_);
        out.boolean(light_detected_);
    }

    void load(detail::BinaryReader& in) {
        control_ = in.i32() & 0xc1;
        light_detected_ = in.boolean();
    }

private:
    bool cgb_mode_{};
    int control_{};
    bool light_detected_{};
};

} // namespace ravenemu::cgb
