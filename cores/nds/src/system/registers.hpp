#pragma once

#include <cstdint>

namespace ravenemu::nds::registers {

/**
 * Registres que les deux processeurs possèdent chacun de leur côté.
 *
 * Ils sont ici plutôt qu'en double dans chaque carte, pour la même raison que
 * les utilitaires de champs de bits : deux copies dérivent, et une adresse
 * corrigée d'un côté laisserait l'autre sur l'ancienne.
 */
inline constexpr std::uint32_t sync = 0x0400'0180;
inline constexpr std::uint32_t queue_control = 0x0400'0184;
inline constexpr std::uint32_t queue_send = 0x0400'0188;
inline constexpr std::uint32_t queue_receive = 0x0410'0000;
inline constexpr std::uint32_t interrupt_master = 0x0400'0208;
inline constexpr std::uint32_t interrupt_enable = 0x0400'0210;
inline constexpr std::uint32_t interrupt_request = 0x0400'0214;

/** Octet [index] d'un registre de trente-deux bits. */
constexpr std::uint8_t byte_of(std::uint32_t value, std::uint32_t index) noexcept {
    return static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU);
}

/** Remplace l'octet [index] d'un registre de trente-deux bits. */
constexpr std::uint32_t with_byte(std::uint32_t value, std::uint32_t index, std::uint8_t byte) noexcept {
    const auto shift = index * 8U;
    return (value & ~(0xffU << shift)) | (static_cast<std::uint32_t>(byte) << shift);
}

} // namespace ravenemu::nds::registers
