#include "system/firmware.hpp"

#include "crc16.hpp"

#include <span>
#include <string_view>

namespace ravenemu::nds {

namespace {

/**
 * Le nom inscrit dans les réglages.
 *
 * Il est écrit en seize bits par caractère, comme la console le fait. Aucun
 * caractère hors de l'alphabet latin n'y figure, si bien que l'encodage se
 * réduit à un octet suivi d'un zéro.
 */
constexpr std::string_view nickname = "RavenEmu";

/** Couleur préférée, sur les seize que la console propose. */
constexpr std::uint8_t favourite_colour = 6;

// Un anniversaire est un champ obligatoire de la structure, et un mois nul la
// rendrait invalide. Le premier jour du premier mois n'est celui de personne.
constexpr std::uint8_t birthday_month = 1;
constexpr std::uint8_t birthday_day = 1;

} // namespace

Firmware::Firmware() noexcept {
    build_settings();
    settings_pointer_ = static_cast<std::uint16_t>(
        first_settings_address / settings_pointer_unit);
}

void Firmware::write16(std::uint32_t offset, std::uint16_t value) noexcept {
    // Poids faible d'abord : c'est l'ordre du processeur, et le bloc se lit par
    // des chargements de seize bits, non octet par octet.
    settings_[offset] = static_cast<std::uint8_t>(value & 0xffU);
    settings_[offset + 1U] = static_cast<std::uint8_t>(value >> 8U);
}

void Firmware::build_settings() noexcept {
    settings_.fill(0);

    settings_[version_offset] = settings_version;
    settings_[favourite_colour_offset] = favourite_colour;
    settings_[birthday_month_offset] = birthday_month;
    settings_[birthday_day_offset] = birthday_day;

    for (std::size_t index = 0; index < nickname.size(); ++index) {
        write16(
            nickname_offset + static_cast<std::uint32_t>(index) * 2U,
            static_cast<std::uint16_t>(nickname[index])
        );
    }
    write16(nickname_length_offset, static_cast<std::uint16_t>(nickname.size()));
    // Le message personnel reste vide. Un jeu qui l'affiche montre une ligne
    // blanche, ce qui est un état légitime de la console.
    write16(message_length_offset, 0);

    // L'étalonnage, dans l'ordre où la console le range : la mesure des deux
    // axes du premier point, puis ses deux pixels, puis la même chose pour le
    // second. Les mesures découlent des pixels, elles ne sont pas posées à part.
    write16(calibration_offset, static_cast<std::uint16_t>(first_point_x * touch_scale));
    write16(calibration_offset + 2U, static_cast<std::uint16_t>(first_point_y * touch_scale));
    settings_[calibration_offset + 4U] = first_point_x;
    settings_[calibration_offset + 5U] = first_point_y;
    write16(calibration_offset + 6U, static_cast<std::uint16_t>(second_point_x * touch_scale));
    write16(calibration_offset + 8U, static_cast<std::uint16_t>(second_point_y * touch_scale));
    settings_[calibration_offset + 10U] = second_point_x;
    settings_[calibration_offset + 11U] = second_point_y;

    write16(language_offset, language_english);
    write16(update_count_offset, 0);

    // La somme de contrôle vient en dernier : elle porte sur tout ce qui
    // précède le compteur de mise à jour, et un champ écrit après elle la
    // rendrait fausse.
    const auto checksum = detail::crc16(
        std::span<const std::uint8_t>(settings_.data(), update_count_offset));
    write16(checksum_offset, checksum);
}

std::uint8_t Firmware::byte_at(std::uint32_t address) const noexcept {
    if (address >= size_bytes) return erased_byte;

    if (address >= settings_pointer_address && address < settings_pointer_address + 2U) {
        const auto index = address - settings_pointer_address;
        return static_cast<std::uint8_t>((settings_pointer_ >> (index * 8U)) & 0xffU);
    }

    // Les deux exemplaires portent le même contenu : la console en garde deux
    // pour survivre à une écriture interrompue, et aucune écriture n'a lieu ici.
    for (const auto base : {first_settings_address, second_settings_address}) {
        if (address >= base && address - base < settings_bytes) {
            return settings_[address - base];
        }
    }

    return erased_byte;
}

} // namespace ravenemu::nds
