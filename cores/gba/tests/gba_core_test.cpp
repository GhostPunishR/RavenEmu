#include "ravenemu/core.hpp"
#include "ravenemu/cheats.hpp"

#include "cheats/gameshark_v1_v2.hpp"

#include "check.hpp"
#include "synthetic_roms.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <stdexcept>
#include <utility>
#include <vector>

using ravenemu::testing::check;
using ravenemu::testing::expect_failure;
using ravenemu::testing::minimal_game_boy_rom;
using ravenemu::testing::minimal_gba_rom;

namespace {
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

std::vector<std::uint8_t> gba_cheat_test_rom() {
    auto rom = minimal_gba_rom();
    // LDR r0,[pc,#8] ; MOV r1,#11 ; STRB r1,[r0] ; B vers MOV.
    // Le jeu rétablit donc 11 dans EWRAM en continu, avant le point de cheat.
    const std::array<std::uint32_t, 4> program{
        0xe59f0008U,
        0xe3a01011U,
        0xe5c01000U,
        0xeafffffcU,
    };
    for (std::size_t index = 0; index < program.size(); ++index) {
        for (unsigned byte = 0; byte < 4; ++byte) {
            rom[index * 4U + byte] =
                static_cast<std::uint8_t>(program[index] >> (byte * 8U));
        }
    }
    rom[16] = 0x00;
    rom[17] = 0x00;
    rom[18] = 0x00;
    rom[19] = 0x02; // 02000000, début EWRAM
    rom[0xac] = 'T';
    rom[0xad] = 'S';
    rom[0xae] = 'T';
    rom[0xaf] = '1';
    return rom;
}

std::uint8_t ewram_state_byte(ravenemu::Core& core, std::size_t offset) {
    // En-tête + registres + CPSR/état CPU + 28 registres bancaires.
    constexpr std::size_t ewram_state_offset =
        4U + 2U + 1U + 32U + 16U * 4U + 4U + 1U + 4U + 28U * 4U;
    const auto state = core.save_state();
    check(ewram_state_offset + offset < state.size(), "offset EWRAM de test invalide");
    return state[ewram_state_offset + offset];
}

std::uint8_t iwram_state_byte(ravenemu::Core& core, std::size_t offset) {
    constexpr std::size_t ewram_state_offset =
        4U + 2U + 1U + 32U + 16U * 4U + 4U + 1U + 4U + 28U * 4U;
    constexpr std::size_t iwram_state_offset = ewram_state_offset + 0x40000U;
    const auto state = core.save_state();
    check(iwram_state_offset + offset < state.size(), "offset IWRAM de test invalide");
    return state[iwram_state_offset + offset];
}

void gba_gameshark_cipher_test() {
    check(
        ravenemu::gba::decrypt_gameshark_v1_v2({0xcd93'194fU, 0x089c'e0b4U}) ==
            ravenemu::gba::GameSharkV1V2Words{0x0300'1c88U, 0x0000'002fU},
        "vecteur publié GameShark Advance v1/v2 mal déchiffré"
    );
    check(
        ravenemu::gba::decrypt_gameshark_v1_v2({0x2ac2'a65dU, 0x67fe'acc6U}) ==
            ravenemu::gba::GameSharkV1V2Words{0x0300'6df4U, 0x0000'0063U},
        "second vecteur GameShark Advance v1/v2 mal déchiffré"
    );
}

void gba_gameshark_cheat_test() {
    auto core = ravenemu::make_gba_core(std::nullopt);
    auto* cheats = dynamic_cast<ravenemu::CheatCapableCore*>(core.get());
    check(cheats != nullptr, "capability cheats absente du cœur GBA livré");
    check(cheats->supported_cheat_formats().empty(), "format GBA annoncé sans ROM");

    core->load_rom(gba_cheat_test_rom(), {});
    check(
        cheats->supported_cheat_formats() ==
            std::vector{ravenemu::CheatFormat::gameshark_gba_v1_v2},
        "format GameShark GBA v1/v2 non annoncé"
    );
    std::vector<std::int32_t> frame(core->video_spec().pixel_count());
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x11, "liste vide a modifié EWRAM");

