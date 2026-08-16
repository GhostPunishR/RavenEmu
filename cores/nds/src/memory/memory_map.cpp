#include "memory/memory_map.hpp"

#include <algorithm>
#include <numeric>

namespace ravenemu::nds {

namespace {

/**
 * Description d'une banque vidéo.
 *
 * Les banques n'ont ni la même taille ni la même place dans la fenêtre de
 * transfert, et le champ qui choisit leur destination n'a pas la même largeur
 * partout. Les trois tiennent ici plutôt que dans une suite de conditions.
 */
struct BankLayout {
    /** Étendue de la banque, en octets. */
    std::uint32_t size;
    /** Décalage dans la fenêtre de transfert. */
    std::uint32_t transfer_offset;
    /** Bits du champ de destination, dont la largeur varie selon la banque. */
    std::uint8_t destination_mask;
};

constexpr std::array<BankLayout, MemoryMap::vram_bank_count> bank_layouts{{
    {128U * 1024U, 0x0'0000U, 0x3},   // A
    {128U * 1024U, 0x2'0000U, 0x3},   // B
    {128U * 1024U, 0x4'0000U, 0x7},   // C
    {128U * 1024U, 0x6'0000U, 0x7},   // D
    { 64U * 1024U, 0x8'0000U, 0x7},   // E
    { 16U * 1024U, 0x9'0000U, 0x7},   // F
    { 16U * 1024U, 0x9'4000U, 0x7},   // G
    { 32U * 1024U, 0x9'8000U, 0x3},   // H
    { 16U * 1024U, 0xa'0000U, 0x3},   // I
}};

/** Bit qui allume une banque ; éteinte, elle ne répond nulle part. */
constexpr std::uint8_t bank_enabled = 0x80;

constexpr std::uint32_t vram_total_bytes = []() {
    std::uint32_t total = 0;
    for (const auto& layout : bank_layouts) total += layout.size;
    return total;
}();

/** Décalage d'une banque dans le bloc unique qui les porte toutes. */
constexpr std::array<std::uint32_t, MemoryMap::vram_bank_count> bank_offsets = []() {
    std::array<std::uint32_t, MemoryMap::vram_bank_count> offsets{};
    std::uint32_t cursor = 0;
    for (std::size_t index = 0; index < bank_layouts.size(); ++index) {
        offsets[index] = cursor;
        cursor += bank_layouts[index].size;
    }
    return offsets;
}();

} // namespace

MemoryMap::MemoryMap()
    : main_ram_(main_ram_bytes, 0),
      shared_wram_(shared_wram_bytes, 0),
      palette_(palette_bytes, 0),
      oam_(oam_bytes, 0),
      vram_(vram_total_bytes, 0) {}

void MemoryMap::reset() noexcept {
    std::fill(main_ram_.begin(), main_ram_.end(), std::uint8_t{0});
    std::fill(shared_wram_.begin(), shared_wram_.end(), std::uint8_t{0});
    std::fill(palette_.begin(), palette_.end(), std::uint8_t{0});
    std::fill(oam_.begin(), oam_.end(), std::uint8_t{0});
    std::fill(vram_.begin(), vram_.end(), std::uint8_t{0});
    std::fill(vram_control_.begin(), vram_control_.end(), std::uint8_t{0});
    // Au démarrage, le processeur principal reçoit toute la mémoire commune.
    shared_wram_control_ = 0;
    unmapped_ = 0;
    first_unmapped_ = 0;
    unimplemented_io_ = 0;
    first_unimplemented_io_ = 0;
}

std::span<std::uint8_t> MemoryMap::vram_bank(std::size_t index) noexcept {
    if (index >= vram_bank_count) return {};
    return std::span<std::uint8_t>{vram_}.subspan(bank_offsets[index], bank_layouts[index].size);
}

MemoryMap::SharedWindow MemoryMap::shared_window() const noexcept {
    // Quatre découpages, et l'un d'eux ne laisse rien au processeur principal.
    switch (shared_wram_control_) {
    case 0: return {0U, shared_wram_bytes};
    case 1: return {shared_wram_bytes / 2U, shared_wram_bytes / 2U};
    case 2: return {0U, shared_wram_bytes / 2U};
    default: return {0U, 0U};
    }
}

void MemoryMap::note_unmapped(std::uint32_t address) noexcept {
    if (unmapped_ == 0U) first_unmapped_ = address;
    ++unmapped_;
}

void MemoryMap::note_unimplemented_io(std::uint32_t address) noexcept {
    if (unimplemented_io_ == 0U) first_unimplemented_io_ = address;
    ++unimplemented_io_;
}

MemoryMap::Location MemoryMap::locate_video(std::uint32_t address) noexcept {
    // Chaque banque est bornée par ses deux extrémités, comparées à l'adresse
    // elle-même. Passer par un décalage relatif à la fenêtre demanderait de se
    // garder d'un débordement par le bas, et cette garde ne serait jamais
    // exercée puisque les bornes la rendent inutile.
    for (std::size_t index = 0; index < bank_layouts.size(); ++index) {
        const auto& layout = bank_layouts[index];
        const auto base = vram_transfer_base + layout.transfer_offset;
        if (address < base || address >= base + layout.size) continue;
        // Une banque éteinte ne répond pas, et une banque dirigée vers un moteur
        // graphique n'est plus visible par cette fenêtre.
        const auto control = vram_control_[index];
        if ((control & bank_enabled) == 0U) return {Region::video, nullptr};
        if ((control & layout.destination_mask) != 0U) return {Region::video, nullptr};
        return {Region::video, &vram_[bank_offsets[index] + (address - base)]};
    }
    // Au-delà des banques, ou en deçà, ce sont les fenêtres des moteurs
    // graphiques : leur aiguillage viendra avec eux.
    return {Region::video, nullptr};
}

MemoryMap::Location MemoryMap::locate(std::uint32_t address) noexcept {
    switch (address >> 24U) {
    case 0x02:
        return {Region::main_ram, &main_ram_[address % main_ram_bytes]};
    case 0x03: {
        const auto window = shared_window();
        if (window.size == 0U) return {Region::shared_wram, nullptr};
        return {Region::shared_wram, &shared_wram_[window.offset + (address % window.size)]};
    }
    case 0x04:
        return {Region::input_output, nullptr};
    case 0x05:
        return {Region::palette, &palette_[address % palette_bytes]};
    case 0x06:
        return locate_video(address);
    case 0x07:
        return {Region::object_attributes, &oam_[address % oam_bytes]};
    default:
        // Tout le reste n'est pas décodé, et pour trois raisons différentes que
        // rien ne distingue encore : les adresses basses appartiennent à la
        // mémoire locale du cœur, que le processeur consulte avant le bus ; le
        // haut de l'espace revient au BIOS, qui n'est pas fourni ; et les deux
        // fenêtres de cartouche attendent leur contrôleur. Les nommer sans les
        // traiter serait une affirmation que rien ne vérifie.
        return {Region::unmapped, nullptr};
    }
}

/**
 * Rang de la banque commandée par une adresse, s'il y en a une.
 *
 * Les neuf commandes occupent dix octets : le registre du partage de la mémoire
 * commune s'est glissé au milieu, si bien que les deux dernières sont décalées
 * d'un cran.
 *
 * L'adresse de ce registre de partage ne doit pas parvenir ici : les appelants
 * le traitent avant, puisqu'ils doivent de toute façon en rendre la valeur. Une
 * garde de plus ici ne serait jamais exercée.
 */
bool MemoryMap::bank_control_index(std::uint32_t address, std::size_t& index) noexcept {
    if (address < vram_control_base || address > vram_control_base + 9U) return false;
    const auto offset = address - vram_control_base;
    index = offset < 7U ? offset : offset - 1U;
    return true;
}

std::uint8_t MemoryMap::read_io(std::uint32_t address) noexcept {
    if (address == shared_wram_control) return shared_wram_control_;
    std::size_t index = 0;
    if (bank_control_index(address, index)) return vram_control_[index];
    note_unimplemented_io(address);
    return 0;
}

void MemoryMap::write_io(std::uint32_t address, std::uint8_t value) noexcept {
    if (address == shared_wram_control) {
        shared_wram_control_ = value & 0x3U;
        return;
    }
    std::size_t index = 0;
    if (bank_control_index(address, index)) { vram_control_[index] = value; return; }
    note_unimplemented_io(address);
}

std::uint32_t MemoryMap::read(std::uint32_t address, std::uint32_t width) noexcept {
    std::uint32_t value = 0;
    for (std::uint32_t index = 0; index < width; ++index) {
        const auto byte_address = address + index;
        const auto location = locate(byte_address);
        std::uint32_t byte = 0;
        if (location.region == Region::input_output) {
            byte = read_io(byte_address);
        } else if (location.data != nullptr) {
            byte = *location.data;
        } else {
            note_unmapped(byte_address);
        }
        value |= byte << (index * 8U);
    }
    return value;
}

void MemoryMap::write(std::uint32_t address, std::uint32_t value, std::uint32_t width) noexcept {
    // La palette, les banques vidéo et la mémoire d'objets refusent l'écriture
    // d'un octet seul. Le matériel l'ignore ; le refus est donc silencieux, et
    // ce silence est le comportement juste.
    if (width == 1U && ignores_byte_writes(locate(address).region)) return;

    for (std::uint32_t index = 0; index < width; ++index) {
        const auto byte_address = address + index;
        const auto byte = static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU);
        const auto location = locate(byte_address);
        if (location.region == Region::input_output) {
            write_io(byte_address, byte);
        } else if (location.data != nullptr) {
            *location.data = byte;
        } else {
            note_unmapped(byte_address);
        }
    }
}

std::uint8_t MemoryMap::read8(std::uint32_t address) {
    return static_cast<std::uint8_t>(read(address, 1U));
}

std::uint16_t MemoryMap::read16(std::uint32_t address) {
    return static_cast<std::uint16_t>(read(address & ~1U, 2U));
}

std::uint32_t MemoryMap::read32(std::uint32_t address) {
    return read(address & ~3U, 4U);
}

void MemoryMap::write8(std::uint32_t address, std::uint8_t value) {
    write(address, value, 1U);
}

void MemoryMap::write16(std::uint32_t address, std::uint16_t value) {
    write(address & ~1U, value, 2U);
}

void MemoryMap::write32(std::uint32_t address, std::uint32_t value) {
    write(address & ~3U, value, 4U);
}

} // namespace ravenemu::nds
