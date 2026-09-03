#pragma once

#include <cstddef>
#include <cstdint>
#include <span>

namespace ravenemu::nds::detail {

/**
 * CRC-16 des en-têtes de cartouche Nintendo DS.
 *
 * Variante réfléchie de polynôme `0x8005`, écrite ici sous sa forme inversée
 * `0xA001`, initialisée à `0xFFFF` et sans inversion finale. C'est l'algorithme
 * couramment désigné CRC-16/MODBUS, et c'est celui que la console applique aux
 * 0x15E premiers octets de l'en-tête ainsi qu'au logo.
 *
 * La table n'est pas précalculée : le calcul ne porte que sur quelques centaines
 * d'octets, une seule fois par chargement de ROM. Une table de 512 octets
 * résidente coûterait plus qu'elle ne rapporte.
 *
 * La valeur initiale est un paramètre parce que le service rendu au jeu par le
 * programme d'amorçage la reçoit de lui : c'est ainsi qu'une somme se calcule
 * sur un contenu découpé en plusieurs morceaux.
 */
[[nodiscard]] constexpr std::uint16_t crc16(
    std::span<const std::uint8_t> data,
    std::uint16_t initial = 0xffffU
) noexcept {
    std::uint16_t crc = initial;
    for (const auto byte : data) {
        crc = static_cast<std::uint16_t>(crc ^ byte);
        for (int bit = 0; bit < 8; ++bit) {
            const bool carry = (crc & 1U) != 0U;
            crc = static_cast<std::uint16_t>(crc >> 1U);
            if (carry) crc = static_cast<std::uint16_t>(crc ^ 0xa001U);
        }
    }
    return crc;
}

} // namespace ravenemu::nds::detail
