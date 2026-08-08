#include "ravenemu/core.hpp"
#include "sha256.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

void check(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string{message});
}

template <typename Exception, typename Function>
void expect_failure(Function&& function, std::string_view message) {
    try {
        std::forward<Function>(function)();
    } catch (const Exception&) {
        return;
    }
    throw std::runtime_error(std::string{message});
}

void sha256_test() {
    constexpr std::array<std::uint8_t, 32> expected{
        0xe3, 0xb0, 0xc4, 0x42, 0x98, 0xfc, 0x1c, 0x14,
        0x9a, 0xfb, 0xf4, 0xc8, 0x99, 0x6f, 0xb9, 0x24,
        0x27, 0xae, 0x41, 0xe4, 0x64, 0x9b, 0x93, 0x4c,
        0xa4, 0x95, 0x99, 0x1b, 0x78, 0x52, 0xb8, 0x55,
    };
    check(ravenemu::detail::sha256({}) == expected, "SHA-256 natif incorrect");
}

std::vector<std::uint8_t> minimal_game_boy_rom() {
    std::vector<std::uint8_t> rom(0x8000);
    rom[0x0147] = 0x00; // no MBC
    // Endless JP 0100 loop, enough to exercise a complete deterministic frame.
    rom[0x0100] = 0xc3;
    rom[0x0101] = 0x00;
    rom[0x0102] = 0x01;
    return rom;
}

std::vector<std::uint8_t> minimal_gba_rom() {
    std::vector<std::uint8_t> rom(0x200);
    // LDR r0, [pc, #8] ; MOV r1, #0x5a ; STRB r1, [r0] ; B .
    const std::array<std::uint32_t, 4> program{
        0xe59f0008U,
        0xe3a0105aU,
        0xe5c01000U,
        0xeafffffeU,
    };
    for (std::size_t index = 0; index < program.size(); ++index) {
        const auto word = program[index];
        for (unsigned byte = 0; byte < 4; ++byte) {
            rom[index * 4U + byte] = static_cast<std::uint8_t>(word >> (byte * 8U));
        }
    }
    // Literal loaded by the first instruction: SRAM base.
    rom[16] = 0x00;
    rom[17] = 0x00;
    rom[18] = 0x00;
    rom[19] = 0x0e;
    rom[0xb2] = 0x96;
    return rom;
}

void game_boy_smoke_test() {
    auto core = ravenemu::make_game_boy_core();
    const auto rom = minimal_game_boy_rom();
    core->load_rom(rom, {});
    check(core->console() == ravenemu::Console::game_boy, "console GB incorrecte");
    check(
        core->framebuffer_format() == ravenemu::FramebufferFormat::indexed_4,
        "format vidéo DMG incorrect"
    );
    std::vector<std::int32_t> frame(core->video_spec().pixel_count());
    core->run_frame(frame, true);
    const auto state = core->save_state();
    check(!state.empty(), "état GB vide");
    core->run_frame(frame, true);
    core->load_state(state);
    core->run_frame(frame, true);

    const auto before_failure = core->save_state();
    auto truncated = before_failure;
    truncated.pop_back();
    expect_failure<ravenemu::SaveStateError>(
        [&] { core->load_state(truncated); },
        "un état GB tronqué a été accepté"
    );
    check(
        core->save_state() == before_failure,
        "la restauration GB en échec a modifié la machine"
    );

    auto other_rom = rom;
    other_rom[0x0134] = 1;
    auto other_core = ravenemu::make_game_boy_core();
    other_core->load_rom(other_rom, {});
    expect_failure<ravenemu::SaveStateError>(
        [&] { other_core->load_state(before_failure); },
        "un état GB d'une autre ROM a été accepté"
    );

    std::vector<std::int32_t> short_frame(core->video_spec().pixel_count() - 1U);
    expect_failure<std::invalid_argument>(
        [&] { core->run_frame(short_frame, true); },
        "un framebuffer GB trop petit a été accepté"
    );
}

void game_boy_color_and_battery_test() {
    auto cgb_rom = minimal_game_boy_rom();
    cgb_rom[0x0143] = 0x80;
    auto cgb = ravenemu::make_game_boy_core();
    cgb->load_rom(cgb_rom, {});
    check(
        cgb->framebuffer_format() == ravenemu::FramebufferFormat::argb_8888,
        "le mode couleur CGB n'a pas été activé"
    );

    auto battery_rom = minimal_game_boy_rom();
    battery_rom[0x0147] = 0x03; // MBC1 + RAM + pile
    battery_rom[0x0149] = 0x02; // 8 Kio
    std::vector<std::uint8_t> battery(8U * 1024U, 0x5a);
    auto battery_core = ravenemu::make_game_boy_core();
    battery_core->load_rom(battery_rom, battery);
    check(battery_core->has_battery_ram(), "RAM à pile GB non détectée");
    check(!battery_core->battery_ram_dirty(), "RAM GB importée marquée sale");
    const auto snapshot = battery_core->snapshot_battery_ram();
    check(snapshot.has_value(), "instantané batterie GB absent");
    check(snapshot->data == battery, "contenu batterie GB altéré à l'import");
}

