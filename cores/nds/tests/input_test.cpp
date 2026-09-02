#include "memory/arm7_memory_map.hpp"
#include "memory/arm9_memory_map.hpp"
#include "system/input.hpp"
#include "system/machine.hpp"

#include <ravenemu/nds/core.hpp>

#include "check.hpp"

#include <cstdint>
#include <vector>

/**
 * Les touches, et le réveil qu'elles peuvent poser.
 *
 * Deux points portent tout le reste. Le premier est que les registres sont
 * **actifs à zéro** : un émulateur qui l'oublierait donnerait une console dont
 * toutes les touches sont enfoncées en permanence, ce qui ne ressemble pas à une
 * panne mais à un jeu qui part tout seul. Le second est l'asymétrie entre les
 * deux processeurs : les dix touches se lisent des deux côtés, les deux touches
 * supplémentaires et le contact de l'écran tactile d'un seul.
 *
 * Les rangs de bits sont écrits en toutes lettres, non repris des constantes du
 * cœur : les comparer à ce qui les définit ne prouverait rien.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

constexpr std::uint32_t key_input = 0x0400'0130;
constexpr std::uint32_t key_control = 0x0400'0132;
constexpr std::uint32_t extra_key_input = 0x0400'0136;

/** La console montée, avec un tampon aux dimensions publiées. */
struct Console {
    Machine machine{};
    std::vector<std::int32_t> framebuffer;

    Console()
        : framebuffer(
              static_cast<std::size_t>(screen_width) * static_cast<std::size_t>(framebuffer_height),
              0
          ) {
        machine.reset();
    }

    void run_line() { machine.run_line(framebuffer); }
};

// --------------------------------------------------------------------------

void les_rangs_sont_ceux_du_materiel() {
    check(InputState::key_a == 1U << 0U, "la touche A occupe le premier bit");
    check(InputState::key_b == 1U << 1U, "B le deuxième");
    check(InputState::key_select == 1U << 2U, "Select le troisième");
    check(InputState::key_start == 1U << 3U, "Start le quatrième");
    check(InputState::key_right == 1U << 4U, "Droite le cinquième");
    check(InputState::key_left == 1U << 5U, "Gauche le sixième");
    check(InputState::key_up == 1U << 6U, "Haut le septième");
    check(InputState::key_down == 1U << 7U, "Bas le huitième");
    check(InputState::key_r == 1U << 8U, "R le neuvième");
    check(InputState::key_l == 1U << 9U, "L le dixième");

    check(InputState::extra_x == 1U << 0U, "X occupe le premier bit du registre du secondaire");
    check(InputState::extra_y == 1U << 1U, "Y le deuxième");
    check(InputState::extra_pen == 1U << 6U, "le contact tactile le septième");
    check(InputState::extra_lid == 1U << 7U, "et le couvercle le huitième");
}

/** Un bit à zéro veut dire enfoncée, et les bits sans emploi se lisent à un. */
void le_registre_est_actif_a_zero() {
    InputState input;
    check(input.key_register() == 0xffffU, "au repos, tous les bits sont posés");

    input.set_pressed(InputState::key_a, true);
    check(input.key_register() == 0xfffeU, "la touche enfoncée efface son bit");
    check(input.held() == 1U, "et la vue interne la donne active à un");

    input.set_pressed(InputState::key_l, true);
    check(input.key_register() == 0xfdfeU, "deux touches effacent deux bits");

    input.set_pressed(InputState::key_a, false);
    check(input.key_register() == 0xfdffU, "relâcher n'en repose qu'un");

    // Les six bits hauts n'ont pas de touche : rien ne les tire, ils restent
    // posés quoi qu'on demande.
    input.set_pressed(0xffffU, true);
    check(input.key_register() == 0xfc00U, "seuls les dix bits bas s'effacent");
}

/** Le registre du processeur secondaire suit la même convention. */
void le_registre_du_secondaire_suit_la_meme_regle() {
    InputState input;
    check(input.extra_register() == 0xffffU, "au repos, tous les bits sont posés");

    input.set_extra_pressed(InputState::extra_x, true);
    check(input.extra_register() == 0xfffeU, "X efface son bit");

    input.set_extra_pressed(InputState::extra_pen, true);
    check(input.extra_register() == 0xffbeU, "le contact tactile efface le sien");

    input.set_extra_pressed(0xffffU, true);
    check(input.extra_register() == 0xff3cU, "et les bits sans emploi restent posés");
}

