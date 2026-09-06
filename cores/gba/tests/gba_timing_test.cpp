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

} // namespace

int main() {
    frame_overshoot_does_not_accumulate();
    return 0;
}
