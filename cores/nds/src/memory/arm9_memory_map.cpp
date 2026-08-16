#include "memory/arm9_memory_map.hpp"

#include <algorithm>
#include <numeric>

#include "system/registers.hpp"

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

constexpr std::array<BankLayout, Arm9MemoryMap::vram_bank_count> bank_layouts{{
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
constexpr std::array<std::uint32_t, Arm9MemoryMap::vram_bank_count> bank_offsets = []() {
    std::array<std::uint32_t, Arm9MemoryMap::vram_bank_count> offsets{};
    std::uint32_t cursor = 0;
    for (std::size_t index = 0; index < bank_layouts.size(); ++index) {
        offsets[index] = cursor;
        cursor += bank_layouts[index].size;
    }
    return offsets;
}();

} // namespace

Arm9MemoryMap::Arm9MemoryMap(
    SystemMemory& system,
    InterProcessor& link,
    InterruptController& interrupts
)
    : system_(system), link_(link), interrupts_(interrupts),
      palette_(palette_bytes, 0),
      oam_(oam_bytes, 0),
      vram_(vram_total_bytes, 0) {}

void Arm9MemoryMap::reset() noexcept {
    // La mémoire partagée est remise à zéro par son propriétaire, non par
    // chacune des deux vues : la vider deux fois n'aurait pas de sens, et la
    // vider depuis l'une effacerait le travail de l'autre.
    std::fill(palette_.begin(), palette_.end(), std::uint8_t{0});
    std::fill(oam_.begin(), oam_.end(), std::uint8_t{0});
    std::fill(vram_.begin(), vram_.end(), std::uint8_t{0});
    std::fill(vram_control_.begin(), vram_control_.end(), std::uint8_t{0});
    unmapped_ = 0;
    first_unmapped_ = 0;
    unimplemented_io_ = 0;
    first_unimplemented_io_ = 0;
}

std::span<std::uint8_t> Arm9MemoryMap::vram_bank(std::size_t index) noexcept {
    if (index >= vram_bank_count) return {};
    return std::span<std::uint8_t>{vram_}.subspan(bank_offsets[index], bank_layouts[index].size);
}

void Arm9MemoryMap::note_unmapped(std::uint32_t address) noexcept {
    if (unmapped_ == 0U) first_unmapped_ = address;
    ++unmapped_;
}

void Arm9MemoryMap::note_unimplemented_io(std::uint32_t address) noexcept {
    if (unimplemented_io_ == 0U) first_unimplemented_io_ = address;
    ++unimplemented_io_;
}

Arm9MemoryMap::Location Arm9MemoryMap::locate_video(std::uint32_t address) noexcept {
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

Arm9MemoryMap::Location Arm9MemoryMap::locate(std::uint32_t address) noexcept {
    switch (address >> 24U) {
    case 0x02:
        return {Region::main_ram, &system_.main_ram()[address % main_ram_bytes]};
    case 0x03: {
        const auto window = shared_window();
        if (window.size == 0U) return {Region::shared_wram, nullptr};
        return {Region::shared_wram, &system_.shared_wram()[window.offset + (address % window.size)]};
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
bool Arm9MemoryMap::bank_control_index(std::uint32_t address, std::size_t& index) noexcept {
    if (address < vram_control_base || address > vram_control_base + 9U) return false;
    const auto offset = address - vram_control_base;
    index = offset < 7U ? offset : offset - 1U;
    return true;
}


std::uint32_t Arm9MemoryMap::read_io(std::uint32_t address, std::uint32_t width) noexcept {
    // Les deux files et les deux registres de seize bits sont indivisibles :
    // les lire par morceaux les ferait avancer plusieurs fois, ou ne rendrait
    // qu'une moitié de leur état.
    if (address == registers::queue_receive && width == 4U) {
        return link_.receive(Processor::main);
    }
    if (address == registers::sync && width == 2U) {
        return link_.read_sync(Processor::main);
    }
    if (address == registers::queue_control && width == 2U) {
        return link_.read_control(Processor::main);
    }
    // Un envoi ne se relit pas : le registre est en écriture seule.
    if (address == registers::queue_send) {
        note_unimplemented_io(address);
        return 0;
    }

    std::uint32_t value = 0;
    for (std::uint32_t index = 0; index < width; ++index) {
        value |= static_cast<std::uint32_t>(read_io_byte(address + index)) << (index * 8U);
    }
    return value;
}

void Arm9MemoryMap::write_io(std::uint32_t address, std::uint32_t value, std::uint32_t width) noexcept {
    if (address == registers::queue_send && width == 4U) {
        link_.send(Processor::main, value);
        return;
    }
    if (address == registers::sync && width == 2U) {
        link_.write_sync(Processor::main, static_cast<std::uint16_t>(value));
        return;
    }
    if (address == registers::queue_control && width == 2U) {
        link_.write_control(Processor::main, static_cast<std::uint16_t>(value));
        return;
    }
    // Une file ne se lit pas en écrivant : le registre est en lecture seule.
    if (address == registers::queue_receive) {
        note_unimplemented_io(address);
        return;
    }

    for (std::uint32_t index = 0; index < width; ++index) {
        write_io_byte(address + index, static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU));
    }
}

std::uint8_t Arm9MemoryMap::read_io_byte(std::uint32_t address) noexcept {
    if (address == shared_wram_control) return system_.shared_control();
    std::size_t index = 0;
    if (bank_control_index(address, index)) return vram_control_[index];

    if (address == registers::interrupt_master) return registers::byte_of(interrupts_.master_enable(), 0U);
    if (address >= registers::interrupt_enable && address < registers::interrupt_enable + 4U) {
        return registers::byte_of(interrupts_.enabled(), address - registers::interrupt_enable);
    }
    if (address >= registers::interrupt_request && address < registers::interrupt_request + 4U) {
        return registers::byte_of(interrupts_.requested(), address - registers::interrupt_request);
    }
    note_unimplemented_io(address);
    return 0;
}

void Arm9MemoryMap::write_io_byte(std::uint32_t address, std::uint8_t value) noexcept {
    if (address == shared_wram_control) {
        // Le partage se commande depuis ce processeur seulement : c'est lui qui
        // décide de ce qu'il cède à l'autre.
        system_.set_shared_control(value);
        return;
    }
    std::size_t index = 0;
    if (bank_control_index(address, index)) { vram_control_[index] = value; return; }

    if (address == registers::interrupt_master) {
        interrupts_.set_master_enable(value);
        return;
    }
    if (address >= registers::interrupt_enable && address < registers::interrupt_enable + 4U) {
        interrupts_.set_enabled(
            registers::with_byte(interrupts_.enabled(), address - registers::interrupt_enable, value)
        );
        return;
    }
    if (address >= registers::interrupt_request && address < registers::interrupt_request + 4U) {
        // Registre des demandes : écrire un bit à un l'efface. Un gestionnaire
        // écrit ici pour dire qu'il a traité, non pour lever une demande.
        interrupts_.acknowledge(
            static_cast<std::uint32_t>(value) << ((address - registers::interrupt_request) * 8U)
        );
        return;
    }
    note_unimplemented_io(address);
}

std::uint32_t Arm9MemoryMap::read(std::uint32_t address, std::uint32_t width) noexcept {
    // Une seule région décide, celle du premier octet : un accès à cheval entre
    // deux régions n'a pas de sens matériel.
    if (locate(address).region == Region::input_output) return read_io(address, width);

    std::uint32_t value = 0;
    for (std::uint32_t index = 0; index < width; ++index) {
        const auto byte_address = address + index;
        const auto location = locate(byte_address);
        std::uint32_t byte = 0;
        if (location.data != nullptr) {
            byte = *location.data;
        } else {
            note_unmapped(byte_address);
        }
        value |= byte << (index * 8U);
    }
    return value;
}

void Arm9MemoryMap::write(std::uint32_t address, std::uint32_t value, std::uint32_t width) noexcept {
    // La palette, les banques vidéo et la mémoire d'objets refusent l'écriture
    // d'un octet seul. Le matériel l'ignore ; le refus est donc silencieux, et
    // ce silence est le comportement juste.
    if (locate(address).region == Region::input_output) {
        write_io(address, value, width);
        return;
    }
    if (width == 1U && ignores_byte_writes(locate(address).region)) return;

    for (std::uint32_t index = 0; index < width; ++index) {
        const auto byte_address = address + index;
        const auto byte = static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU);
        const auto location = locate(byte_address);
        if (location.data != nullptr) {
            *location.data = byte;
        } else {
            note_unmapped(byte_address);
        }
    }
}

std::uint8_t Arm9MemoryMap::read8(std::uint32_t address) {
    return static_cast<std::uint8_t>(read(address, 1U));
}

std::uint16_t Arm9MemoryMap::read16(std::uint32_t address) {
    return static_cast<std::uint16_t>(read(address & ~1U, 2U));
}

std::uint32_t Arm9MemoryMap::read32(std::uint32_t address) {
    return read(address & ~3U, 4U);
}

void Arm9MemoryMap::write8(std::uint32_t address, std::uint8_t value) {
    write(address, value, 1U);
}

void Arm9MemoryMap::write16(std::uint32_t address, std::uint16_t value) {
    write(address & ~1U, value, 2U);
}

void Arm9MemoryMap::write32(std::uint32_t address, std::uint32_t value) {
    write(address & ~3U, value, 4U);
}

} // namespace ravenemu::nds