void invalid_rom_test() {
    auto gb = ravenemu::make_game_boy_core();
    expect_failure<ravenemu::RomLoadError>(
        [&] { gb->load_rom(std::vector<std::uint8_t>(16), {}); },
        "une ROM GB trop courte a été acceptée"
    );

    auto gba_rom = minimal_gba_rom();
    gba_rom[0xb2] = 0;
    auto gba = ravenemu::make_gba_core(std::nullopt);
    expect_failure<ravenemu::RomLoadError>(
        [&] { gba->load_rom(gba_rom, {}); },
        "une ROM GBA sans marqueur a été acceptée"
    );
}

void gba_smoke_test() {
    auto core = ravenemu::make_gba_core(ravenemu::GbaSaveType::sram);
    const auto rom = minimal_gba_rom();
    core->load_rom(rom, {});
    check(core->console() == ravenemu::Console::game_boy_advance, "console GBA incorrecte");
    check(
        core->framebuffer_format() == ravenemu::FramebufferFormat::argb_8888,
        "format vidéo GBA incorrect"
    );
    check(core->supports_video_frame_skipping(), "saut de rendu GBA non déclaré");
    check(core->gba_save_type() == ravenemu::GbaSaveType::sram, "type SRAM GBA incorrect");
    check(core->has_battery_ram(), "sauvegarde GBA absente");
    std::vector<std::int32_t> frame(core->video_spec().pixel_count());
    core->run_frame(frame, true);
    const auto battery = core->snapshot_battery_ram();
    check(battery && battery->data.size() == 32U * 1024U, "taille SRAM GBA incorrecte");
    check(battery->data[0] == 0x5a, "écriture CPU vers SRAM GBA perdue");
    check(core->battery_ram_dirty(), "écriture SRAM GBA non signalée");
    core->acknowledge_battery_ram_saved(battery->generation);
    check(!core->battery_ram_dirty(), "acquittement SRAM GBA ignoré");

    core->set_measuring_time(true);
    check(core->measuring_time(), "mesure GBA non activée");
    core->run_frame(frame, true);
    const auto debug = core->debug_snapshot();
    check(debug.has_value(), "photographie de diagnostic GBA absente");
    check(debug->instructions_per_frame > 0, "compteur d'instructions GBA vide");

    core->reset();
    check(core->measuring_time(), "mesure GBA perdue après reset");

    const auto state = core->save_state();
    check(!state.empty(), "état GBA vide");
    std::fill(frame.begin(), frame.end(), 0x12345678);
    core->run_frame(frame, false);
    check(
        std::all_of(frame.begin(), frame.end(), [](std::int32_t pixel) {
            return pixel == 0x12345678;
        }),
        "le saut de rendu GBA a modifié le framebuffer"
    );
    core->load_state(state);
    check(core->measuring_time(), "mesure GBA perdue après restauration d'état");
    core->run_frame(frame, true);
    const auto restored_battery = core->snapshot_battery_ram();
    check(restored_battery && restored_battery->data[0] == 0x5a, "SRAM GBA non restaurée");

    const auto before_failure = core->save_state();
    auto truncated = before_failure;
    truncated.resize(truncated.size() / 2U);
    expect_failure<ravenemu::SaveStateError>(
        [&] { core->load_state(truncated); },
        "un état GBA tronqué a été accepté"
    );
    check(
        core->save_state() == before_failure,
        "la restauration GBA en échec a modifié la machine"
    );

    auto other_rom = rom;
    other_rom[0x100] = 1;
    auto other_core = ravenemu::make_gba_core(ravenemu::GbaSaveType::sram);
    other_core->load_rom(other_rom, {});
    expect_failure<ravenemu::SaveStateError>(
        [&] { other_core->load_state(before_failure); },
        "un état GBA d'une autre ROM a été accepté"
    );

    std::vector<std::int32_t> short_frame(core->video_spec().pixel_count() - 1U);
    expect_failure<std::invalid_argument>(
        [&] { core->run_frame(short_frame, true); },
        "un framebuffer GBA trop petit a été accepté"
    );
}

void gba_save_detection_test() {
    auto rom = minimal_gba_rom();
    constexpr std::string_view marker = "FLASH1M_V";
    std::copy(marker.begin(), marker.end(), rom.begin() + 0x100);
    auto detected = ravenemu::make_gba_core(std::nullopt);
    detected->load_rom(rom, {});
    check(
        detected->gba_save_type() == ravenemu::GbaSaveType::flash_128k,
        "détection Flash 128 Kio GBA incorrecte"
    );

    auto forced = ravenemu::make_gba_core(ravenemu::GbaSaveType::eeprom_8k);
    forced->load_rom(rom, {});
    check(
        forced->gba_save_type() == ravenemu::GbaSaveType::eeprom_8k,
        "type de sauvegarde GBA imposé ignoré"
    );
    forced->set_gba_forced_save_type(ravenemu::GbaSaveType::sram);
    check(
        forced->gba_save_type() == ravenemu::GbaSaveType::eeprom_8k,
        "le type GBA actif a changé avant le reset"
    );
    forced->reset();
    check(
        forced->gba_save_type() == ravenemu::GbaSaveType::sram,
        "le type GBA imposé n'a pas été appliqué au reset"
    );
}

} // namespace

int main() {
    sha256_test();
    invalid_rom_test();
    game_boy_smoke_test();
    game_boy_color_and_battery_test();
    gba_smoke_test();
    gba_save_detection_test();
    return 0;
}
