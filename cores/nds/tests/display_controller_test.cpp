#include "memory/arm7_memory_map.hpp"
#include "memory/arm9_memory_map.hpp"
#include "memory/system_memory.hpp"
#include "video/video_system.hpp"

#include <ravenemu/nds/core.hpp>

#include "check.hpp"

#include <array>
#include <cstdint>
#include <string>
#include <vector>

/**
 * Contrôleur d'affichage.
 *
 * Trois niveaux se succèdent. Le premier éprouve le compteur et les deux
 * registres d'état, où le piège est la ligne guettée, dont le neuvième bit est
 * rangé loin des huit autres. Le deuxième éprouve **les trois interruptions**,
 * et surtout le fait que chaque processeur règle les siennes sans toucher à
 * celles de l'autre. Le troisième dessine une trame entière et vérifie que
 * chaque moteur alimente le bon écran, échange compris.
 *
 * Les nombres du balayage sont écrits en toutes lettres, non reprises des
 * constantes du cœur.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

/** Le matériel partagé, monté comme dans la console. */
struct Console {
    SystemMemory system{};
    InterruptController main_interrupts{};
    InterruptController secondary_interrupts{};
    InterProcessor link{main_interrupts, secondary_interrupts};
    VideoSystem video{main_interrupts, secondary_interrupts};
    Arm9MemoryMap main_map{system, video, link, main_interrupts};
    Arm7MemoryMap secondary_map{system, video, link, secondary_interrupts};

    Console() {
        system.reset();
        main_interrupts.reset();
        secondary_interrupts.reset();
        link.reset();
        video.reset();
        main_map.reset();
        secondary_map.reset();
    }

    [[nodiscard]] DisplayController& display() { return video.display(); }

    /** Avance jusqu'à la ligne voulue, en balayant celles d'avant. */
    void advance_to(std::uint32_t line) {
        for (std::uint32_t step = 0; step < 263U * 2U; ++step) {
            if (display().line() == line) return;
            display().advance_line();
        }
        check(false, "la ligne " + std::to_string(line) + " est atteignable");
    }
};

/** Registre d'état : autorisations et ligne guettée, champ par champ. */
[[nodiscard]] constexpr std::uint16_t status_command(
    std::uint32_t watched = 0,
    bool on_vertical = false,
    bool on_horizontal = false,
    bool on_match = false
) {
    return static_cast<std::uint16_t>(
        (on_vertical ? 1U << 3U : 0U) | (on_horizontal ? 1U << 4U : 0U) |
        (on_match ? 1U << 5U : 0U) |
        (((watched >> 8U) & 1U) << 7U) | ((watched & 0xffU) << 8U)
    );
}

// --------------------------------------------------------------------------

void le_balayage_a_les_nombres_du_materiel() {
    check(DisplayController::visible_lines == 192U, "192 lignes affichées");
    check(DisplayController::total_lines == 263U, "263 lignes balayées");
    check(DisplayController::visible_dots == 256U, "256 points affichés par ligne");
    check(DisplayController::dots_per_line == 355U, "355 points balayés par ligne");
    check(DisplayController::cycles_per_dot == 6U, "six cycles par point");

    // La fréquence annoncée doit découler de ces nombres, et non d'une valeur
    // recopiée à côté : 355 points sur 263 lignes à six cycles.
    const double cycles = 355.0 * 263.0 * 6.0;
    const double expected = 33'513'982.0 / cycles;
    const double gap = refresh_rate_hz > expected ? refresh_rate_hz - expected
                                                  : expected - refresh_rate_hz;
    check(gap < 1e-9, "la fréquence de rafraîchissement découle du balayage");
}

void le_compteur_de_lignes_boucle_sur_le_balayage() {
    Console console;
    check(console.display().line() == 0U, "le balayage part de la première ligne");

    for (std::uint32_t line = 1; line < 263U; ++line) {
        console.display().advance_line();
        check(
            console.display().line() == line,
            "ligne " + std::to_string(line) + " atteinte dans l'ordre"
        );
    }
    console.display().advance_line();
    check(console.display().line() == 0U, "et le balayage revient au début après la 262e");
}