    // Code chiffré réellement publié comme GameShark Advance v1/v2.
    const ravenemu::CheatCode published_encrypted{
        ravenemu::CheatFormat::gameshark_gba_v1_v2,
        "CD93194F 089CE0B4",
    };
    cheats->replace_active_cheats(std::span{&published_encrypted, 1U});
    core->run_frame(frame, false);
    check(iwram_state_byte(*core, 0x1c88U) == 0x2f, "code GameShark chiffré sans effet");
    check(ewram_state_byte(*core, 0) == 0x11, "code chiffré a écrit à la mauvaise adresse");

    const ravenemu::CheatCode multiple{
        ravenemu::CheatFormat::gameshark_gba_v1_v2,
        "RAW 02000000 0000002A\n"
        "RAW 12000002 0000BEEF\n"
        "RAW 22000004 12345678\n"
        "RAW 13000000 0000CAFE",
    };
    cheats->replace_active_cheats(std::span{&multiple, 1U});
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x2a, "écriture GameShark 8 bits absente");
    check(ewram_state_byte(*core, 2) == 0xef, "écriture GameShark 16 bits octet faible absent");
    check(ewram_state_byte(*core, 3) == 0xbe, "écriture GameShark 16 bits octet fort absent");
    check(ewram_state_byte(*core, 4) == 0x78, "écriture GameShark 32 bits octet 0 absent");
    check(ewram_state_byte(*core, 7) == 0x12, "écriture GameShark 32 bits octet 3 absent");
    check(iwram_state_byte(*core, 0) == 0xfe, "écriture GameShark IWRAM octet faible absent");
    check(iwram_state_byte(*core, 1) == 0xca, "écriture GameShark IWRAM octet fort absent");

    const std::array separate_cheats{
        ravenemu::CheatCode{
            ravenemu::CheatFormat::gameshark_gba_v1_v2,
            "RAW 02000000 0000003A",
        },
        ravenemu::CheatCode{
            ravenemu::CheatFormat::gameshark_gba_v1_v2,
            "RAW 12000002 0000CDEF",
        },
    };
    cheats->replace_active_cheats(separate_cheats);
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x3a, "premier cheat GameShark simultané absent");
    check(ewram_state_byte(*core, 2) == 0xef, "second cheat GameShark simultané absent");

    const ravenemu::CheatCode condition_true{
        ravenemu::CheatFormat::gameshark_gba_v1_v2,
        "RAW 12000010 00001234\n"
        "RAW D2000010 00001234\n"
        "RAW 02000000 0000003B",
    };
    cheats->replace_active_cheats(std::span{&condition_true, 1U});
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x3b, "condition GameShark vraie ignorée");

    const ravenemu::CheatCode condition_false{
        ravenemu::CheatFormat::gameshark_gba_v1_v2,
        "RAW 12000010 00005678\n"
        "RAW D2000010 00001234\n"
        "RAW 02000000 0000004C",
    };
    cheats->replace_active_cheats(std::span{&condition_false, 1U});
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x11, "condition GameShark fausse a écrit");

    const ravenemu::CheatCode condition_block{
        ravenemu::CheatFormat::gameshark_gba_v1_v2,
        "RAW 12000010 00001234\n"
        "RAW E0021234 02000010\n"
        "RAW 02000000 0000005D\n"
        "RAW 02000001 0000006E",
    };
    cheats->replace_active_cheats(std::span{&condition_block, 1U});
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x5d, "condition GameShark multiligne ligne 1 absente");
    check(ewram_state_byte(*core, 1) == 0x6e, "condition GameShark multiligne ligne 2 absente");

    // Le type 6 intercepte réellement les lectures d'instructions du bus : le
    // MOV #11 de la ROM synthétique devient MOV #2A.
    const ravenemu::CheatCode rom_patch{
        ravenemu::CheatFormat::gameshark_gba_v1_v2,
        "CAC738BF 74B65A44",
    };
    cheats->replace_active_cheats(std::span{&rom_patch, 1U});
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x2a, "patch ROM GameShark type 6 sans effet");

    cheats->replace_active_cheats({});
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x11, "désactivation à chaud GameShark sans effet");

    const ravenemu::CheatCode master_and_write{
        ravenemu::CheatFormat::gameshark_gba_v1_v2,
        "0C4CADCA 7D5C4CF0\n"
        "17B397CA E7919D45\n"
        "RAW DEADBEEF 001DC0DE\n"
        "RAW 02000000 0000002A",
    };
    cheats->replace_active_cheats(std::span{&master_and_write, 1U});
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x2a, "Master Code valide a bloqué le cheat");

    cheats->replace_active_cheats(std::span{&rom_patch, 1U});
    core->reset();
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x2a, "reset GBA a perdu le patch GameShark actif");

    cheats->replace_active_cheats({});
    core->run_frame(frame, true);
    const auto state_without_cheat_value = core->save_state();
    cheats->replace_active_cheats(std::span{&rom_patch, 1U});
    core->load_state(state_without_cheat_value);
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x2a, "save state a perdu le patch ROM actif");

    cheats->replace_active_cheats(std::span{&multiple, 1U});
    core->load_state(state_without_cheat_value);
    core->run_frame(frame, false);
    check(ewram_state_byte(*core, 0) == 0x2a, "save state GBA a perdu les cheats actifs");

    const auto expect_rejected = [&](std::string code, std::string message) {
        const ravenemu::CheatCode invalid{
            ravenemu::CheatFormat::gameshark_gba_v1_v2,
            std::move(code),
        };
        expect_failure<std::invalid_argument>(
            [&] { cheats->replace_active_cheats(std::span{&invalid, 1U}); },
            message
        );
    };
    expect_rejected("CD93194F 089CE0B", "ligne GameShark incomplète acceptée");
    expect_rejected("CD93194G 089CE0B4", "caractère GameShark non hexadécimal accepté");
    expect_rejected("RAW 40000000 00000000", "commande GameShark inconnue acceptée");
    expect_rejected("RAW 01000000 0000002A", "adresse GameShark invalide acceptée");
    expect_rejected("RAW 12000001 00001234", "adresse GameShark non alignée acceptée");
    expect_rejected("RAW 02000000 0000012A", "valeur GameShark 8 bits invalide acceptée");
    expect_rejected("RAW D8000000 00001234", "condition GameShark en ROM acceptée");
    expect_rejected("RAW D2000010 00001234", "condition GameShark incomplète acceptée");
    expect_rejected(
        "RAW D2000010 00001234\nRAW 60000002 1000102A",
        "condition GameShark appliquée à un patch ROM"
    );
    expect_rejected("RAW 30000002 12345678", "group write GameShark non implémenté accepté");
    expect_rejected("RAW 8A100000 0000002A", "commande bouton GameShark acceptée");
    expect_rejected("RAW F7000000 00000101", "hook de Master Code invalide accepté");
    expect_rejected("RAW DEADFACE 00000000", "changement de graines GameShark accepté");
    expect_rejected("D0CD0E46 4AA27D60", "changement de graines chiffré accepté");
    expect_rejected("RAW 60FFFFFF 10000001", "patch ROM hors limites accepté");

    const ravenemu::CheatCode wrong_format{
        ravenemu::CheatFormat::gameshark_gb_gbc,
        "002A00A0",
    };
    expect_failure<std::invalid_argument>(
        [&] { cheats->replace_active_cheats(std::span{&wrong_format, 1U}); },
        "format GB/GBC accepté par le cœur GBA"
    );

    // Tous les remplacements invalides sont atomiques : le dernier code valide reste actif.
    core->run_frame(frame, true);
    check(ewram_state_byte(*core, 0) == 0x2a, "échec de validation a altéré la liste active");
}


void invalid_rom_test() {
    auto rom = minimal_gba_rom();
    rom[0xb2] = 0;
    auto gba = ravenemu::make_gba_core(std::nullopt);
    expect_failure<ravenemu::RomLoadError>(
        [&] { gba->load_rom(rom, {}); },
        "une ROM GBA sans marqueur a été acceptée"
    );
}

} // namespace

int main() {
    invalid_rom_test();
    gba_smoke_test();
    gba_save_detection_test();
    gba_gameshark_cipher_test();
    gba_gameshark_cheat_test();
    return 0;
}
