#include "video/engine2d.hpp"
#include "video/video_memory.hpp"

#include "check.hpp"

#include <array>
#include <cstdint>
#include <string>

/**
 * Moteur graphique 2D, décors en mode texte.
 *
 * Trois niveaux se succèdent. Le premier éprouve ce qui se lit sans rien
 * dessiner : la nature de chaque plan selon le mode, et la conversion des
 * couleurs. Le deuxième dessine un plan seul, sous toutes ses formes — deux
 * profondeurs de palette, les deux retournements, les quatre tailles de carte,
 * le défilement. Le troisième superpose plusieurs plans, parce que la résolution
 * des priorités est le seul endroit où l'ordre compte et où une erreur cache un
 * décor derrière un autre sans rien signaler.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

/** Les deux moteurs, leurs banques et leur palette, montés ensemble. */
struct Screen {
    VideoMemory video{};
    std::array<std::uint8_t, 2048> palette{};
    Engine2d main{Engine::main, video, palette};
    Engine2d secondary{Engine::secondary, video, palette};
    std::array<std::int32_t, 256> row{};

    Screen() {
        video.reset();
        main.reset();
        secondary.reset();
    }

    [[nodiscard]] Engine2d& engine(Engine which) {
        return which == Engine::main ? main : secondary;
    }

    /** Branche une banque sur le décor du moteur voulu et rend son contenu. */
    [[nodiscard]] std::span<std::uint8_t> attach_background(Engine which) {
        const std::size_t bank = which == Engine::main ? 0U : 7U;
        video.set_control(bank, 0x81U);
        return video.bank(bank);
    }

    void set_colour(Engine which, std::uint32_t index, std::uint16_t colour) {
        const std::size_t base = which == Engine::main ? 0U : 1024U;
        palette[base + index * 2U] = static_cast<std::uint8_t>(colour & 0xffU);
        palette[base + index * 2U + 1U] = static_cast<std::uint8_t>(colour >> 8U);
    }
};

/** Une entrée de carte : tuile, retournements, sous-palette. */
[[nodiscard]] constexpr std::uint16_t map_entry(
    std::uint32_t tile,
    bool flip_x = false,
    bool flip_y = false,
    std::uint32_t sub_palette = 0
) {
    return static_cast<std::uint16_t>(
        tile | (flip_x ? 1U << 10U : 0U) | (flip_y ? 1U << 11U : 0U) | (sub_palette << 12U)
    );
}

void write_map(std::span<std::uint8_t> vram, std::uint32_t base, std::uint32_t cell, std::uint16_t entry) {
    vram[base + cell * 2U] = static_cast<std::uint8_t>(entry & 0xffU);
    vram[base + cell * 2U + 1U] = static_cast<std::uint8_t>(entry >> 8U);
}

/** Un pixel d'une tuile à seize couleurs : deux par octet, le pair en bas. */
void write_pixel4(
    std::span<std::uint8_t> vram,
    std::uint32_t base,
    std::uint32_t tile,
    std::uint32_t x,
    std::uint32_t y,
    std::uint8_t value
) {
    auto& byte = vram[base + tile * 32U + y * 4U + x / 2U];
    if ((x & 1U) != 0U) {
        byte = static_cast<std::uint8_t>((byte & 0x0fU) | static_cast<std::uint8_t>(value << 4U));
    } else {
        byte = static_cast<std::uint8_t>((byte & 0xf0U) | (value & 0x0fU));
    }
}

void write_pixel8(
    std::span<std::uint8_t> vram,
    std::uint32_t base,
    std::uint32_t tile,
    std::uint32_t x,
    std::uint32_t y,
    std::uint8_t value
) {
    vram[base + tile * 64U + y * 8U + x] = value;
}

/** Commande d'un plan, champ par champ comme le manuel les décrit. */
[[nodiscard]] constexpr std::uint16_t background_command(
    std::uint32_t priority,
    std::uint32_t character_block,
    std::uint32_t screen_block,
    bool full_palette = false,
    std::uint32_t size = 0
) {
    return static_cast<std::uint16_t>(
        priority | (character_block << 2U) | (full_palette ? 1U << 7U : 0U) |
        (screen_block << 8U) | (size << 14U)
    );
}

/** Affichage en mode graphique, avec les plans voulus allumés. */
[[nodiscard]] constexpr std::uint32_t display_command(std::uint32_t mode, std::uint32_t layers) {
    return mode | (layers << 8U) | (1U << 16U);
}

// --------------------------------------------------------------------------

