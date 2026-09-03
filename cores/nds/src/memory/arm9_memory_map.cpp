#include "memory/arm9_memory_map.hpp"

#include <algorithm>
#include <numeric>

#include "system/registers.hpp"

namespace ravenemu::nds {

namespace {

/** Rangs des registres portés par un moteur, dans son bloc. */
constexpr std::uint32_t display_control_offset = 0x00;
constexpr std::uint32_t background_control_offset = 0x08;
constexpr std::uint32_t scroll_offset = 0x10;

} // namespace

Arm9MemoryMap::Arm9MemoryMap(
    SystemMemory& system,
    VideoSystem& video,
    InterProcessor& link,
    InterruptController& interrupts,
    InputState& input,
    Cartridge& cartridge
): system_(system), video_(video), link_(link), interrupts_(interrupts), input_(input), cartridge_(cartridge) {}

void Arm9MemoryMap::reset() noexcept {
    // La mémoire partagée et le matériel vidéo sont remis à zéro par leurs
    // propriétaires, non par cette vue : les vider ici effacerait le travail de
    // l'autre processeur.
    key_interrupt_.reset();
    dma_.reset();
    timers_.reset();
    power_ = 0;
    // L’amorçage est passé dès la remise à zéro : ce cœur rend la main au jeu
    // au moment où le programme de la console le ferait, drapeau posé.
    post_boot_ = post_boot_done;
    external_memory_ = 0;
    unmapped_ = 0;
    first_unmapped_ = 0;
    unimplemented_io_ = 0;
    first_unimplemented_io_ = 0;
}

std::span<std::uint8_t> Arm9MemoryMap::vram_bank(std::size_t index) noexcept {
    return video_.memory().bank(index);
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
    // Les fenêtres des moteurs se lisent par les moteurs, non par le bus : le
    // processeur n'y écrit pas des pixels, il écrit dans une banque, et c'est
    // l'aiguillage qui décide où cette banque se montre.
    return {Region::video, video_.memory().transfer(address)};
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
        return {Region::palette, &video_.palette()[address % palette_bytes]};
    case 0x06:
        return locate_video(address);
    case 0x07:
        return {Region::object_attributes, &video_.object_attributes()[address % oam_bytes]};
    default:
        if (address >= bios_base) {
            return {Region::boot_program, &bios_[(address - bios_base) % bios_bytes]};
        }
        // Le reste n'est pas décodé, et pour deux raisons différentes que rien
        // ne distingue encore : les adresses basses appartiennent à la mémoire
        // locale du cœur, que le processeur consulte avant le bus, et les deux
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

Engine2d* Arm9MemoryMap::engine_register(std::uint32_t address, std::uint32_t& offset) noexcept {
    Engine2d* engine = nullptr;
    if (address >= main_engine_base && address < main_engine_base + engine_register_bytes) {
        engine = &video_.engine(Engine::main);
        offset = address - main_engine_base;
    } else if (
        address >= secondary_engine_base &&
        address < secondary_engine_base + engine_register_bytes
    ) {
        engine = &video_.engine(Engine::secondary);
        offset = address - secondary_engine_base;
    } else {
        return nullptr;
    }

    // Entre la commande d'affichage et les commandes de plans, quatre octets
    // appartiennent à l'écran, pas au moteur : ils ne se dédoublent pas.
    if (offset >= 4U && offset < background_control_offset) return nullptr;
    return engine;
}

std::uint8_t Arm9MemoryMap::read_engine_byte(Engine2d& engine, std::uint32_t offset) noexcept {
    if (offset < 4U) {
        return registers::byte_of(engine.display_control(), offset - display_control_offset);
    }
    if (offset < scroll_offset) {
        const auto index = (offset - background_control_offset) / 2U;
        return registers::byte_of(engine.background_control(index), offset & 1U);
    }
    // Le défilement ne se relit pas : le matériel n'en garde pas de quoi
    // répondre, et rendre la dernière valeur écrite serait une invention.
    note_unimplemented_io(offset);
    return 0;
}

void Arm9MemoryMap::write_engine_byte(
    Engine2d& engine,
    std::uint32_t offset,
    std::uint8_t value
) noexcept {
    if (offset < 4U) {
        engine.set_display_control(
            registers::with_byte(engine.display_control(), offset - display_control_offset, value)
        );
        return;
    }
    if (offset < scroll_offset) {
        const auto index = (offset - background_control_offset) / 2U;
        engine.set_background_control(
            index,
            static_cast<std::uint16_t>(
                registers::with_byte(engine.background_control(index), offset & 1U, value)
            )
        );
        return;
    }

    const auto index = (offset - scroll_offset) / 4U;
    const auto within = offset & 0x3U;
    // Quatre octets par plan : le défilement horizontal puis le vertical.
    if (within < 2U) {
        engine.set_scroll_x(
            index,
            static_cast<std::uint16_t>(registers::with_byte(engine.scroll_x(index), within, value))
        );
        return;
    }
    engine.set_scroll_y(
        index,
        static_cast<std::uint16_t>(
            registers::with_byte(engine.scroll_y(index), within - 2U, value)
        )
    );
}


std::uint32_t Arm9MemoryMap::read_io(std::uint32_t address, std::uint32_t width) noexcept {
    // Le bus de cartouche décode lui-même ses registres : les deux processeurs
    // les voient aux mêmes adresses, et deux copies de ce décodage dériveraient.
    std::uint32_t from_cartridge = 0;
    if (cartridge_.read_register(Processor::main, address, width, from_cartridge)) {
        return from_cartridge;
    }
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
    if (cartridge_.write_register(Processor::main, address, width, value)) return;
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
    if (bank_control_index(address, index)) return video_.memory().control(index);

    std::uint32_t offset = 0;
    if (auto* engine = engine_register(address, offset); engine != nullptr) {
        return read_engine_byte(*engine, offset);
    }

    if (address >= display_status && address < display_status + 2U) {
        return registers::byte_of(display().status(Processor::main), address - display_status);
    }
    if (address >= line_counter && address < line_counter + 2U) {
        return registers::byte_of(display().line(), address - line_counter);
    }
    if (address >= registers::external_memory_control &&
        address < registers::external_memory_control + 2U) {
        return registers::byte_of(external_memory_, address - registers::external_memory_control);
    }
    if (address >= power_control && address < power_control + 2U) {
        return registers::byte_of(power_, address - power_control);
    }

    if (address >= key_input && address < key_input + 2U) {
        // Actif à zéro : un bit effacé veut dire touche enfoncée.
        return registers::byte_of(input_.key_register(), address - key_input);
    }
    if (address >= key_control && address < key_control + 2U) {
        return registers::byte_of(key_interrupt_.control(), address - key_control);
    }

    if (address >= dma_base && address < dma_base + DmaController::channel_bytes * DmaController::count) {
        const auto offset = address - dma_base;
        const auto channel = offset / DmaController::channel_bytes;
        const auto part = offset % DmaController::channel_bytes;
        if (part < 4U) return registers::byte_of(dma_.source(channel), part);
        if (part < 8U) return registers::byte_of(dma_.destination(channel), part - 4U);
        return registers::byte_of(dma_.control(channel), part - 8U);
    }

    if (address >= timer_base && address < timer_base + timer_stride * Timers::count) {
        const auto slot = (address - timer_base) / timer_stride;
        const auto part = (address - timer_base) % timer_stride;
        // Le registre bas rend le compteur, non le rechargement : montrer le
        // rechargement donnerait à un jeu un temps immobile.
        if (part < 2U) return registers::byte_of(timers_.counter(slot), part);
        return registers::byte_of(timers_.control(slot), part - 2U);
    }

    if (address >= post_boot_flag && address < post_boot_flag + post_boot_bytes) {
        // Seul le premier octet porte le drapeau ; la garniture se lit nulle,
        // comme sur le matériel.
        return address == post_boot_flag ? post_boot_ : std::uint8_t{0};
    }

    if (
        address >= registers::interrupt_master &&
        address < registers::interrupt_master + registers::interrupt_master_bytes
    ) {
        // Le registre occupe quatre octets, dont un seul porte quelque chose :
        // les trois autres sont de la garniture, et le matériel les rend nuls.
        // Ne servir que le premier faisait compter les trois autres comme des
        // registres inconnus à chaque écriture, et cette avalanche cachait la
        // première adresse vraiment inconnue, seule utile au diagnostic.
        return registers::byte_of(interrupts_.master_enable(), address - registers::interrupt_master);
    }
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
    if (bank_control_index(address, index)) { video_.memory().set_control(index, value); return; }

    std::uint32_t offset = 0;
    if (auto* engine = engine_register(address, offset); engine != nullptr) {
        write_engine_byte(*engine, offset, value);
        return;
    }

    if (address >= display_status && address < display_status + 2U) {
        // Les trois indicateurs du balayage sont en lecture seule : le tampon
        // les rend, et l'écriture les écarte.
        display().set_status(
            Processor::main,
            static_cast<std::uint16_t>(registers::with_byte(
                display().status(Processor::main), address - display_status, value))
        );
        return;
    }
    if (address >= registers::external_memory_control &&
        address < registers::external_memory_control + 2U) {
        external_memory_ = static_cast<std::uint16_t>(registers::with_byte(
            external_memory_, address - registers::external_memory_control, value));
        // Un seul bit agit : celui qui confie le port cartouche à l'un ou à
        // l'autre. Les autres décrivent des temps d'attente de bus, que ce cœur
        // ne compte pas ; ils sont donc conservés et relus, sans effet.
        cartridge_.set_owner(
            (external_memory_ & registers::cartridge_to_secondary) != 0U
                ? Processor::secondary
                : Processor::main
        );
        return;
    }
    if (address >= power_control && address < power_control + 2U) {
        power_ = static_cast<std::uint16_t>(
            registers::with_byte(power_, address - power_control, value));
        // Un seul bit agit : celui qui échange les deux écrans. Les autres
        // coupent l'alimentation d'organes qui n'existent pas encore, et se
        // relisent tels qu'écrits.
        display().set_swapped((power_ & power_swaps_screens) != 0U);
        return;
    }

    if (address >= key_input && address < key_input + 2U) {
        // Les touches ne s'écrivent pas : le matériel ignore, et le silence est
        // ici le comportement juste plutôt qu'un manque.
        static_cast<void>(value);
        return;
    }
    if (address >= key_control && address < key_control + 2U) {
        key_interrupt_.set_control(static_cast<std::uint16_t>(
            registers::with_byte(key_interrupt_.control(), address - key_control, value)));
        return;
    }

    if (address >= dma_base && address < dma_base + DmaController::channel_bytes * DmaController::count) {
        const auto offset = address - dma_base;
        const auto channel = offset / DmaController::channel_bytes;
        const auto part = offset % DmaController::channel_bytes;
        if (part < 4U) {
            dma_.set_source(channel, registers::with_byte(dma_.source(channel), part, value));
            return;
        }
        if (part < 8U) {
            dma_.set_destination(
                channel, registers::with_byte(dma_.destination(channel), part - 4U, value));
            return;
        }
        dma_.set_control(channel, registers::with_byte(dma_.control(channel), part - 8U, value));
        return;
    }

    if (address >= timer_base && address < timer_base + timer_stride * Timers::count) {
        const auto slot = (address - timer_base) / timer_stride;
        const auto part = (address - timer_base) % timer_stride;
        // Le registre bas écrit le rechargement, non le compteur : écrire le
        // compteur laisserait un jeu replacer le temps où il veut.
        if (part < 2U) {
            timers_.set_reload(slot, static_cast<std::uint16_t>(
                registers::with_byte(timers_.reload(slot), part, value)));
            return;
        }
        timers_.set_control(slot, static_cast<std::uint16_t>(
            registers::with_byte(timers_.control(slot), part - 2U, value)));
        return;
    }

    if (address >= post_boot_flag && address < post_boot_flag + post_boot_bytes) {
        if (address != post_boot_flag) return;
        // Le bit d’amorçage ne se retire pas : une écriture ne peut que poser
        // des bits. C’est ce qui permet à un jeu de s’en servir comme d’une
        // marque de passage plutôt que d’un simple registre.
        post_boot_ = static_cast<std::uint8_t>(post_boot_ | value);
        return;
    }

    if (
        address >= registers::interrupt_master &&
        address < registers::interrupt_master + registers::interrupt_master_bytes
    ) {
        // Seul le premier octet décide ; les trois autres sont acceptés et
        // ignorés, comme le fait le matériel.
        if (address == registers::interrupt_master) interrupts_.set_master_enable(value);
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
        if (is_read_only(location.region)) continue;
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
