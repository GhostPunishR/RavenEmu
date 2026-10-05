#include <ravenemu/nds/cartridge_header.hpp>

#include "crc16.hpp"

#include <algorithm>

namespace ravenemu::nds {

namespace {

[[nodiscard]] std::uint16_t read_u16(std::span<const std::uint8_t> rom, std::size_t offset) noexcept {
    return static_cast<std::uint16_t>(
        static_cast<std::uint16_t>(rom[offset]) |
        static_cast<std::uint16_t>(static_cast<std::uint16_t>(rom[offset + 1]) << 8U)
    );
}

[[nodiscard]] std::uint32_t read_u32(std::span<const std::uint8_t> rom, std::size_t offset) noexcept {
    return static_cast<std::uint32_t>(rom[offset]) |
        (static_cast<std::uint32_t>(rom[offset + 1]) << 8U) |
        (static_cast<std::uint32_t>(rom[offset + 2]) << 16U) |
        (static_cast<std::uint32_t>(rom[offset + 3]) << 24U);
}

/**
 * Extrait un champ texte de longueur fixe.
 *
 * Les octets nuls de remplissage sont retirés, et tout ce qui n'est pas
 * imprimable est remplacé plutôt que recopié : ce texte finit dans une
 * bibliothèque affichée à l'écran, et une ROM abîmée ne doit pas pouvoir y
 * injecter des caractères de contrôle.
 */
[[nodiscard]] std::string read_text(
    std::span<const std::uint8_t> rom,
    std::size_t offset,
    std::size_t length
) {
    std::string value;
    value.reserve(length);
    for (std::size_t index = 0; index < length; ++index) {
        const auto byte = rom[offset + index];
        if (byte == 0) break;
        value.push_back(byte >= 0x20 && byte < 0x7f ? static_cast<char>(byte) : '?');
    }
    while (!value.empty() && value.back() == ' ') value.pop_back();
    return value;
}

/** Vrai si un bloc [offset, offset+size) tient dans l'image. */
[[nodiscard]] bool block_fits(std::uint32_t offset, std::uint32_t size, std::size_t rom_size) noexcept {
    const auto end = static_cast<std::uint64_t>(offset) + static_cast<std::uint64_t>(size);
    return end <= static_cast<std::uint64_t>(rom_size);
}

} // namespace

CartridgeHeader CartridgeHeader::parse(std::span<const std::uint8_t> rom) {
    if (rom.size() < size) throw RomLoadError("ROM Nintendo DS trop courte pour porter un en-tête");
    if (rom.size() > max_rom_size) throw RomLoadError("ROM Nintendo DS trop volumineuse");

    CartridgeHeader header{};
    header.title = read_text(rom, 0x000, 12);
    header.game_code = read_text(rom, 0x00c, 4);
    header.maker_code = read_text(rom, 0x010, 2);

    const auto unit = rom[0x012];
    switch (unit) {
    case static_cast<std::uint8_t>(UnitCode::nintendo_ds):
    case static_cast<std::uint8_t>(UnitCode::nintendo_ds_and_dsi):
        header.unit_code = static_cast<UnitCode>(unit);
        break;
    case static_cast<std::uint8_t>(UnitCode::nintendo_dsi):
        // Refusé explicitement plutôt que démarré à moitié : une cartouche DSi
        // exclusive suppose un second processeur, une autre carte mémoire et
        // des périphériques que ce cœur ne prétend pas fournir.
        throw RomLoadError("Cartouche exclusivement Nintendo DSi non prise en charge");
    default:
        throw RomLoadError("Code unité Nintendo DS inconnu");
    }

    header.device_capacity_code = rom[0x014];
    header.rom_version = rom[0x01e];

    header.arm9_rom_offset = read_u32(rom, 0x020);
    header.arm9_entry_address = read_u32(rom, 0x024);
    header.arm9_ram_address = read_u32(rom, 0x028);
    header.arm9_size = read_u32(rom, 0x02c);

    header.arm7_rom_offset = read_u32(rom, 0x030);
    header.arm7_entry_address = read_u32(rom, 0x034);
    header.arm7_ram_address = read_u32(rom, 0x038);
    header.arm7_size = read_u32(rom, 0x03c);

    header.icon_title_offset = read_u32(rom, 0x068);
    header.total_used_rom_size = read_u32(rom, 0x080);
    header.header_size = read_u32(rom, 0x084);

    header.logo_crc = read_u16(rom, logo_crc_offset);
    header.header_crc = read_u16(rom, header_crc_offset);
    header.computed_header_crc = detail::crc16(rom.subspan(0, crc_covered_bytes));

    // Les deux blocs de code sont la seule chose dont le démarrage dépend
    // vraiment. Un en-tête qui les place hors du fichier ne décrit pas une
    // cartouche, quelle que soit la validité du reste.
    if (header.arm9_size == 0) throw RomLoadError("Bloc de code ARM9 vide");
    if (header.arm7_size == 0) throw RomLoadError("Bloc de code ARM7 vide");
    if (!block_fits(header.arm9_rom_offset, header.arm9_size, rom.size())) {
        throw RomLoadError("Bloc de code ARM9 hors de la ROM");
    }
    if (!block_fits(header.arm7_rom_offset, header.arm7_size, rom.size())) {
        throw RomLoadError("Bloc de code ARM7 hors de la ROM");
    }
    if (header.arm9_rom_offset < size || header.arm7_rom_offset < size) {
        throw RomLoadError("Bloc de code Nintendo DS recouvrant l'en-tête");
    }

    return header;
}

} // namespace ravenemu::nds