void l_indicateur_de_retour_vertical_s_eteint_une_ligne_avant_la_fin() {
    Console console;

    // Sur les lignes affichées, pas de retour vertical.
    console.advance_to(191U);
    check(
        (console.display().status(Processor::main) & 0x0001U) == 0U,
        "la dernière ligne affichée n'est pas un retour vertical"
    );

    console.display().advance_line();
    check(
        (console.display().status(Processor::main) & 0x0001U) != 0U,
        "la première ligne non affichée en est un"
    );

    console.advance_to(261U);
    check(
        (console.display().status(Processor::main) & 0x0001U) != 0U,
        "l'avant-dernière ligne aussi"
    );

    // Le détail qui surprend : la toute dernière ligne n'en est pas un.
    console.display().advance_line();
    check(console.display().line() == 262U, "on est bien sur la dernière ligne");
    check(
        (console.display().status(Processor::main) & 0x0001U) == 0U,
        "mais la dernière ligne n'est pas comptée comme retour vertical"
    );

    // Et l'indicateur se lit pareil des deux côtés : c'est le même faisceau.
    console.advance_to(200U);
    check(
        (console.display().status(Processor::secondary) & 0x0001U) != 0U,
        "le second processeur voit le même retour vertical"
    );
}

void la_ligne_guettee_tient_sur_neuf_bits() {
    {   // Les huit bits bas sont rangés en haut du registre.
        Console console;
        console.display().set_status(Processor::main, status_command(100U));
        check(console.display().watched_line(Processor::main) == 100U, "ligne 100 guettée");
    }
    {   // Une ligne dont l'octet bas porte son bit de poids fort : le champ fait
        // bien huit bits, et non sept.
        Console console;
        console.display().set_status(Processor::main, status_command(200U));
        check(console.display().watched_line(Processor::main) == 200U, "ligne 200 guettée");
    }
    {   // Le neuvième bit est logé tout seul, bien plus bas : sans lui, aucune
        // ligne au-delà de 255 ne pourrait être guettée.
        Console console;
        console.display().set_status(Processor::main, status_command(260U));
        check(console.display().watched_line(Processor::main) == 260U, "ligne 260 guettée");
    }
    {   // L'indicateur de correspondance suit le compteur.
        Console console;
        console.display().set_status(Processor::main, status_command(5U));
        check(
            (console.display().status(Processor::main) & 0x0004U) == 0U,
            "pas de correspondance à la ligne 0"
        );
        console.advance_to(5U);
        check(
            (console.display().status(Processor::main) & 0x0004U) != 0U,
            "correspondance à la ligne guettée"
        );
        console.display().advance_line();
        check(
            (console.display().status(Processor::main) & 0x0004U) == 0U,
            "et plus à la suivante"
        );
    }
    {   // Chaque processeur guette la ligne qu'il veut.
        Console console;
        console.display().set_status(Processor::main, status_command(10U));
        console.display().set_status(Processor::secondary, status_command(20U));
        console.advance_to(10U);
        check(
            (console.display().status(Processor::main) & 0x0004U) != 0U,
            "le principal voit sa correspondance"
        );
        check(
            (console.display().status(Processor::secondary) & 0x0004U) == 0U,
            "et le secondaire ne voit pas la sienne"
        );
    }
}

void les_indicateurs_ne_s_ecrivent_pas() {
    Console console;
    // Le faisceau est écarté de la première ligne, sinon la correspondance de
    // ligne serait légitimement levée : la ligne guettée vaut zéro au départ, et
    // le faisceau y est. C'est le matériel qui veut cela, non le test.
    console.advance_to(5U);

    // Écrire les trois indicateurs ne doit rien retenir : le matériel les
    // impose, et les laisser écrire donnerait à un jeu le pouvoir de se mentir
    // sur la position du faisceau.
    console.display().set_status(Processor::main, 0x0007U);
    check(
        (console.display().status(Processor::main) & 0x0007U) == 0U,
        "les trois indicateurs restent au matériel"
    );

    // Les autorisations et la ligne guettée, elles, se relisent.
    console.display().set_status(Processor::main, status_command(3U, true, true, true));
    const auto value = console.display().status(Processor::main);
    check((value & 0x0038U) == 0x0038U, "les trois autorisations se relisent");
    check(console.display().watched_line(Processor::main) == 3U, "et la ligne guettée aussi");
}

