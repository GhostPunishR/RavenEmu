#pragma once

#include "support/bits.hpp"

namespace ravenemu::cgb {

class Bus {
public:
    virtual ~Bus() = default;
    [[nodiscard]] virtual int read(int address) = 0;
    virtual void write(int address, int value) = 0;

    /** Retourne true lorsque STOP déclenche un changement de vitesse CGB. */
    virtual bool on_stop() { return false; }
    /** Consomme une demande de réveil du mode STOP normal. */
    virtual bool stop_wake_requested() { return false; }
    /** Le CPU doit rester arrêté pendant un transfert DMA VRAM. */
    [[nodiscard]] virtual bool cpu_blocked() const noexcept { return false; }

    void write_word(int address, int value) {
        write(address, value); write(word(address + 1), value >> 8);
    }
};

} // namespace ravenemu::cgb
