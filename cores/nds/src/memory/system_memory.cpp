#include "memory/system_memory.hpp"

#include <algorithm>

namespace ravenemu::nds {

SystemMemory::SystemMemory()
    : main_ram_(main_ram_bytes, 0), shared_wram_(shared_wram_bytes, 0) {}

void SystemMemory::reset() noexcept {
    std::fill(main_ram_.begin(), main_ram_.end(), std::uint8_t{0});
    std::fill(shared_wram_.begin(), shared_wram_.end(), std::uint8_t{0});
    // Au démarrage, le processeur principal reçoit toute la mémoire commune.
    shared_control_ = 0;
}

SystemMemory::Window SystemMemory::main_processor_window() const noexcept {
    switch (shared_control_) {
    case 0: return {0U, shared_wram_bytes};
    case 1: return {shared_wram_bytes / 2U, shared_wram_bytes / 2U};
    case 2: return {0U, shared_wram_bytes / 2U};
    default: return {0U, 0U};
    }
}

SystemMemory::Window SystemMemory::secondary_processor_window() const noexcept {
    // Le complément exact du découpage précédent : ce que l'un reçoit, l'autre
    // ne l'a pas. Écrire les deux depuis le même registre interdit un état où
    // les deux processeurs se croiraient propriétaires du même octet.
    switch (shared_control_) {
    case 0: return {0U, 0U};
    case 1: return {0U, shared_wram_bytes / 2U};
    case 2: return {shared_wram_bytes / 2U, shared_wram_bytes / 2U};
    default: return {0U, shared_wram_bytes};
    }
}

} // namespace ravenemu::nds