/** Les dix touches se lisent des deux côtés, les autres d'un seul. */
void chaque_processeur_lit_ce_qu_il_doit_lire() {
    Console console;
    auto& input = console.machine.input();

    input.set_pressed(InputState::key_start, true);
    check(
        console.machine.main_memory().read16(key_input) == 0xfff7U,
        "le principal voit la touche enfoncée"
    );
    check(
        console.machine.secondary_memory().read16(key_input) == 0xfff7U,
        "et le secondaire la même"
    );

    input.set_extra_pressed(InputState::extra_y, true);
    check(
        console.machine.secondary_memory().read16(extra_key_input) == 0xfffdU,
        "le secondaire voit les touches supplémentaires"
    );

    // Le principal n'a pas ce registre : l'adresse ne mène nulle part chez lui.
    const auto before = console.machine.main_memory().unimplemented_io_count();
    static_cast<void>(console.machine.main_memory().read16(extra_key_input));
    check(
        console.machine.main_memory().unimplemented_io_count() > before,
        "chez le principal, cette adresse n'est pas décodée"
    );
}

/** Les touches ne s'écrivent pas : le matériel ignore. */
void les_touches_ne_s_ecrivent_pas() {
    Console console;
    console.machine.input().set_pressed(InputState::key_b, true);

    console.machine.main_memory().write16(key_input, 0xffffU);
    check(
        console.machine.main_memory().read16(key_input) == 0xfffdU,
        "l'écriture n'a pas relâché la touche"
    );
    check(
        console.machine.main_memory().unimplemented_io_count() == 0U,
        "et n'est pas comptée comme un registre inconnu : le matériel l'ignore"
    );
}

/** La condition du réveil : une touche suffit, ou il les faut toutes. */
void la_condition_du_reveil() {
    {
        KeyInterrupt setting;
        setting.set_control(static_cast<std::uint16_t>(0x0003U | (1U << 14U)));
        check(!setting.satisfied(0U), "sans touche enfoncée, rien");
        check(setting.satisfied(1U), "une des deux suffit");
        check(setting.satisfied(3U), "les deux aussi");
        check(!setting.satisfied(4U), "une touche hors de la sélection ne compte pas");
    }
    {
        KeyInterrupt setting;
        setting.set_control(static_cast<std::uint16_t>(0x0003U | (1U << 14U) | (1U << 15U)));
        check(!setting.satisfied(1U), "en mode combinaison, une seule ne suffit pas");
        check(setting.satisfied(3U), "il les faut toutes");
        check(setting.satisfied(7U), "d'autres en plus ne gênent pas");
    }
    {
        // Sans autorisation, la condition ne vaut rien.
        KeyInterrupt setting;
        setting.set_control(0x0003U);
        check(!setting.satisfied(3U), "sans autorisation, aucun réveil");
    }
    {
        // Une sélection vide ne réveille jamais : en mode combinaison, l'ensemble
        // vide serait sinon satisfait par n'importe quel état.
        KeyInterrupt setting;
        setting.set_control(static_cast<std::uint16_t>((1U << 14U) | (1U << 15U)));
        check(!setting.satisfied(0U), "sélection vide, aucun réveil");
        check(!setting.satisfied(0x3ffU), "même toutes touches enfoncées");
    }
    {
        // Les bits sans emploi ne se posent pas.
        KeyInterrupt setting;
        setting.set_control(0xffffU);
        check(setting.control() == 0xc3ffU, "seuls les bits du matériel se lisent");
    }
}