void chaque_interruption_va_au_processeur_qui_l_a_demandee() {
    {   // Le retour vertical se pose une fois, au passage sur la ligne 192.
        Console console;
        console.display().set_status(Processor::main, status_command(0, true));
        console.advance_to(191U);
        check(console.main_interrupts.requested() == 0U, "rien avant la ligne 192");

        console.display().advance_line();
        check(
            (console.main_interrupts.requested() & 0x0001U) != 0U,
            "le retour vertical est demandé"
        );

        console.main_interrupts.acknowledge(0x0001U);
        console.display().advance_line();
        check(
            (console.main_interrupts.requested() & 0x0001U) == 0U,
            "et il ne se repose pas à chaque ligne suivante"
        );
    }
    {   // Sans autorisation, rien n'est posé.
        Console console;
        console.advance_to(192U);
        check(console.main_interrupts.requested() == 0U, "sans autorisation, pas de demande");
        check(console.secondary_interrupts.requested() == 0U, "des deux côtés");
    }
    {   // Chacun règle ses réveils sans toucher à ceux de l'autre.
        Console console;
        console.display().set_status(Processor::secondary, status_command(0, true));
        console.advance_to(192U);
        check(
            (console.secondary_interrupts.requested() & 0x0001U) != 0U,
            "le secondaire est réveillé"
        );
        check(console.main_interrupts.requested() == 0U, "le principal ne l'est pas");
    }
    {   // Le retour horizontal a lieu sur toutes les lignes, y compris celles
        // qu'on n'affiche pas : le faisceau les balaie tout de même.
        Console console;
        console.display().set_status(Processor::main, status_command(0, false, true));
        console.display().advance_line();
        check(
            (console.main_interrupts.requested() & 0x0002U) != 0U,
            "le retour horizontal est demandé"
        );

        console.main_interrupts.acknowledge(0x0002U);
        console.advance_to(200U);
        check(
            (console.main_interrupts.requested() & 0x0002U) != 0U,
            "et aussi pendant le retour vertical"
        );
    }
    {   // La ligne guettée réveille celui qui la guette, à sa ligne.
        Console console;
        console.display().set_status(Processor::main, status_command(80U, false, false, true));
        console.advance_to(79U);
        check(console.main_interrupts.requested() == 0U, "rien avant la ligne guettée");
        console.display().advance_line();
        check(
            (console.main_interrupts.requested() & 0x0004U) != 0U,
            "la correspondance de ligne est demandée"
        );
    }
    {   // Guetter une ligne sans autoriser l'interruption lève l'indicateur sans
        // réveiller : c'est ainsi qu'un logiciel scrute sans être interrompu.
        Console console;
        console.display().set_status(Processor::main, status_command(80U));
        console.advance_to(80U);
        check(
            (console.display().status(Processor::main) & 0x0004U) != 0U,
            "l'indicateur est levé"
        );
        check(console.main_interrupts.requested() == 0U, "mais personne n'est réveillé");
    }
}

