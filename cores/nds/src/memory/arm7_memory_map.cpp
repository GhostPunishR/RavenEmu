#include "memory/arm7_memory_map.hpp"

#include <algorithm>

namespace ravenemu::nds {

Arm7MemoryMap::Arm7MemoryMap(SystemMemory& system)
    : system_(system), private_wram_(private_wram_bytes, 0) {}

void Arm7MemoryMap::reset() noexcept {
    // La mémoire partagée est remise à zéro par son propriétaire : la vider
    // depuis l'une des deux vues effacerait le travail de l'autre.
    std::fill(private_wram_.begin(), private_wram_.end(), std::uint8_t{0});
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
        // Le programme d'amorçage de ce processeur, la cartouche, le port Game
        // Boy Advance et les banques vidéo qui peuvent lui être confiées. Aucun
        // de ces contenus n'existe encore.
        return {Region::unmapped, nullptr};
    }
}

std::uint8_t Arm7MemoryMap::read_io(std::uint32_t address) noexcept {
    if (address == shared_wram_status) return system_.shared_control();
    note_unimplemented_io(address);
    return 0;
}

void Arm7MemoryMap::write_io(std::uint32_t address, std::uint8_t value) noexcept {
    if (address == shared_wram_status) {
        // Vue en lecture seule : ce processeur constate le partage, il ne le
        // décide pas. L'écriture est ignorée par le matériel, et ce silence est
        // le comportement juste.
        static_cast<void>(value);
        return;
    }
    note_unimplemented_io(address);
}

std::uint32_t Arm7MemoryMap::read(std::uint32_t address, std::uint32_t width) noexcept {
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

void Arm7MemoryMap::write(std::uint32_t address, std::uint32_t value, std::uint32_t width) noexcept {
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