/** Chaque processeur règle le sien, et le réveil tombe du bon côté. */
void chaque_processeur_regle_son_reveil() {
    Console console;

    // Seul le secondaire demande à être réveillé par la touche A.
    console.machine.secondary_memory().write16(
        key_control, static_cast<std::uint16_t>(InputState::key_a | (1U << 14U)));
    check(
        console.machine.secondary_memory().read16(key_control) ==
            static_cast<std::uint16_t>(InputState::key_a | (1U << 14U)),
        "le réglage écrit se relit"
    );
    check(
        console.machine.main_memory().read16(key_control) == 0U,
        "et le principal ne l'a pas reçu"
    );

    console.run_line();
    check(
        console.machine.interrupts(Processor::secondary).requested() == 0U,
        "sans touche enfoncée, rien n'est posé"
    );

    console.machine.input().set_pressed(InputState::key_a, true);
    console.run_line();
    check(
        console.machine.interrupts(Processor::secondary).requested() == 1U << 12U,
        "le secondaire est réveillé par les touches, source de rang douze"
    );
    check(
        console.machine.interrupts(Processor::main).requested() == 0U,
        "et le principal n'a rien, ne l'ayant pas demandé"
    );
}

/**
 * Le réveil tient sur un niveau, non sur un front.
 *
 * Une touche gardée enfoncée redemande le réveil après chaque acquittement,
 * comme le fait le matériel : un jeu qui acquitte sans relire l'état ne doit pas
 * en conclure que la touche est relâchée.
 */
