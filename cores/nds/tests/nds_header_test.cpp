#include <ravenemu/nds/core.hpp>

#include "crc16.hpp"
#include "check.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

/**
 * En-tête de cartouche Nintendo DS et identité du cœur.
 *
 * Sont éprouvés ici le décodage de l'en-tête, le refus franc de ce qu'il ne sait
 * pas décrire, les caractéristiques publiées, et ce que le cœur accepte ou
 * refuse de faire. Le partage a changé : **balayer une trame n'est plus refusé**,
 * puisqu'il y a une console pour la produire ; enregistrer un état l'est
 * toujours, faute de format.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;
using ravenemu::testing::expect_failure;

namespace {

constexpr std::size_t default_rom_size = 0x8000;
constexpr std::uint32_t arm9_offset = 0x4000;
constexpr std::uint32_t arm7_offset = 0x6000;
constexpr std::uint32_t block_size = 0x400;

void write_u16(std::vector<std::uint8_t>& rom, std::size_t offset, std::uint16_t value) {
    rom[offset] = static_cast<std::uint8_t>(value & 0xffU);
    rom[offset + 1] = static_cast<std::uint8_t>((value >> 8U) & 0xffU);
}

void write_u32(std::vector<std::uint8_t>& rom, std::size_t offset, std::uint32_t value) {
    for (std::size_t byte = 0; byte < 4; ++byte) {
        rom[offset + byte] = static_cast<std::uint8_t>((value >> (byte * 8U)) & 0xffU);
    }
}

void write_text(std::vector<std::uint8_t>& rom, std::size_t offset, std::string_view text) {
    for (std::size_t index = 0; index < text.size(); ++index) {
        rom[offset + index] = static_cast<std::uint8_t>(text[index]);
    }
}

/** Recalcule et inscrit la somme de contrôle de l'en-tête. */
void seal(std::vector<std::uint8_t>& rom) {
    const auto crc = detail::crc16(
        std::span<const std::uint8_t>{rom}.subspan(0, CartridgeHeader::crc_covered_bytes)
    );
    write_u16(rom, CartridgeHeader::header_crc_offset, crc);
}