void les_registres_du_balayage_repondent_par_les_deux_cartes() {
    Console console;

    // Adresses écrites littéralement, telles qu'un programme les écrirait.
    console.main_map.write16(0x0400'0004U, status_command(60U, true));
    check(
        console.display().watched_line(Processor::main) == 60U,
        "le principal règle sa ligne guettée par sa carte"
    );

    console.secondary_map.write16(0x0400'0004U, status_command(120U, true));
    check(
        console.display().watched_line(Processor::secondary) == 120U,
        "le secondaire règle la sienne par la sienne"
    );
    check(
        console.display().watched_line(Processor::main) == 60U,
        "sans toucher à celle du principal"
    );

    // Le compteur de lignes se lit des deux côtés, et c'est le même.
    console.advance_to(150U);
    check(console.main_map.read16(0x0400'0006U) == 150U, "le compteur se lit par la carte du principal");
    check(console.secondary_map.read16(0x0400'0006U) == 150U, "et par celle du secondaire");
    check(console.main_map.unimplemented_io_count() == 0U, "ces registres sont modélisés");
    check(console.secondary_map.unimplemented_io_count() == 0U, "des deux côtés");

    // L'état se relit entier, ses deux octets compris : la ligne guettée occupe
    // l'octet haut, et la lire par morceaux en perdrait la moitié. Et chaque
    // carte rend l'état de SON processeur, non celui de l'autre.
    check(
        console.main_map.read16(0x0400'0004U) == status_command(60U, true),
        "le principal relit son propre état, ses deux octets compris"
    );
    check(
        console.secondary_map.read16(0x0400'0004U) == status_command(120U, true),
        "et le secondaire le sien"
    );

    // Forcer le compteur n'est pas modélisé, et le dit.
    const auto before = console.main_map.unimplemented_io_count();
    console.main_map.write16(0x0400'0006U, 0U);
    check(
        console.main_map.unimplemented_io_count() > before,
        "forcer le compteur n'est pas modélisé"
    );
    check(console.display().line() == 150U, "et le faisceau n'a pas bougé");
}

/**
 * Le faisceau ne dessine que la ligne qu'il traverse.
 *
 * C'est ce qui sépare un balayage d'une capture : dessiner toute la trame d'un
 * coup effacerait tout changement survenu en cours de route, sans rien dire.
 */
void le_faisceau_ne_dessine_que_sa_ligne() {
    constexpr std::int32_t untouched = 0x0123'4567;

    Console console;
    std::vector<std::int32_t> framebuffer(
        static_cast<std::size_t>(256) * static_cast<std::size_t>(384), untouched);

    console.video.palette()[0] = 0x1fU;                  // rouge, pour le moteur principal
    console.video.engine(Engine::main).set_display_control(1U << 16U);
    console.video.engine(Engine::secondary).set_display_control(1U << 16U);

    console.advance_to(50U);
    console.display().render_current_line(framebuffer);

    check(static_cast<std::uint32_t>(framebuffer[50U * 256U]) == 0xffff'0000U, "la ligne balayée est dessinée");
    check(framebuffer[49U * 256U] == untouched, "celle d'avant est laissée telle quelle");
    check(framebuffer[51U * 256U] == untouched, "celle d'après aussi");
    // L'écran du bas reçoit la même ligne, du second moteur : c'est un seul
    // faisceau pour les deux écrans.
    check(framebuffer[(192U + 50U) * 256U] != untouched, "l'écran du bas reçoit la sienne au même instant");
    check(framebuffer[(192U + 49U) * 256U] == untouched, "et pas celle d'avant");

    // Les lignes qui ne s'affichent pas se balaient tout de même, mais ne se
    // dessinent nulle part.
    console.advance_to(200U);
    const auto before = framebuffer;
    console.display().render_current_line(framebuffer);
    check(framebuffer == before, "une ligne hors écran ne dessine rien");
}

void une_trame_remplit_les_deux_ecrans() {
    Console console;
    std::vector<std::int32_t> framebuffer(
        static_cast<std::size_t>(256) * static_cast<std::size_t>(384), 0);

    // Un fond distinct par moteur : c'est le plus court chemin pour savoir
    // lequel a dessiné quelle moitié du tampon.
    auto set_backdrop = [&console](Engine which, std::uint16_t colour) {
        const std::size_t base = which == Engine::main ? 0U : 1024U;
        console.video.palette()[base] = static_cast<std::uint8_t>(colour & 0xffU);
        console.video.palette()[base + 1U] = static_cast<std::uint8_t>(colour >> 8U);
    };
    set_backdrop(Engine::main, 0x001fU);        // rouge
    set_backdrop(Engine::secondary, 0x03e0U);   // vert

    // Les deux moteurs en mode graphique, sans plan allumé : le fond suffit.
    console.video.engine(Engine::main).set_display_control(1U << 16U);
    console.video.engine(Engine::secondary).set_display_control(1U << 16U);

    console.display().render_frame(framebuffer);

    check(static_cast<std::uint32_t>(framebuffer[0]) == 0xffff'0000U, "l'écran du haut au principal");
    check(
        static_cast<std::uint32_t>(framebuffer[191U * 256U + 255U]) == 0xffff'0000U,
        "jusqu'à son dernier pixel"
    );
    check(
        static_cast<std::uint32_t>(framebuffer[192U * 256U]) == 0xff00'ff00U,
        "l'écran du bas au secondaire"
    );
    check(
        static_cast<std::uint32_t>(framebuffer[383U * 256U + 255U]) == 0xff00'ff00U,
        "jusqu'à son dernier pixel"
    );

    // Le matériel peut échanger les deux écrans, et c'est un bit du registre
    // d'alimentation qui le décide, non une convention de ce code.
    console.main_map.write16(0x0400'0304U, 0x8000U);
    check(console.display().swapped(), "le bit d'échange est pris en compte");
    console.display().render_frame(framebuffer);
    check(
        static_cast<std::uint32_t>(framebuffer[0]) == 0xff00'ff00U,
        "après échange, le secondaire alimente l'écran du haut"
    );
    check(
        static_cast<std::uint32_t>(framebuffer[192U * 256U]) == 0xffff'0000U,
        "et le principal celui du bas"
    );

    // Les autres bits du registre se relisent sans rien commander : ils coupent
    // l'alimentation d'organes qui n'existent pas encore.
    console.main_map.write16(0x0400'0304U, 0x800fU);
    check(console.main_map.read16(0x0400'0304U) == 0x800fU, "le registre se relit entier");
    check(console.display().swapped(), "et l'échange tient toujours");
    console.main_map.write16(0x0400'0304U, 0x000fU);
    check(!console.display().swapped(), "retirer le bit défait l'échange");

    // Le registre d'alimentation appartient à la carte, non au matériel vidéo
    // partagé : c'est donc elle qui le remet à zéro.
    console.main_map.write16(0x0400'0304U, 0x800fU);
    console.main_map.reset();
    check(console.main_map.read16(0x0400'0304U) == 0U, "la carte remet l'alimentation à zéro");
}

void une_trame_trop_courte_n_est_pas_ecrite() {
    Console console;
    std::vector<std::int32_t> framebuffer(
        static_cast<std::size_t>(256) * static_cast<std::size_t>(384) - 1U, 0);
    console.video.palette()[0] = 0xffU;
    console.video.palette()[1] = 0x7fU;
    console.video.engine(Engine::main).set_display_control(1U << 16U);

    console.display().render_frame(framebuffer);
    check(framebuffer[0] == 0, "un tampon trop court est laissé intact");
}

void la_remise_a_zero_ramene_le_faisceau() {
    Console console;
    console.display().set_status(Processor::main, status_command(42U, true, true, true));
    console.display().set_status(Processor::secondary, status_command(7U));
    console.display().set_swapped(true);
    console.advance_to(100U);

    // La palette et les attributs d'objets appartiennent au matériel partagé :
    // les oublier laisserait les couleurs et les sprites d'une partie à la
    // suivante.
    console.video.palette()[0] = 0xffU;
    console.video.object_attributes()[0] = 0xffU;

    console.video.reset();
    check(console.video.palette()[0] == 0U, "la palette est effacée");
    check(console.video.object_attributes()[0] == 0U, "les attributs d'objets aussi");
    check(console.display().line() == 0U, "le faisceau repart de la première ligne");
    check(console.display().watched_line(Processor::main) == 0U, "les lignes guettées sont effacées");
    check(console.display().watched_line(Processor::secondary) == 0U, "des deux côtés");
    // Seules les autorisations sont effacées : la correspondance de ligne, elle,
    // est légitimement levée, puisque la ligne guettée vaut zéro et que le
    // faisceau y est revenu. C'est un état du matériel, pas un reste.
    check(
        (console.display().status(Processor::main) & 0x0038U) == 0U,
        "les autorisations du principal sont effacées"
    );
    check(
        (console.display().status(Processor::secondary) & 0x0038U) == 0U,
        "celles du secondaire aussi"
    );
    check(
        (console.display().status(Processor::main) & 0x0004U) != 0U,
        "et la ligne zéro guettée correspond au faisceau revenu à zéro"
    );
    check(!console.display().swapped(), "et les deux écrans reprennent leur place");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    le_balayage_a_les_nombres_du_materiel();
    le_compteur_de_lignes_boucle_sur_le_balayage();
    l_indicateur_de_retour_vertical_s_eteint_une_ligne_avant_la_fin();
    la_ligne_guettee_tient_sur_neuf_bits();
    les_indicateurs_ne_s_ecrivent_pas();
    chaque_interruption_va_au_processeur_qui_l_a_demandee();
    les_registres_du_balayage_repondent_par_les_deux_cartes();
    le_faisceau_ne_dessine_que_sa_ligne();
    une_trame_remplit_les_deux_ecrans();
    une_trame_trop_courte_n_est_pas_ecrite();
    la_remise_a_zero_ramene_le_faisceau();
    return 0;
}