void les_couleurs_passent_du_bleu_vert_rouge_a_l_argb() {
    Screen screen;
    // Le fond suffit à éprouver la conversion : c'est la première couleur de la
    // palette, et elle traverse tout le reste.
    screen.main.set_display_control(display_command(0, 0));

    const std::array<std::pair<std::uint16_t, std::uint32_t>, 5> expected{{
        {0x0000U, 0xff00'0000U},   // noir
        {0x001fU, 0xffff'0000U},   // rouge au maximum
        {0x03e0U, 0xff00'ff00U},   // vert au maximum
        {0x7c00U, 0xff00'00ffU},   // bleu au maximum
        {0x7fffU, 0xffff'ffffU},   // blanc
    }};

    for (const auto& [packed, argb] : expected) {
        screen.set_colour(Engine::main, 0, packed);
        screen.main.render_row(0, screen.row);
        check(
            static_cast<std::uint32_t>(screen.row[0]) == argb,
            "couleur " + std::to_string(packed) + " convertie"
        );
    }

    // Cinq bits deviennent huit sans perdre les extrêmes : le maximum doit
    // rester le maximum, et un cran valoir huit.
    screen.set_colour(Engine::main, 0, 0x0001U);
    screen.main.render_row(0, screen.row);
    check(
        (static_cast<std::uint32_t>(screen.row[0]) & 0xff'0000U) == 0x08'0000U,
        "un cran de rouge vaut huit"
    );
}

void chaque_mode_donne_une_nature_a_chaque_plan() {
    Screen screen;

    // Table écrite d'après le manuel, mode par mode et plan par plan.
    const std::array<std::array<LayerKind, 4>, 7> expected{{
        {LayerKind::text, LayerKind::text, LayerKind::text, LayerKind::text},
        {LayerKind::text, LayerKind::text, LayerKind::text, LayerKind::affine},
        {LayerKind::text, LayerKind::text, LayerKind::affine, LayerKind::affine},
        {LayerKind::text, LayerKind::text, LayerKind::text, LayerKind::extended},
        {LayerKind::text, LayerKind::text, LayerKind::affine, LayerKind::extended},
        {LayerKind::text, LayerKind::text, LayerKind::extended, LayerKind::extended},
        {LayerKind::three_dimensional, LayerKind::none, LayerKind::large_bitmap, LayerKind::none},
    }};

    for (std::uint32_t mode = 0; mode < 7U; ++mode) {
        screen.main.set_display_control(mode);
        for (std::size_t plan = 0; plan < 4U; ++plan) {
            check(
                screen.main.layer_kind(plan) == expected[mode][plan],
                "mode " + std::to_string(mode) + " plan " + std::to_string(plan)
            );
        }
    }

    {   // Le dernier mode n'appartient qu'au moteur principal.
        screen.secondary.set_display_control(6U);
        for (std::size_t plan = 0; plan < 4U; ++plan) {
            check(
                screen.secondary.layer_kind(plan) == LayerKind::none,
                "le moteur secondaire n'a pas le mode 6"
            );
        }
    }
    {   // Le mode 7 n'existe pas.
        screen.main.set_display_control(7U);
        check(screen.main.layer_kind(0) == LayerKind::none, "le mode 7 ne donne rien");
    }
    {   // Le premier plan cède la place au rendu 3D, sur le principal seulement.
        screen.main.set_display_control(0U | (1U << 3U));
        check(screen.main.layer_kind(0) == LayerKind::three_dimensional, "le plan 0 devient le 3D");
        check(screen.main.layer_kind(1) == LayerKind::text, "les autres ne bougent pas");

        screen.secondary.set_display_control(0U | (1U << 3U));
        check(
            screen.secondary.layer_kind(0) == LayerKind::text,
            "le secondaire n'a pas de rendu 3D à recevoir"
        );
    }
    check(screen.main.layer_kind(4) == LayerKind::none, "il n'y a pas de cinquième plan");
}

void un_plan_en_seize_couleurs_se_dessine() {
    Screen screen;
    auto vram = screen.attach_background(Engine::main);

    // Carte au bloc 1, tuiles au bloc 0 : les deux ne doivent pas se marcher
    // dessus, et c'est le logiciel qui les sépare.
    constexpr std::uint32_t tiles = 0x0000;
    constexpr std::uint32_t map = 0x0800;

    write_map(vram, map, 0, map_entry(1));
    write_pixel4(vram, tiles, 1, 0, 0, 3);
    write_pixel4(vram, tiles, 1, 1, 0, 0);   // transparent
    write_pixel4(vram, tiles, 1, 2, 0, 5);

    screen.set_colour(Engine::main, 0, 0x0000U);   // fond noir
    screen.set_colour(Engine::main, 3, 0x001fU);   // rouge
    screen.set_colour(Engine::main, 5, 0x03e0U);   // vert

    screen.main.set_background_control(0, background_command(0, 0, 1));
    screen.main.set_display_control(display_command(0, 0x1U));
    screen.main.render_row(0, screen.row);

    check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'0000U, "premier pixel rouge");
    check(static_cast<std::uint32_t>(screen.row[1]) == 0xff00'0000U, "la couleur zéro laisse voir le fond");
    check(static_cast<std::uint32_t>(screen.row[2]) == 0xff00'ff00U, "troisième pixel vert");
}

void une_sous_palette_deplace_les_couleurs_sans_deplacer_la_transparence() {
    Screen screen;
    auto vram = screen.attach_background(Engine::main);

    write_map(vram, 0x0800, 0, map_entry(1, false, false, 2));
    write_pixel4(vram, 0, 1, 0, 0, 4);
    write_pixel4(vram, 0, 1, 1, 0, 0);

    screen.set_colour(Engine::main, 0, 0x001fU);    // fond rouge, pour se voir
    screen.set_colour(Engine::main, 32U, 0x7fffU);  // la seizaine 2 n'est pas visée
    screen.set_colour(Engine::main, 36U, 0x03e0U);  // 2 * 16 + 4

    screen.main.set_background_control(0, background_command(0, 0, 1));
    screen.main.set_display_control(display_command(0, 0x1U));
    screen.main.render_row(0, screen.row);

    check(static_cast<std::uint32_t>(screen.row[0]) == 0xff00'ff00U, "la sous-palette décale de seize");
    check(
        static_cast<std::uint32_t>(screen.row[1]) == 0xffff'0000U,
        "mais la couleur zéro reste la transparence, et non la première de la seizaine"
    );
}

void un_plan_en_deux_cent_cinquante_six_couleurs_se_dessine() {
    Screen screen;
    auto vram = screen.attach_background(Engine::main);

    write_map(vram, 0x0800, 0, map_entry(1));
    write_pixel8(vram, 0, 1, 0, 0, 200);
    write_pixel8(vram, 0, 1, 1, 0, 0);

    screen.set_colour(Engine::main, 0, 0x0000U);
    screen.set_colour(Engine::main, 200U, 0x7c00U);

    // Une tuile fait alors soixante-quatre octets, non trente-deux : se tromper
    // ferait lire la tuile voisine.
    screen.main.set_background_control(0, background_command(0, 0, 1, true));
    screen.main.set_display_control(display_command(0, 0x1U));
    screen.main.render_row(0, screen.row);

    check(static_cast<std::uint32_t>(screen.row[0]) == 0xff00'00ffU, "couleur 200 lue directement");
    check(static_cast<std::uint32_t>(screen.row[1]) == 0xff00'0000U, "et zéro reste transparent");
}

void une_tuile_se_retourne_dans_les_deux_sens() {
    const std::array<std::pair<bool, bool>, 4> flips{{{false, false}, {true, false}, {false, true}, {true, true}}};

    for (const auto& [flip_x, flip_y] : flips) {
        Screen screen;
        auto vram = screen.attach_background(Engine::main);

        write_map(vram, 0x0800, 0, map_entry(1, flip_x, flip_y));
        // Un seul pixel allumé, dans un coin : sa place après retournement dit
        // tout, et une confusion des deux axes se verrait aussitôt.
        write_pixel4(vram, 0, 1, 0, 0, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);

        screen.main.set_background_control(0, background_command(0, 0, 1));
        screen.main.set_display_control(display_command(0, 0x1U));

        const std::uint32_t expected_x = flip_x ? 7U : 0U;
        const std::uint32_t expected_row = flip_y ? 7U : 0U;

        screen.main.render_row(expected_row, screen.row);
        check(
            static_cast<std::uint32_t>(screen.row[expected_x]) == 0xffff'ffffU,
            "le pixel se retrouve au bon coin"
        );
        screen.main.render_row(expected_row == 0U ? 7U : 0U, screen.row);
        check(
            static_cast<std::uint32_t>(screen.row[expected_x]) == 0xff00'0000U,
            "et nulle part ailleurs"
        );
    }
}

void le_defilement_deplace_le_decor() {
    Screen screen;
    auto vram = screen.attach_background(Engine::main);

    // Deux tuiles voisines, l'une pleine, l'autre vide.
    write_map(vram, 0x0800, 0, map_entry(1));
    write_map(vram, 0x0800, 1, map_entry(2));
    for (std::uint32_t y = 0; y < 8U; ++y) {
        for (std::uint32_t x = 0; x < 8U; ++x) write_pixel4(vram, 0, 2, x, y, 1);
    }

    screen.set_colour(Engine::main, 0, 0x0000U);
    screen.set_colour(Engine::main, 1, 0x7fffU);

    screen.main.set_background_control(0, background_command(0, 0, 1));
    screen.main.set_display_control(display_command(0, 0x1U));

    screen.main.render_row(0, screen.row);
    check(static_cast<std::uint32_t>(screen.row[0]) == 0xff00'0000U, "sans défilement, la première tuile est vide");
    check(static_cast<std::uint32_t>(screen.row[8]) == 0xffff'ffffU, "la seconde est pleine");

    screen.main.set_scroll_x(0, 8);
    screen.main.render_row(0, screen.row);
    check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "huit pixels de défilement amènent la seconde");

    // Le défilement vertical se lit sur la ligne, pas sur la colonne : les
    // confondre donnerait une image qui glisse de travers.
    screen.main.set_scroll_x(0, 0);
    screen.main.set_scroll_y(0, 8);
    screen.main.render_row(0, screen.row);
    check(static_cast<std::uint32_t>(screen.row[8]) == 0xff00'0000U, "la ligne 8 de la carte est vide");
}

void les_quatre_tailles_de_carte_choisissent_le_bon_bloc() {
    // Une carte large ou haute se range en blocs de 256 sur 256 qui se suivent.
    // Le bloc du bas ne se trouve pas au même rang selon que la carte est large,
    // et c'est là qu'une confusion affiche un quart de décor pour un autre.
    struct Case {
        std::uint32_t size;
        std::uint32_t scroll_x;
        std::uint32_t scroll_y;
        std::uint32_t block;
        const char* label;
    };
    const std::array<Case, 6> cases{{
        {0, 0, 0, 0, "256 sur 256 : un seul bloc"},
        {1, 256, 0, 1, "512 de large : la droite est au bloc 1"},
        {2, 0, 256, 1, "512 de haut : le bas est au bloc 1"},
        {3, 0, 256, 2, "512 sur 512 : le bas est au bloc 2"},
        {3, 256, 0, 1, "512 sur 512 : la droite est au bloc 1"},
        {3, 256, 256, 3, "512 sur 512 : le coin est au bloc 3"},
    }};

    for (const auto& entry : cases) {
        Screen screen;
        auto vram = screen.attach_background(Engine::main);

        constexpr std::uint32_t map = 0x2000;
        write_map(vram, map + entry.block * 0x800U, 0, map_entry(1));
        write_pixel4(vram, 0, 1, 0, 0, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);

        screen.main.set_background_control(0, background_command(0, 0, 4, false, entry.size));
        screen.main.set_display_control(display_command(0, 0x1U));
        screen.main.set_scroll_x(0, static_cast<std::uint16_t>(entry.scroll_x));
        screen.main.set_scroll_y(0, static_cast<std::uint16_t>(entry.scroll_y));

        screen.main.render_row(0, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, entry.label);
    }
}

void les_bases_se_comptent_par_blocs() {
    {   // Le bloc de tuiles vaut seize kilooctets, celui de carte deux.
        Screen screen;
        auto vram = screen.attach_background(Engine::main);
        write_map(vram, 3U * 0x800U, 0, map_entry(0));
        write_pixel4(vram, 2U * 0x4000U, 0, 0, 0, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);
        screen.main.set_background_control(0, background_command(0, 2, 3));
        screen.main.set_display_control(display_command(0, 0x1U));
        screen.main.render_row(0, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "seize kilooctets et deux");
    }
    {   // Le moteur principal décale en plus toutes ses bases par deux champs
        // de la commande d'affichage, par blocs de soixante-quatre kilooctets.
        // Ces décalages portent au-delà d'une seule banque : c'est justement ce
        // qu'ils servent à atteindre, et il en faut donc deux.
        Screen screen;
        auto first = screen.attach_background(Engine::main);
        screen.video.set_control(1, 0x89U);   // B au cran suivant, à 0x20000
        auto second = screen.video.bank(1);

        // Une entrée différente au début : sans le décalage de carte, elle
        // serait lue là, et elle renvoie à une tuile vide. Son numéro est choisi
        // pour que ses deux octets ne puissent pas non plus passer pour des
        // pixels : sans le décalage de tuiles, c'est ici que la tuile 0 serait
        // lue, et une entrée finissant par un chiffre non nul y ferait un pixel
        // allumé qui masquerait la faute.
        write_map(first, 0, 0, map_entry(16));
        write_map(first, 0x1'0000U, 0, map_entry(0));
        write_pixel4(second, 0, 0, 0, 0, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);
        screen.main.set_background_control(0, background_command(0, 0, 0));
        screen.main.set_display_control(
            display_command(0, 0x1U) | (2U << 24U) | (1U << 27U)
        );
        screen.main.render_row(0, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "soixante-quatre kilooctets par cran");
    }
    {   // Le moteur secondaire n'a pas ces deux champs : les écrire ne doit rien
        // décaler chez lui, sans quoi son décor partirait ailleurs.
        Screen screen;
        auto vram = screen.attach_background(Engine::secondary);
        write_map(vram, 0x0800U, 0, map_entry(1));
        write_pixel4(vram, 0, 1, 0, 0, 1);

        screen.set_colour(Engine::secondary, 0, 0x0000U);
        screen.set_colour(Engine::secondary, 1, 0x7fffU);
        screen.secondary.set_background_control(0, background_command(0, 0, 1));
        screen.secondary.set_display_control(
            display_command(0, 0x1U) | (2U << 24U) | (1U << 27U)
        );
        screen.secondary.render_row(0, screen.row);
        check(
            static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU,
            "le secondaire ignore les deux champs supplémentaires"
        );
    }
}

void chaque_moteur_lit_sa_propre_palette() {
    Screen screen;
    auto vram = screen.attach_background(Engine::secondary);
    write_map(vram, 0x0800U, 0, map_entry(1));
    write_pixel4(vram, 0, 1, 0, 0, 1);

    // La même place dans la palette du principal ne doit rien changer chez le
    // secondaire : les deux tables sont distantes de mille vingt-quatre octets.
    screen.set_colour(Engine::main, 1, 0x001fU);
    screen.set_colour(Engine::secondary, 0, 0x0000U);
    screen.set_colour(Engine::secondary, 1, 0x03e0U);

    screen.secondary.set_background_control(0, background_command(0, 0, 1));
    screen.secondary.set_display_control(display_command(0, 0x1U));
    screen.secondary.render_row(0, screen.row);
    check(static_cast<std::uint32_t>(screen.row[0]) == 0xff00'ff00U, "le secondaire lit sa propre table");
}

void les_champs_larges_vont_jusqu_a_leur_dernier_bit() {
    {   // La base des tuiles compte seize blocs, non huit : le dernier bit du
        // champ porte au-delà d'une seule banque, et c'est ce qu'il sert à faire.
        Screen screen;
        auto first = screen.attach_background(Engine::main);
        screen.video.set_control(1, 0x89U);   // B au cran suivant, à 0x20000
        auto second = screen.video.bank(1);

        write_map(first, 0x800U, 0, map_entry(0));
        write_pixel4(second, 0, 0, 0, 0, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);
        screen.main.set_background_control(0, background_command(0, 8, 1));
        screen.main.set_display_control(display_command(0, 0x1U));
        screen.main.render_row(0, screen.row);
        check(
            static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU,
            "le bloc de tuiles numéro 8 existe"
        );
    }
    {   // La base de la carte compte trente-deux blocs, non seize.
        Screen screen;
        auto vram = screen.attach_background(Engine::main);
        write_map(vram, 16U * 0x800U, 0, map_entry(1));
        write_pixel4(vram, 0, 1, 0, 0, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);
        screen.main.set_background_control(0, background_command(0, 0, 16));
        screen.main.set_display_control(display_command(0, 0x1U));
        screen.main.render_row(0, screen.row);
        check(
            static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU,
            "le bloc de carte numéro 16 existe"
        );
    }
    {   // Le numéro de tuile tient sur dix bits : mille vingt-quatre tuiles.
        Screen screen;
        auto vram = screen.attach_background(Engine::main);
        write_map(vram, 0x800U, 0, map_entry(512));
        write_pixel4(vram, 0, 512, 0, 0, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);
        screen.main.set_background_control(0, background_command(0, 0, 1));
        screen.main.set_display_control(display_command(0, 0x1U));
        screen.main.render_row(0, screen.row);
        check(
            static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU,
            "la tuile 512 se distingue de la tuile 0"
        );
    }
    {   // La priorité tient sur deux bits. Un seul suffirait à classer 2 avant 3,
        // mais renverserait 1 et 2 : c'est ce couple qui le dit.
        Screen screen;
        auto vram = screen.attach_background(Engine::main);
        write_map(vram, 0x800U, 0, map_entry(1));
        write_map(vram, 0x1000U, 0, map_entry(2));
        write_pixel4(vram, 0, 1, 0, 0, 1);
        write_pixel4(vram, 0, 2, 0, 0, 2);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x001fU);
        screen.set_colour(Engine::main, 2, 0x03e0U);

        screen.main.set_background_control(0, background_command(1, 0, 1));
        screen.main.set_background_control(1, background_command(2, 0, 2));
        screen.main.set_display_control(display_command(0, 0x3U));
        screen.main.render_row(0, screen.row);
        check(
            static_cast<std::uint32_t>(screen.row[0]) == 0xffff'0000U,
            "la priorité 1 passe devant la priorité 2"
        );
    }
}

void une_tuile_se_lit_ligne_par_ligne_et_colonne_par_colonne() {
    {   // Une ligne de tuile fait quatre octets en seize couleurs.
        Screen screen;
        auto vram = screen.attach_background(Engine::main);
        write_map(vram, 0x800U, 0, map_entry(1));
        write_pixel4(vram, 0, 1, 0, 3, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);
        screen.main.set_background_control(0, background_command(0, 0, 1));
        screen.main.set_display_control(display_command(0, 0x1U));

        screen.main.render_row(3, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "la ligne 3 de la tuile");
        screen.main.render_row(0, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xff00'0000U, "et pas la ligne 0");
    }
    {   // Une ligne de tuile fait huit octets en deux cent cinquante-six.
        Screen screen;
        auto vram = screen.attach_background(Engine::main);
        write_map(vram, 0x800U, 0, map_entry(1));
        write_pixel8(vram, 0, 1, 0, 3, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);
        screen.main.set_background_control(0, background_command(0, 0, 1, true));
        screen.main.set_display_control(display_command(0, 0x1U));

        screen.main.render_row(3, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "la ligne 3 en 256 couleurs");
        screen.main.render_row(0, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xff00'0000U, "et pas la ligne 0");
    }
    {   // Une tuile fait huit pixels de large : le neuvième appartient à la
        // suivante, et non à une neuvième colonne de la première.
        Screen screen;
        auto vram = screen.attach_background(Engine::main);
        write_map(vram, 0x800U, 0, map_entry(1));
        write_map(vram, 0x800U, 1, map_entry(2));
        write_pixel4(vram, 0, 1, 0, 0, 1);
        write_pixel4(vram, 0, 2, 0, 0, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);
        screen.main.set_background_control(0, background_command(0, 0, 1));
        screen.main.set_display_control(display_command(0, 0x1U));
        screen.main.render_row(0, screen.row);

        check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "premier pixel de la première");
        check(static_cast<std::uint32_t>(screen.row[8]) == 0xffff'ffffU, "premier pixel de la seconde");
        check(static_cast<std::uint32_t>(screen.row[1]) == 0xff00'0000U, "et rien entre les deux");
    }
    {   // Une tuile fait huit pixels de haut, et la carte compte trente-deux
        // tuiles par ligne : la neuvième ligne d'écran est la seconde ligne de
        // la carte, non la neuvième ligne d'une tuile.
        Screen screen;
        auto vram = screen.attach_background(Engine::main);
        write_map(vram, 0x800U, 32, map_entry(1));
        write_pixel4(vram, 0, 1, 0, 0, 1);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x7fffU);
        screen.main.set_background_control(0, background_command(0, 0, 1));
        screen.main.set_display_control(display_command(0, 0x1U));

        screen.main.render_row(8, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "la seconde ligne de la carte");
        screen.main.render_row(0, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xff00'0000U, "et pas la première");
    }
}

void la_carte_se_replie_au_dela_de_ses_bords() {
    Screen screen;
    auto vram = screen.attach_background(Engine::main);
    write_map(vram, 0x800U, 0, map_entry(1));
    write_pixel4(vram, 0, 1, 0, 0, 1);

    screen.set_colour(Engine::main, 0, 0x0000U);
    screen.set_colour(Engine::main, 1, 0x7fffU);
    screen.main.set_background_control(0, background_command(0, 0, 1));
    screen.main.set_display_control(display_command(0, 0x1U));

    // Une carte de 256 de haut se répète : défiler de 256 revient au début, et
    // non au-delà de son unique bloc.
    screen.main.set_scroll_y(0, 256);
    screen.main.render_row(0, screen.row);
    check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "la carte se replie en hauteur");

    screen.main.set_scroll_y(0, 0);
    screen.main.set_scroll_x(0, 256);
    screen.main.render_row(0, screen.row);
    check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "et en largeur");
}

void un_plan_devant_ne_couvre_pas_avec_sa_transparence() {
    // Le plan de devant est transparent à cet endroit : c'est le plan de
    // derrière qu'on doit voir, et non le fond. Sans quoi un plan de devant
    // masquerait tout ce qui est derrière lui dès qu'il est allumé.
    Screen screen;
    auto vram = screen.attach_background(Engine::main);

    write_map(vram, 0x800U, 0, map_entry(1));
    write_map(vram, 0x1000U, 0, map_entry(2));
    write_pixel4(vram, 0, 1, 0, 0, 0);   // devant, transparent
    write_pixel4(vram, 0, 2, 0, 0, 2);   // derrière, visible

    screen.set_colour(Engine::main, 0, 0x001fU);   // fond rouge
    screen.set_colour(Engine::main, 2, 0x03e0U);   // plan de derrière, vert

    screen.main.set_background_control(0, background_command(0, 0, 1));
    screen.main.set_background_control(1, background_command(1, 0, 2));
    screen.main.set_display_control(display_command(0, 0x3U));
    screen.main.render_row(0, screen.row);
    check(
        static_cast<std::uint32_t>(screen.row[0]) == 0xff00'ff00U,
        "le plan de derrière se voit à travers celui de devant"
    );
}

void un_plan_qui_n_est_pas_en_mode_texte_ne_dessine_rien() {
    // Le plan est configuré comme un décor en tuiles et ses données sont en
    // place, mais le mode en fait une surface tournante : le dessiner comme un
    // décor en tuiles donnerait une image plausible et fausse.
    Screen screen;
    auto vram = screen.attach_background(Engine::main);
    write_map(vram, 0x800U, 0, map_entry(1));
    write_pixel4(vram, 0, 1, 0, 0, 1);

    screen.set_colour(Engine::main, 0, 0x0000U);
    screen.set_colour(Engine::main, 1, 0x7fffU);
    screen.main.set_background_control(2, background_command(0, 0, 1));
    screen.main.set_display_control(display_command(2, 0x4U));
    screen.main.render_row(0, screen.row);

    check(static_cast<std::uint32_t>(screen.row[0]) == 0xff00'0000U, "rien n'est dessiné");
    check(screen.main.unimplemented_layer_count() == 1U, "et le plan est compté");
}

void les_priorites_decident_de_ce_qui_couvre() {
    // Deux plans qui se recouvrent entièrement : seule la priorité tranche, et
    // à priorité égale, le plan de plus petit numéro.
    struct Case {
        std::uint32_t priority0;
        std::uint32_t priority1;
        std::uint32_t expected;
        const char* label;
    };
    const std::array<Case, 3> cases{{
        {0, 1, 0xffff'0000U, "le plan de priorité la plus basse couvre"},
        {1, 0, 0xff00'ff00U, "dans l'autre sens aussi"},
        {1, 1, 0xffff'0000U, "à égalité, le plan de plus petit numéro l'emporte"},
    }};

    for (const auto& entry : cases) {
        Screen screen;
        auto vram = screen.attach_background(Engine::main);

        write_map(vram, 0x0800U, 0, map_entry(1));
        write_map(vram, 0x1000U, 0, map_entry(2));
        write_pixel4(vram, 0, 1, 0, 0, 1);
        write_pixel4(vram, 0, 2, 0, 0, 2);

        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.set_colour(Engine::main, 1, 0x001fU);   // plan 0, rouge
        screen.set_colour(Engine::main, 2, 0x03e0U);   // plan 1, vert

        screen.main.set_background_control(0, background_command(entry.priority0, 0, 1));
        screen.main.set_background_control(1, background_command(entry.priority1, 0, 2));
        screen.main.set_display_control(display_command(0, 0x3U));
        screen.main.render_row(0, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == entry.expected, entry.label);
    }
}

void un_plan_eteint_ne_se_dessine_pas() {
    Screen screen;
    auto vram = screen.attach_background(Engine::main);
    write_map(vram, 0x0800U, 0, map_entry(1));
    write_pixel4(vram, 0, 1, 0, 0, 1);

    screen.set_colour(Engine::main, 0, 0x0000U);
    screen.set_colour(Engine::main, 1, 0x7fffU);
    screen.main.set_background_control(0, background_command(0, 0, 1));

    // Le plan est configuré, mais son bit d'allumage est absent.
    screen.main.set_display_control(display_command(0, 0x0U));
    screen.main.render_row(0, screen.row);
    check(static_cast<std::uint32_t>(screen.row[0]) == 0xff00'0000U, "un plan éteint ne dessine rien");
    check(screen.main.unimplemented_layer_count() == 0U, "et ce n'est pas un manque");

    screen.main.set_display_control(display_command(0, 0x1U));
    screen.main.render_row(0, screen.row);
    check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "allumé, il dessine");
}

void ce_qui_n_est_pas_encore_dessine_est_compte() {
    {   // Un plan tournant est demandé : il n'est pas dessiné, et il le dit.
        Screen screen;
        screen.main.set_display_control(display_command(2, 0x4U));
        screen.main.render_row(0, screen.row);
        check(screen.main.unimplemented_layer_count() == 1U, "un plan tournant est compté");
    }
    {   // Un plan que le mode ne donne pas n'est pas un manque : le matériel
        // n'affiche rien non plus, et le compter serait une fausse alerte.
        Screen screen;
        screen.main.set_display_control(display_command(6, 0x2U));
        screen.main.render_row(0, screen.row);
        check(screen.main.unimplemented_layer_count() == 0U, "un plan absent du mode n'est pas compté");
    }
    {   // Les deux modes d'affichage qui n'appartiennent qu'au principal.
        Screen screen;
        screen.main.set_display_control(0U | (2U << 16U));
        screen.main.render_row(0, screen.row);
        check(screen.main.unimplemented_display_count() == 1U, "afficher une banque telle quelle");

        screen.main.set_display_control(0U | (3U << 16U));
        screen.main.render_row(0, screen.row);
        check(screen.main.unimplemented_display_count() == 2U, "lire l'image en mémoire principale");
    }
}

void l_ecran_eteint_et_le_blanc_force_ne_sont_pas_la_meme_chose() {
    {   // Éteint, l'écran est noir. C'est un état du matériel, non un manque :
        // le confondre avec un mode non servi ferait remonter une fausse alerte
        // à chaque trame d'un jeu qui éteint son second écran.
        Screen screen;
        screen.set_colour(Engine::main, 0, 0x7fffU);
        screen.main.set_display_control(0U);
        screen.main.render_row(0, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xff00'0000U, "l'écran éteint est noir");
        check(screen.main.unimplemented_display_count() == 0U, "et ce n'est pas un mode non servi");
    }
    {   // Le blanc forcé coupe la sortie, et l'écran devient blanc, non noir.
        Screen screen;
        screen.set_colour(Engine::main, 0, 0x0000U);
        screen.main.set_display_control(display_command(0, 0xfU) | (1U << 7U));
        screen.main.render_row(0, screen.row);
        check(static_cast<std::uint32_t>(screen.row[0]) == 0xffff'ffffU, "le blanc forcé est blanc");
        check(static_cast<std::uint32_t>(screen.row[255]) == 0xffff'ffffU, "sur toute la ligne");
        check(screen.main.unimplemented_layer_count() == 0U, "et rien n'est même consulté");
    }
}

void une_ligne_trop_courte_n_est_pas_ecrite() {
    Screen screen;
    screen.set_colour(Engine::main, 0, 0x7fffU);
    screen.main.set_display_control(display_command(0, 0));

    std::array<std::int32_t, 255> short_row{};
    screen.main.render_row(0, short_row);
    check(short_row[0] == 0, "une ligne trop courte est laissée intacte");

    screen.main.render_row(0, screen.row);
    check(static_cast<std::uint32_t>(screen.row[255]) == 0xffff'ffffU, "une ligne entière est remplie");
}

void la_remise_a_zero_efface_les_registres() {
    Screen screen;
    screen.main.set_display_control(0xffff'ffffU);
    screen.main.set_background_control(2, 0xffffU);
    screen.main.set_scroll_x(1, 0x1ffU);
    screen.main.set_scroll_y(3, 0x0ffU);
    screen.main.set_display_control(0U | (2U << 16U));
    screen.main.render_row(0, screen.row);

    screen.main.reset();
    check(screen.main.display_control() == 0U, "la commande d'affichage est effacée");
    check(screen.main.background_control(2) == 0U, "les commandes de plans aussi");
    check(screen.main.scroll_x(1) == 0U, "le défilement horizontal");
    check(screen.main.scroll_y(3) == 0U, "et le vertical");
    check(screen.main.unimplemented_display_count() == 0U, "et les comptes repartent de zéro");
}

void les_registres_hors_bornes_ne_debordent_pas() {
    Screen screen;
    screen.main.set_background_control(4, 0xffffU);
    screen.main.set_scroll_x(4, 0xffffU);
    screen.main.set_scroll_y(4, 0xffffU);
    check(screen.main.background_control(4) == 0U, "il n'y a pas de cinquième commande de plan");
    check(screen.main.scroll_x(4) == 0U, "ni de cinquième défilement horizontal");
    check(screen.main.scroll_y(4) == 0U, "ni vertical");
    check(screen.main.background_control(0) == 0U, "et rien n'a débordé sur le premier");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_couleurs_passent_du_bleu_vert_rouge_a_l_argb();
    chaque_mode_donne_une_nature_a_chaque_plan();
    un_plan_en_seize_couleurs_se_dessine();
    une_sous_palette_deplace_les_couleurs_sans_deplacer_la_transparence();
    un_plan_en_deux_cent_cinquante_six_couleurs_se_dessine();
    une_tuile_se_retourne_dans_les_deux_sens();
    le_defilement_deplace_le_decor();
    les_quatre_tailles_de_carte_choisissent_le_bon_bloc();
    les_bases_se_comptent_par_blocs();
    chaque_moteur_lit_sa_propre_palette();
    les_champs_larges_vont_jusqu_a_leur_dernier_bit();
    une_tuile_se_lit_ligne_par_ligne_et_colonne_par_colonne();
    la_carte_se_replie_au_dela_de_ses_bords();
    un_plan_devant_ne_couvre_pas_avec_sa_transparence();
    un_plan_qui_n_est_pas_en_mode_texte_ne_dessine_rien();
    les_priorites_decident_de_ce_qui_couvre();
    un_plan_eteint_ne_se_dessine_pas();
    ce_qui_n_est_pas_encore_dessine_est_compte();
    l_ecran_eteint_et_le_blanc_force_ne_sont_pas_la_meme_chose();
    une_ligne_trop_courte_n_est_pas_ecrite();
    la_remise_a_zero_efface_les_registres();
    les_registres_hors_bornes_ne_debordent_pas();
    return 0;
}
