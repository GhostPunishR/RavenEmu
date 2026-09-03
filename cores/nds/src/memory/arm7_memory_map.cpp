#include "memory/arm7_memory_map.hpp"

#include <algorithm>

#include "system/registers.hpp"

namespace ravenemu::nds {


Arm7MemoryMap::Arm7MemoryMap(
    SystemMemory& system,
    VideoSystem& video,
    InterProcessor& link,
    InterruptController& interrupts,
    InputState& input,
    Cartridge& cartridge,
    SerialPort& serial
): system_(system), video_(video), link_(link), interrupts_(interrupts),
      input_(input), cartridge_(cartridge), serial_(serial),
      private_wram_(private_wram_bytes, 0) {}

void Arm7MemoryMap::reset() noexcept {
    // La mémoire partagée est remise à zéro par son propriétaire : la vider
    // depuis l'une des deux vues effacerait le travail de l'autre.
    std::fill(private_wram_.begin(), private_wram_.end(), std::uint8_t{0});
    key_interrupt_.reset();
    dma_.reset();
    timers_.reset();
    halt_requested_ = false;
    // L’amorçage est passé dès la remise à zéro : ce cœur rend la main au jeu
    // au moment où le programme de la console le ferait, drapeau posé.
    post_boot_ = post_boot_done;
    unmapped_ = 0;
    first_unmapped_ = 0;
    unimplemented_io_ = 0;
    first_unimplemented_io_ = 0;
}

void Arm7MemoryMap::note_unmapped(std::uint32_t address) noexcept {
    if (unmapped_ == 0U) first_unmapped_ = address;
    ++unmapped_;
}

void Arm7MemoryMap::note_unimplemented_io(std::uint32_t address) noexcept {
    if (unimplemented_io_ == 0U) first_unimplemented_io_ = address;
    ++unimplemented_io_;
}

Arm7MemoryMap::Location Arm7MemoryMap::locate(std::uint32_t address) noexcept {
    switch (address >> 24U) {
    case 0x00:
        // Le programme d'amorçage de ce processeur, et sa table des vecteurs.
        return {Region::boot_program, &bios_[address % bios_bytes]};
    case 0x02:
        return {Region::main_ram, &system_.main_ram()[address % SystemMemory::main_ram_bytes]};
    case 0x03: {
        if (address >= private_wram_base) {
            return {Region::private_wram, &private_wram_[address % private_wram_bytes]};
        }
        const auto window = shared_window();
        // Sans part de la mémoire commune, cette fenêtre ne devient pas muette :
        // elle donne sur la mémoire propre. Un programme qui s'y adresse
        // continue de fonctionner après que l'autre processeur lui a tout pris.
        if (window.size == 0U) {
            return {Region::private_wram, &private_wram_[address % private_wram_bytes]};
        }
        return {
            Region::shared_wram,
            &system_.shared_wram()[window.offset + (address % window.size)],
        };
    }
    case 0x04:
        return {Region::input_output, nullptr};
    default:
        // Le port Game Boy Advance et les banques vidéo qui peuvent lui être
        // confiées. Aucun de ces contenus n'existe encore.
        return {Region::unmapped, nullptr};
    }
}


std::uint32_t Arm7MemoryMap::read_io(std::uint32_t address, std::uint32_t width) noexcept {
    // Le bus de cartouche décode lui-même ses registres : les deux processeurs
    // les voient aux mêmes adresses, et deux copies de ce décodage dériveraient.
    std::uint32_t from_cartridge = 0;
    if (cartridge_.read_register(Processor::secondary, address, width, from_cartridge)) {
        return from_cartridge;
    }
    // Le port série fait de même, et pour une autre raison : ses registres sont
    // les siens de bout en bout, et le protocole qui les anime n'a rien à faire
    // dans une carte mémoire.
    std::uint32_t from_serial = 0;
    if (serial_.read_register(address, width, from_serial)) return from_serial;
    // Les deux files et les deux registres de seize bits sont indivisibles :
    // les lire par morceaux les ferait avancer plusieurs fois, ou ne rendrait
    // qu'une moitié de leur état.
    if (address == registers::queue_receive && width == 4U) {
        return link_.receive(Processor::secondary);
    }
    if (address == registers::sync && width == 2U) {
        return link_.read_sync(Processor::secondary);
    }
    if (address == registers::queue_control && width == 2U) {
        return link_.read_control(Processor::secondary);
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

void Arm7MemoryMap::write_io(std::uint32_t address, std::uint32_t value, std::uint32_t width) noexcept {
    if (cartridge_.write_register(Processor::secondary, address, width, value)) return;
    if (serial_.write_register(address, width, value)) return;
    if (address == registers::queue_send && width == 4U) {
        link_.send(Processor::secondary, value);
        return;
    }
    if (address == registers::sync && width == 2U) {
        link_.write_sync(Processor::secondary, static_cast<std::uint16_t>(value));
        return;
    }
    if (address == registers::queue_control && width == 2U) {
        link_.write_control(Processor::secondary, static_cast<std::uint16_t>(value));
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

std::uint8_t Arm7MemoryMap::read_io_byte(std::uint32_t address) noexcept {
    if (address == shared_wram_status) return system_.shared_control();

    if (address >= display_status && address < display_status + 2U) {
        return registers::byte_of(
            video_.display().status(Processor::secondary), address - display_status);
    }
    if (address >= line_counter && address < line_counter + 2U) {
        // Le compteur est le même des deux côtés : c'est un seul faisceau.
        return registers::byte_of(video_.display().line(), address - line_counter);
    }

    if (address >= key_input && address < key_input + 2U) {
        // Actif à zéro : un bit effacé veut dire touche enfoncée.
        return registers::byte_of(input_.key_register(), address - key_input);
    }
    if (address >= key_control && address < key_control + 2U) {
        return registers::byte_of(key_interrupt_.control(), address - key_control);
    }
    if (address >= extra_key_input && address < extra_key_input + 2U) {
        return registers::byte_of(input_.extra_register(), address - extra_key_input);
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

    if (address == post_boot_flag) return post_boot_;

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

void Arm7MemoryMap::write_io_byte(std::uint32_t address, std::uint8_t value) noexcept {
    if (address >= display_status && address < display_status + 2U) {
        // Ce processeur règle ses propres réveils sur le balayage, sans toucher
        // à ceux de l'autre.
        video_.display().set_status(
            Processor::secondary,
            static_cast<std::uint16_t>(registers::with_byte(
                video_.display().status(Processor::secondary), address - display_status, value))
        );
        return;
    }
    if (address >= line_counter && address < line_counter + 2U) {
        note_unimplemented_io(address);
        return;
    }

    if (address == halt_control) {
        // Deux bits de poids fort décident. Une écriture qui n'en pose aucun ne
        // fait rien : c'est une valeur légitime, pas un registre méconnu.
        const auto mode = static_cast<std::uint8_t>(value >> 6U);
        if (mode == halt_mode) {
            halt_requested_ = true;
            return;
        }
        if (mode == 0U) return;
        note_unimplemented_io(address);
        return;
    }

    if (address == shared_wram_status) {
        // Vue en lecture seule : ce processeur constate le partage, il ne le
        // décide pas. L'écriture est ignorée par le matériel, et ce silence est
        // le comportement juste.
        static_cast<void>(value);
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
    if (address >= extra_key_input && address < extra_key_input + 2U) {
        static_cast<void>(value);
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

    if (address == post_boot_flag) {
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

std::uint32_t Arm7MemoryMap::read(std::uint32_t address, std::uint32_t width) noexcept {
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

void Arm7MemoryMap::write(std::uint32_t address, std::uint32_t value, std::uint32_t width) noexcept {
    if (locate(address).region == Region::input_output) {
        write_io(address, value, width);
        return;
    }

    for (std::uint32_t index = 0; index < width; ++index) {
        const auto byte_address = address + index;
        const auto byte = static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU);
        const auto location = locate(byte_address);
        // Le programme d'amorçage ne s'écrit pas : le matériel ignore, et le
        // compter comme une adresse non décodée serait faux, l'adresse étant
        // bel et bien décodée.
        if (location.region == Region::boot_program) continue;
        if (location.data != nullptr) {
            *location.data = byte;
        } else {
            note_unmapped(byte_address);
        }
    }
}

std::uint8_t Arm7MemoryMap::read8(std::uint32_t address) {
    return static_cast<std::uint8_t>(read(address, 1U));
}

std::uint16_t Arm7MemoryMap::read16(std::uint32_t address) {
    return static_cast<std::uint16_t>(read(address & ~1U, 2U));
}

std::uint32_t Arm7MemoryMap::read32(std::uint32_t address) {
    return read(address & ~3U, 4U);
}

void Arm7MemoryMap::write8(std::uint32_t address, std::uint8_t value) {
    write(address, value, 1U);
}

void Arm7MemoryMap::write16(std::uint32_t address, std::uint16_t value) {
    write(address & ~1U, value, 2U);
}

void Arm7MemoryMap::write32(std::uint32_t address, std::uint32_t value) {
    write(address & ~3U, value, 4U);
}

} // namespace ravenemu::nds
