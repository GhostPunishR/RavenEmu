#include "machine/machine.hpp"

#include "check.hpp"
#include "synthetic_roms.hpp"

#include <cstdint>
#include <memory>
#include <vector>

using ravenemu::testing::check;

namespace {

void frame_overshoot_does_not_accumulate() {
    // Cette ROM n'utilise ni DMA ni pause : seule une instruction peut dépasser
    // la frontière. La borne reste volontairement bien supérieure à son coût.
    constexpr int maximum_single_step_overshoot = 64;
    auto rom = std::make_shared<const std::vector<std::uint8_t>>(
        ravenemu::testing::minimal_gba_rom()
    );
    ravenemu::gba::Machine machine(
        rom, ravenemu::GbaSaveType::none, false, [] { return std::int64_t{0}; }
    );

    for (int frame = 0; frame < 120; ++frame) {
        machine.run_frame();
        const auto ppu_state = machine.ppu.state_fields();
        check(ppu_state[0] == 0, "la cadence GBA a dépassé la frontière de trame");
        check(
            ppu_state[1] >= 0 && ppu_state[1] < maximum_single_step_overshoot,
            "le dépassement de cycles GBA s'accumule entre les trames"
        );
    }
}

void long_dma_stops_at_frame_boundary() {
    auto rom = std::make_shared<const std::vector<std::uint8_t>>(
        ravenemu::testing::minimal_gba_rom()
    );
    ravenemu::gba::Machine machine(
        rom, ravenemu::GbaSaveType::none, false, [] { return std::int64_t{0}; }
    );

    const auto cycles_to_frame = machine.ppu.cycles_until_next_frame();
    const auto pc_before = machine.cpu.state.regs[15];
    machine.bus.write32(ravenemu::gba::i32(0x040000d4U), ravenemu::gba::i32(0x02000000U));
    machine.bus.write32(ravenemu::gba::i32(0x040000d8U), ravenemu::gba::i32(0x02020000U));
    machine.bus.write16(ravenemu::gba::i32(0x040000dcU), 32768);
    machine.bus.write16(ravenemu::gba::i32(0x040000deU), 0x8400); // canal 3, immédiat, 32 bits

    const auto pending_before = machine.dma.pending_cycles;
    check(pending_before > cycles_to_frame, "le DMA de test doit dépasser une trame");

    machine.run_frame();

    const auto ppu_state = machine.ppu.state_fields();
    check(ppu_state[0] == 0 && ppu_state[1] == 0, "le DMA a franchi la frontière de trame");
    check(
        machine.dma.pending_cycles == pending_before - cycles_to_frame,
        "les cycles DMA au-delà de la trame n'ont pas été conservés"
    );
    check(machine.cpu.state.regs[15] == pc_before, "le CPU a repris le bus avant la fin du DMA");
}

} // namespace

int main() {
    frame_overshoot_does_not_accumulate();
    long_dma_stops_at_frame_boundary();
    return 0;
}