/** ROM synthétique minimale, structurellement valide et scellée. */
std::vector<std::uint8_t> synthetic_rom() {
    std::vector<std::uint8_t> rom(default_rom_size, 0);
    write_text(rom, 0x000, "RAVENTEST");
    write_text(rom, 0x00c, "ARVE");
    write_text(rom, 0x010, "01");
    rom[0x012] = static_cast<std::uint8_t>(UnitCode::nintendo_ds);
    rom[0x014] = 0x09;            // capacité annoncée : 128 Kio << 9 = 64 Mio
    rom[0x01e] = 0x02;            // version de ROM

    write_u32(rom, 0x020, arm9_offset);
    write_u32(rom, 0x024, 0x0200'0800);
    write_u32(rom, 0x028, 0x0200'0000);
    write_u32(rom, 0x02c, block_size);

    write_u32(rom, 0x030, arm7_offset);
    write_u32(rom, 0x034, 0x0380'0000);
    write_u32(rom, 0x038, 0x0380'0000);
    write_u32(rom, 0x03c, block_size);

    write_u32(rom, 0x068, 0x0000'7000);              // bloc icône et titres
    write_u32(rom, 0x080, static_cast<std::uint32_t>(default_rom_size));
    write_u32(rom, 0x084, 0x0000'4000);              // taille d'en-tête annoncée
    write_u16(rom, CartridgeHeader::logo_crc_offset, 0xcf56);
    seal(rom);
    return rom;
}

/**
 * Conformité du CRC à un vecteur publié, et non à lui-même.
 *
 * La valeur de contrôle du CRC-16/MODBUS pour la chaîne « 123456789 » vaut
 * 0x4B37. C'est le seul point d'ancrage extérieur disponible ici : comparer le
 * calcul à une somme produite par le même code ne prouverait rien, alors que
 * cette constante est vérifiable indépendamment de RavenEmu.
 */
void crc16_conforme_au_vecteur_publie() {
    constexpr std::string_view echantillon = "123456789";
    std::array<std::uint8_t, 9> octets{};
    for (std::size_t i = 0; i < echantillon.size(); ++i) {
        octets[i] = static_cast<std::uint8_t>(echantillon[i]);
    }
    check(detail::crc16(octets) == 0x4b37, "CRC-16 non conforme au vecteur de contrôle publié");

    // Une entrée vide doit rendre la valeur initiale, sans transformation.
    check(detail::crc16(std::span<const std::uint8_t>{}) == 0xffff, "CRC-16 d'une entrée vide incorrect");
}

void en_tete_decode_les_champs_utiles() {
    const auto rom = synthetic_rom();
    const auto header = CartridgeHeader::parse(rom);

    check(header.title == "RAVENTEST", "titre mal décodé");
    check(header.game_code == "ARVE", "code jeu mal décodé");
    check(header.maker_code == "01", "code éditeur mal décodé");
    check(header.unit_code == UnitCode::nintendo_ds, "code unité mal décodé");
    check(header.rom_version == 2, "version de ROM mal décodée");

    check(header.arm9_rom_offset == arm9_offset, "position du bloc ARM9 incorrecte");
    check(header.arm9_entry_address == 0x0200'0800, "point d'entrée ARM9 incorrect");
    check(header.arm9_ram_address == 0x0200'0000, "adresse de chargement ARM9 incorrecte");
    check(header.arm9_size == block_size, "taille du bloc ARM9 incorrecte");

    check(header.arm7_rom_offset == arm7_offset, "position du bloc ARM7 incorrecte");
    check(header.arm7_entry_address == 0x0380'0000, "point d'entrée ARM7 incorrect");
    check(header.arm7_size == block_size, "taille du bloc ARM7 incorrecte");

    check(header.icon_title_offset == 0x7000, "position du bloc icône incorrecte");
    check(header.header_size == 0x4000, "taille d'en-tête annoncée incorrecte");
    check(header.logo_crc == 0xcf56, "somme du logo mal décodée");
    check(header.declared_capacity_bytes() == 64ULL * 1024ULL * 1024ULL, "capacité annoncée incorrecte");
}

/** Les octets de remplissage et les caractères de contrôle ne passent pas. */
void les_champs_texte_sont_assainis() {
    auto rom = synthetic_rom();
    write_text(rom, 0x000, "AB");
    rom[0x002] = 0x01;                 // caractère de contrôle
    rom[0x003] = static_cast<std::uint8_t>(' ');
    rom[0x004] = 0;                    // fin de chaîne
    seal(rom);

    const auto header = CartridgeHeader::parse(rom);
    check(header.title == "AB?", "un caractère non imprimable a été recopié tel quel");
}

/**
 * Une somme fausse est rapportée, pas refusée : le cœur Game Boy Advance
 * procède déjà ainsi, et une partie des ROMs amateur démarrent sur console
 * avec un en-tête non scellé.
 */
void une_somme_fausse_est_rapportee_sans_refus() {
    const auto valide = CartridgeHeader::parse(synthetic_rom());
    check(valide.header_crc_valid(), "une ROM scellée devrait avoir une somme valide");
    check(valide.header_crc == valide.computed_header_crc, "somme lue et recalculée divergentes");

    auto rom = synthetic_rom();
    write_u16(rom, CartridgeHeader::header_crc_offset, 0x0000);
    const auto header = CartridgeHeader::parse(rom);
    check(!header.header_crc_valid(), "une somme fausse devrait être signalée");
    check(header.title == "RAVENTEST", "le reste de l'en-tête devrait rester décodé");
}

void les_images_inexploitables_sont_refusees() {
    expect_failure<RomLoadError>(
        [] { static_cast<void>(CartridgeHeader::parse(std::vector<std::uint8_t>(0x100, 0))); },
        "une image trop courte devrait être refusée"
    );

    expect_failure<RomLoadError>(
        [] {
            auto rom = synthetic_rom();
            rom[0x012] = static_cast<std::uint8_t>(UnitCode::nintendo_dsi);
            seal(rom);
            static_cast<void>(CartridgeHeader::parse(rom));
        },
        "une cartouche exclusivement DSi devrait être refusée"
    );

    expect_failure<RomLoadError>(
        [] {
            auto rom = synthetic_rom();
            rom[0x012] = 0x7f;
            seal(rom);
            static_cast<void>(CartridgeHeader::parse(rom));
        },
        "un code unité inconnu devrait être refusé"
    );

    expect_failure<RomLoadError>(
        [] {
            auto rom = synthetic_rom();
            write_u32(rom, 0x02c, 0);
            seal(rom);
            static_cast<void>(CartridgeHeader::parse(rom));
        },
        "un bloc ARM9 vide devrait être refusé"
    );

    expect_failure<RomLoadError>(
        [] {
            auto rom = synthetic_rom();
            write_u32(rom, 0x020, static_cast<std::uint32_t>(default_rom_size - 4));
            seal(rom);
            static_cast<void>(CartridgeHeader::parse(rom));
        },
        "un bloc ARM9 débordant de la ROM devrait être refusé"
    );

    expect_failure<RomLoadError>(
        [] {
            auto rom = synthetic_rom();
            write_u32(rom, 0x030, static_cast<std::uint32_t>(default_rom_size));
            seal(rom);
            static_cast<void>(CartridgeHeader::parse(rom));
        },
        "un bloc ARM7 débordant de la ROM devrait être refusé"
    );

    expect_failure<RomLoadError>(
        [] {
            auto rom = synthetic_rom();
            write_u32(rom, 0x020, 0x100);          // recouvre l'en-tête
            write_u32(rom, 0x02c, 0x40);
            seal(rom);
            static_cast<void>(CartridgeHeader::parse(rom));
        },
        "un bloc de code recouvrant l'en-tête devrait être refusé"
    );
}

/** Les deux écrans sont empilés dans un tampon unique. */
void le_contrat_video_decrit_deux_ecrans() {
    const auto core = make_core();
    const auto video = core->video_spec();

    check(video.width == screen_width, "largeur d'écran incorrecte");
    check(video.height == framebuffer_height, "le tampon doit contenir les deux écrans");
    check(video.height == 2 * screen_height, "le tampon doit valoir exactement deux écrans");
    check(bottom_screen_first_row == screen_height, "l'écran bas doit suivre l'écran haut");
    check(video.pixel_count() == 256U * 384U, "nombre de pixels incorrect");
    check(video.refresh_rate_hz > 59.8 && video.refresh_rate_hz < 59.9, "fréquence hors plage");
    check(core->framebuffer_format() == FramebufferFormat::argb_8888, "format vidéo incorrect");

    const auto audio = core->audio_spec();
    check(audio.sample_rate_hz == 32'768 && audio.channel_count == 2, "cadence audio incorrecte");
}

/** L'identité persistée ne doit jamais empiéter sur un identifiant retiré. */
void l_identite_persistee_est_distincte() {
    const auto core = make_core();
    check(core->console() == Console::nintendo_ds, "console rapportée incorrecte");
    check(static_cast<int>(Console::nintendo_ds) == 3, "identifiant persisté inattendu");
    check(static_cast<int>(Console::nintendo_ds) != 1, "l'identifiant 1 est retiré et ne doit pas être réattribué");
    check(
        static_cast<int>(Console::nintendo_ds) != static_cast<int>(Console::game_boy) &&
            static_cast<int>(Console::nintendo_ds) != static_cast<int>(Console::game_boy_advance),
        "identifiant en collision avec une console existante"
    );
}

void le_chargement_valide_la_cartouche() {
    auto core = make_core();
    core->load_rom(synthetic_rom(), {});

    expect_failure<RomLoadError>(
        [&] { core->load_rom(std::vector<std::uint8_t>(0x100, 0), {}); },
        "une image trop courte devrait être refusée au chargement"
    );
}

/**
 * Ce que le cœur accepte, et ce qu'il refuse encore.
 *
 * Sans cartouche, rien ne peut tourner : le refus est nommé plutôt que silencieux.
 * Avec une cartouche, la console tourne — et une cartouche dont le programme ne
 * fait rien montre un écran noir, non parce que l'émulation se tait mais parce
 * qu'un moteur graphique qu'on n'allume pas n'affiche rien.
 */
void ce_que_le_coeur_accepte_et_refuse() {
    auto core = make_core();
    std::vector<std::int32_t> framebuffer(static_cast<std::size_t>(screen_width * framebuffer_height));

    expect_failure<std::logic_error>(
        [&] { core->run_frame(framebuffer, true); },
        "sans ROM, l'exécution devrait être refusée"
    );

    core->load_rom(synthetic_rom(), {});
    core->run_frame(framebuffer, true);
    check(
        static_cast<std::uint32_t>(framebuffer[0]) == 0xff00'0000U,
        "un programme qui n'allume aucun moteur laisse l'écran du haut noir"
    );
    check(
        static_cast<std::uint32_t>(framebuffer.back()) == 0xff00'0000U,
        "et celui du bas également"
    );

    expect_failure<std::logic_error>(
        [&] { static_cast<void>(core->save_state()); },
        "aucun format d'état ne doit être publié tant qu'il n'y en a pas"
    );
    expect_failure<std::logic_error>(
        [&] { core->load_state(std::vector<std::uint8_t>(16, 0)); },
        "aucun état ne doit être accepté tant qu'il n'y en a pas"
    );

    // Les accessoires encore sans organe restent muets plutôt que d'échouer :
    // ils décrivent une absence, pas une erreur.
    check(!core->has_battery_ram(), "aucune sauvegarde de cartouche n'est encore décrite");
    check(!core->battery_ram_dirty(), "aucune sauvegarde ne peut être modifiée");
    check(core->read_audio(std::span<std::int16_t>{}) == 0, "aucun échantillon ne doit être produit");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    crc16_conforme_au_vecteur_publie();
    en_tete_decode_les_champs_utiles();
    les_champs_texte_sont_assainis();
    une_somme_fausse_est_rapportee_sans_refus();
    les_images_inexploitables_sont_refusees();
    le_contrat_video_decrit_deux_ecrans();
    l_identite_persistee_est_distincte();
    le_chargement_valide_la_cartouche();
    ce_que_le_coeur_accepte_et_refuse();
    return 0;
}
