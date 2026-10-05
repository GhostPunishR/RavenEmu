#pragma once

#include <cstdint>

/**
 * Découpage de champs d'instruction.
 *
 * Les deux jeux, ARM et Thumb, décodent les mêmes sortes de champs. Les tenir
 * ici plutôt qu'en double dans chaque fichier évite qu'une correction d'un côté
 * laisse l'autre avec l'ancienne version — le genre d'écart qui ne se voit qu'à
 * l'exécution d'un jeu.
 */
namespace ravenemu::nds::detail {

/** Extrait [count] bits à partir du rang [low]. */
constexpr std::uint32_t bits(std::uint32_t value, unsigned low, unsigned count) noexcept {
    return (value >> low) & ((1U << count) - 1U);
}

constexpr bool bit(std::uint32_t value, unsigned index) noexcept {
    return ((value >> index) & 1U) != 0U;
}

constexpr std::uint32_t rotate_right(std::uint32_t value, std::uint32_t amount) noexcept {
    amount &= 31U;
    if (amount == 0U) return value;
    return (value >> amount) | (value << (32U - amount));
}

/** Étend un champ signé de [width] bits sur 32. */
constexpr std::uint32_t sign_extend(std::uint32_t value, unsigned width) noexcept {
    const auto shift = 32U - width;
    return static_cast<std::uint32_t>(static_cast<std::int32_t>(value << shift) >> shift);
}

} // namespace ravenemu::nds::detail
