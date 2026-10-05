#pragma once

#include <ravenemu/core.hpp>

#include <array>
#include <cstdint>
#include <span>
#include <string>

namespace ravenemu::nds {

/** Modèle de console déclaré par l'octet de code unité de l'en-tête. */
enum class UnitCode : std::uint8_t {
    /** Cartouche Nintendo DS. */
    nintendo_ds = 0x00,
    /** Cartouche hybride, démarrable sur DS comme sur DSi. */
    nintendo_ds_and_dsi = 0x02,
    /** Cartouche exclusivement DSi : hors du périmètre de ce cœur. */
    nintendo_dsi = 0x03,
};

/**
 * En-tête de cartouche Nintendo DS, tel qu'il occupe les premiers octets de la
 * ROM.
 *
 * Seuls sont retenus les champs dont le cœur ou la bibliothèque ont réellement
 * besoin. Les zones réservées, la table des fichiers, les recouvrements et la
 * zone sécurisée sont volontairement laissés de côté tant qu'aucun code ne les
 * consomme : un champ décodé mais inutilisé est une affirmation que rien ne
 * vérifie.
 */
struct CartridgeHeader {
    /** Taille de l'en-tête décodée ici, en octets. */
    static constexpr std::size_t size = 0x200;
    /** Étendue couverte par la somme de contrôle de l'en-tête. */
    static constexpr std::size_t crc_covered_bytes = 0x15e;
    /** Emplacement de la somme de contrôle de l'en-tête. */
    static constexpr std::size_t header_crc_offset = 0x15e;
    /** Emplacement de la somme de contrôle du logo. */
    static constexpr std::size_t logo_crc_offset = 0x15c;
    /** Taille maximale d'une cartouche adressable, 4 Gio. */
    static constexpr std::uint64_t max_rom_size = 1ULL << 32U;

    /** Titre interne, sans les octets de remplissage. */
    std::string title;
    /** Code jeu à quatre caractères. */
    std::string game_code;
    /** Code éditeur à deux caractères. */
    std::string maker_code;

    UnitCode unit_code{};
    /** Version de la ROM déclarée par l'en-tête. */
    std::uint8_t rom_version{};
    /**
     * Code de capacité de la puce. La taille annoncée vaut 128 Kio décalés de
     * cette valeur ; elle décrit la puce, pas la longueur du fichier.
     */
    std::uint8_t device_capacity_code{};

    std::uint32_t arm9_rom_offset{};
    std::uint32_t arm9_entry_address{};
    std::uint32_t arm9_ram_address{};
    std::uint32_t arm9_size{};

    std::uint32_t arm7_rom_offset{};
    std::uint32_t arm7_entry_address{};
    std::uint32_t arm7_ram_address{};
    std::uint32_t arm7_size{};

    /** Emplacement du bloc icône et titres, ou zéro s'il est absent. */
    std::uint32_t icon_title_offset{};
    /** Longueur utile annoncée par l'en-tête. */
    std::uint32_t total_used_rom_size{};
    /** Longueur de l'en-tête annoncée par l'en-tête. */
    std::uint32_t header_size{};

    /** Somme de contrôle lue dans l'en-tête. */
    std::uint16_t header_crc{};
    /** Somme recalculée sur les octets couverts. */
    std::uint16_t computed_header_crc{};
    /** Somme du logo lue dans l'en-tête. */
    std::uint16_t logo_crc{};

    /**
     * Vrai lorsque la somme de contrôle de l'en-tête concorde.
     *
     * Une divergence n'empêche pas le chargement : elle est rapportée, comme le
     * fait déjà le cœur Game Boy Advance pour sa propre somme. Une partie des
     * ROMs amateur sont assemblées sans somme correcte alors qu'elles démarrent
     * sur console ; les refuser reviendrait à confondre « en-tête inhabituel »
     * et « fichier inexploitable ».
     */
    [[nodiscard]] bool header_crc_valid() const noexcept { return header_crc == computed_header_crc; }

    /** Taille annoncée par le code de capacité, en octets. */
    [[nodiscard]] std::uint64_t declared_capacity_bytes() const noexcept {
        return static_cast<std::uint64_t>(128U * 1024U) << device_capacity_code;
    }

    /**
     * Décode et contrôle l'en-tête d'une image de cartouche.
     *
     * Ne sont refusés que les fichiers structurellement inexploitables : trop
     * courts pour porter un en-tête, portant un code unité que ce cœur ne
     * couvre pas, ou décrivant des blocs de code processeur qui sortent du
     * fichier. Tout le reste est décodé et rapporté.
     *
     * @throws RomLoadError si l'image ne peut pas décrire une cartouche.
     */
    [[nodiscard]] static CartridgeHeader parse(std::span<const std::uint8_t> rom);
};

} // namespace ravenemu::nds