void le_reveil_tient_sur_un_niveau() {
    Console console;
    console.machine.main_memory().write16(
        key_control, static_cast<std::uint16_t>(InputState::key_b | (1U << 14U)));
    check(
        console.machine.main_memory().read16(key_control) ==
            static_cast<std::uint16_t>(InputState::key_b | (1U << 14U)),
        "le réglage du principal se relit aussi"
    );
    console.machine.input().set_pressed(InputState::key_b, true);

    console.run_line();
    check(console.machine.interrupts(Processor::main).requested() != 0U, "la demande est posée");

    console.machine.interrupts(Processor::main).acknowledge(0xffff'ffffU);
    console.run_line();
    check(
        console.machine.interrupts(Processor::main).requested() != 0U,
        "et revient tant que la touche est tenue"
    );

    console.machine.interrupts(Processor::main).acknowledge(0xffff'ffffU);
    console.machine.input().set_pressed(InputState::key_b, false);
    console.run_line();
    check(
        console.machine.interrupts(Processor::main).requested() == 0U,
        "relâchée, elle ne redemande plus rien"
    );
}

/**
 * Chaque touche de l'interface partagée atteint la bonne, et une seule.
 *
 * Deux touches qui se confondraient rendraient un jeu injouable sans que rien ne
 * le signale : ni le code ni l'image ne diraient quoi que ce soit.
 */
void chaque_touche_partagee_atteint_la_sienne() {
    check(InputState::key_for(Button::a) == 1U << 0U, "A");
    check(InputState::key_for(Button::b) == 1U << 1U, "B");
    check(InputState::key_for(Button::select) == 1U << 2U, "Select");
    check(InputState::key_for(Button::start) == 1U << 3U, "Start");
    check(InputState::key_for(Button::right) == 1U << 4U, "Droite");
    check(InputState::key_for(Button::left) == 1U << 5U, "Gauche");
    check(InputState::key_for(Button::up) == 1U << 6U, "Haut");
    check(InputState::key_for(Button::down) == 1U << 7U, "Bas");
    check(InputState::key_for(Button::r) == 1U << 8U, "R");
    check(InputState::key_for(Button::l) == 1U << 9U, "L");

    // Et l'ensemble couvre les dix bits : une correspondance qui en oublierait
    // une, ou en doublerait une autre, n'atteindrait pas ce total.
    std::uint16_t seen = 0;
    for (const auto button : {
             Button::up, Button::down, Button::left, Button::right, Button::a,
             Button::b, Button::start, Button::select, Button::l, Button::r}) {
        seen = static_cast<std::uint16_t>(seen | InputState::key_for(button));
    }
    check(seen == 0x03ffU, "les dix touches couvrent les dix bits, sans doublon");
}

/**
 * Une touche enfoncée par l'interface publique arrive jusqu'au registre.
 *
 * C'est la seule vérification qui traverse tout : elle fait démarrer une
 * cartouche dont le programme lit le registre des touches et le pose en fond
 * d'écran, si bien que la couleur de l'image dit ce que le programme a lu.
 */
void une_touche_enfoncee_arrive_jusqu_au_programme() {
    // Programme : allume le moteur, lit le registre des touches, le range dans
    // la palette, puis s'immobilise.
    constexpr std::uint32_t always = 0xeU;
    const std::uint32_t program[] = {
        (always << 28U) | (1U << 25U) | (0xdU << 21U) | (2U << 12U) | (4U << 8U) | 0x04U,
        (always << 28U) | (1U << 25U) | (0xdU << 21U) | (3U << 12U) | (4U << 8U) | 0x05U,
        (always << 28U) | (1U << 25U) | (0xdU << 21U) | (1U << 12U) | (8U << 8U) | 0x01U,
        (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) | (2U << 16U) | (1U << 12U),
        (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) | (1U << 20U) |
            (2U << 16U) | (1U << 12U) | 0x130U,
        (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) | (3U << 16U) | (1U << 12U),
        (always << 28U) | (0x5U << 25U) | 0x00ff'fffeU,
    };

    std::vector<std::uint8_t> image(0x8000, 0);
    const auto write_u32 = [&image](std::size_t offset, std::uint32_t value) {
        for (std::size_t byte = 0; byte < 4U; ++byte) {
            image[offset + byte] = static_cast<std::uint8_t>((value >> (byte * 8U)) & 0xffU);
        }
    };
    image[0x012] = static_cast<std::uint8_t>(UnitCode::nintendo_ds);
    write_u32(0x020, 0x4000);                    // le bloc du principal
    write_u32(0x024, 0x0200'0000);
    write_u32(0x028, 0x0200'0000);
    write_u32(0x02c, 0x400);
    write_u32(0x030, 0x6000);                    // celui du secondaire, vide
    write_u32(0x034, 0x0380'0000);
    write_u32(0x038, 0x0380'0000);
    write_u32(0x03c, 0x400);
    write_u32(0x080, 0x8000);
    write_u32(0x084, 0x4000);
    for (std::size_t index = 0; index < std::size(program); ++index) {
        write_u32(0x4000 + index * 4U, program[index]);
    }
    // Le secondaire s'immobilise sur place.
    write_u32(0x6000, (always << 28U) | (0x5U << 25U) | 0x00ff'fffeU);

    auto core = make_core();
    std::vector<std::int32_t> pixels(
        static_cast<std::size_t>(screen_width) * static_cast<std::size_t>(framebuffer_height), 0);

    core->load_rom(image, {});
    core->set_button(Button::a, true);
    core->run_frame(pixels, true);

    // Le registre vaut 0xfffe : tous les bits posés sauf celui de A. En couleur,
    // les cinq bits bas donnent le rouge, qui perd donc son bit de poids faible.
    constexpr std::uint32_t expected_red = (0x1eU << 3U) | (0x1eU >> 2U);
    check(
        static_cast<std::uint32_t>(pixels[0]) == (0xff00'0000U | (expected_red << 16U) | 0xffffU),
        "le programme a lu le registre avec la seule touche A enfoncée"
    );
}

/** La remise à zéro relâche tout. */
void la_remise_a_zero_relache_tout() {
    Console console;
    console.machine.input().set_pressed(InputState::key_a | InputState::key_l, true);
    console.machine.input().set_extra_pressed(InputState::extra_pen, true);
    console.machine.main_memory().write16(
        key_control, static_cast<std::uint16_t>(InputState::key_a | (1U << 14U)));

    console.machine.reset();

    check(console.machine.main_memory().read16(key_input) == 0xffffU, "toutes les touches sont relâchées");
    check(
        console.machine.secondary_memory().read16(extra_key_input) == 0xffffU,
        "les entrées du secondaire aussi"
    );
    check(console.machine.main_memory().read16(key_control) == 0U, "et le réglage du réveil est effacé");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_rangs_sont_ceux_du_materiel();
    le_registre_est_actif_a_zero();
    le_registre_du_secondaire_suit_la_meme_regle();
    chaque_processeur_lit_ce_qu_il_doit_lire();
    les_touches_ne_s_ecrivent_pas();
    la_condition_du_reveil();
    chaque_processeur_regle_son_reveil();
    le_reveil_tient_sur_un_niveau();
    chaque_touche_partagee_atteint_la_sienne();
    une_touche_enfoncee_arrive_jusqu_au_programme();
    la_remise_a_zero_relache_tout();
    return 0;
}
