#pragma once

#include <cstdint>

namespace ravenemu::nds {

/**
 * Frontière mémoire vue par un processeur.
 *
 * Le processeur ne connaît pas la carte mémoire de la console : il demande des
 * octets à une adresse et n'en sait pas davantage. C'est ce qui permet de
 * l'éprouver contre une simple mémoire de test, sans banques vidéo, sans
 * cartouche et sans second processeur — donc de séparer les fautes du
 * processeur de celles de la machine.
 *
 * Les largeurs sont distinctes plutôt que dérivées d'un accès octet : sur cette
 * console un accès 32 bits n'est pas quatre accès 8 bits, ni en durée ni pour
 * les périphériques qui réagissent à la largeur.
 */
class Bus {
public:
    virtual ~Bus() = default;

    [[nodiscard]] virtual std::uint8_t read8(std::uint32_t address) = 0;
    [[nodiscard]] virtual std::uint16_t read16(std::uint32_t address) = 0;
    [[nodiscard]] virtual std::uint32_t read32(std::uint32_t address) = 0;

    virtual void write8(std::uint32_t address, std::uint8_t value) = 0;
    virtual void write16(std::uint32_t address, std::uint16_t value) = 0;
    virtual void write32(std::uint32_t address, std::uint32_t value) = 0;
};

} // namespace ravenemu::nds
