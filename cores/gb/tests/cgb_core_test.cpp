#include "ravenemu/core.hpp"
#include "ravenemu/cheats.hpp"

#include "check.hpp"
#include "cheats/game_shark.hpp"
#include "synthetic_roms.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <optional>
#include <stdexcept>
#include <vector>

using ravenemu::testing::check;
using ravenemu::testing::expect_failure;
using ravenemu::testing::minimal_game_boy_rom;
using ravenemu::testing::minimal_gba_rom;

namespace {
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
}

void game_shark_parser_test() {
    const auto parsed = ravenemu::cgb::GameSharkRamWrite::parse("01-7f 38-cd");
    check(parsed.external_ram_bank == 1, "banque GameShark incorrecte");
    check(parsed.value == 0x7f, "valeur GameShark incorrecte");
    check(parsed.address == 0xcd38, "adresse GameShark incorrecte");
    check(parsed.normalized == "017F38CD", "normalisation GameShark incorrecte");

    expect_failure<std::invalid_argument>(
        [] { static_cast<void>(ravenemu::cgb::GameSharkRamWrite::parse("010238C")); },
        "longueur GameShark invalide acceptée"
    );
    expect_failure<std::invalid_argument>(
        [] { static_cast<void>(ravenemu::cgb::GameSharkRamWrite::parse("010G38CD")); },
        "caractère GameShark invalide accepté"
    );
    expect_failure<std::invalid_argument>(
        [] { static_cast<void>(ravenemu::cgb::GameSharkRamWrite::parse("910238CD")); },
        "variante GameShark inconnue acceptée"
    );
    expect_failure<std::invalid_argument>(
        [] { static_cast<void>(ravenemu::cgb::GameSharkRamWrite::parse("01020080")); },
        "adresse GameShark hors RAM acceptée"
    );
}

std::vector<std::uint8_t> cheat_test_rom() {
    auto rom = minimal_game_boy_rom();
    rom[0x0143] = 0x80; // mode CGB réel
    rom[0x0147] = 0x09; // ROM + RAM + pile, sans MBC
    rom[0x0149] = 0x02; // 8 Kio
    const std::array<std::uint8_t, 7> program{
        0x3e, 0x01,       // LD A,01
        0xea, 0x00, 0xa0, // LD (A000),A
        0x18, 0xf9,       // JR 0100
    };
    std::copy(program.begin(), program.end(), rom.begin() + 0x0100);
    return rom;
}

std::uint8_t battery_byte(ravenemu::Core& core, std::size_t offset) {
    const auto snapshot = core.snapshot_battery_ram();
    check(snapshot.has_value(), "instantané RAM absent pendant le test de cheats");
    check(offset < snapshot->data.size(), "offset RAM de test hors limites");
    return snapshot->data[offset];
}

void game_shark_real_core_test() {
    auto core = ravenemu::make_game_boy_core();
    core->load_rom(cheat_test_rom(), {});
    auto* cheats = dynamic_cast<ravenemu::CheatCapableCore*>(core.get());
    check(cheats != nullptr, "capability cheats absente du cœur GB livré");
    check(
        cheats->supported_cheat_formats() ==
            std::vector{ravenemu::CheatFormat::gameshark_gb_gbc},
        "format GameShark non annoncé pour la cartouche CGB"
    );

    std::vector<std::int32_t> frame(core->video_spec().pixel_count());
    core->run_frame(frame, true);
    check(battery_byte(*core, 0) == 0x01, "liste vide a modifié la RAM");

    const ravenemu::CheatCode first{
        ravenemu::CheatFormat::gameshark_gb_gbc,
        "002A00A0",
    };
    cheats->replace_active_cheats(std::span{&first, 1U});
    core->run_frame(frame, true);
    check(battery_byte(*core, 0) == 0x2a, "activation à chaud GameShark sans effet");

    const std::array multiple{
        first,
        ravenemu::CheatCode{ravenemu::CheatFormat::gameshark_gb_gbc, "003B01A0"},
    };
    cheats->replace_active_cheats(multiple);
    core->run_frame(frame, true);
    check(battery_byte(*core, 0) == 0x2a, "première ligne du cheat multiple absente");
    check(battery_byte(*core, 1) == 0x3b, "seconde ligne du cheat multiple absente");

    cheats->replace_active_cheats({});
    core->run_frame(frame, true);
    check(battery_byte(*core, 0) == 0x01, "désactivation à chaud sans effet");

    cheats->replace_active_cheats(std::span{&first, 1U});
    core->reset();
    core->run_frame(frame, true);
    check(battery_byte(*core, 0) == 0x2a, "reset a perdu les cheats actifs");

    cheats->replace_active_cheats({});
    core->run_frame(frame, true);
    const auto state_without_cheat_value = core->save_state();
    cheats->replace_active_cheats(std::span{&first, 1U});
    core->load_state(state_without_cheat_value);
    core->run_frame(frame, true);
    check(battery_byte(*core, 0) == 0x2a, "chargement d'état a perdu les cheats actifs");

    auto dmg = ravenemu::make_game_boy_core();
    dmg->load_rom(minimal_game_boy_rom(), {});
    auto* dmg_cheats = dynamic_cast<ravenemu::CheatCapableCore*>(dmg.get());
    check(dmg_cheats != nullptr, "capability générique absente du cœur unifié");
    check(dmg_cheats->supported_cheat_formats().empty(), "cheats annoncés pour une cartouche DMG");
    expect_failure<std::invalid_argument>(
        [&] { dmg_cheats->replace_active_cheats(std::span{&first, 1U}); },
        "un cheat a été accepté pour une cartouche DMG"
    );
}

} // namespace

int main() {
    invalid_rom_test();
    game_boy_smoke_test();
    game_boy_color_and_battery_test();
    game_shark_parser_test();
    game_shark_real_core_test();
    return 0;
}
