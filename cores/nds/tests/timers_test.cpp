#include "memory/arm7_memory_map.hpp"
#include "memory/arm9_memory_map.hpp"
#include "system/cartridge.hpp"
#include "system/machine.hpp"
#include "system/registers.hpp"
#include "system/timers.hpp"

#include <ravenemu/nds/core.hpp>

#include "check.hpp"

#include <array>
#include <cstdint>
#include <initializer_list>
#include <vector>

/**
 * Les quatre minuteries d'un processeur.
 *
 * Trois niveaux se succèdent. Le premier éprouve une minuterie seule : ses
 * diviseurs, le reste de division qu'elle conserve, le rechargement repris au
 * débordement, et le fait que l'allumage est un front. Le deuxième éprouve
 * l'enchaînement, qui est la seule façon d'obtenir un compteur plus large que
 * seize bits. Le troisième les monte sous les cartes mémoire et sous
 * l'ordonnanceur, où ce qui compte est que **chaque processeur ait les siennes**
 * et qu'un débordement réveille le bon.
 *
 * Les nombres du matériel sont écrits en toutes lettres, non repris des
 * constantes du cœur.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

constexpr std::uint32_t timer_base = 0x0400'0100;

/** Commande d'une minuterie, champ par champ. */
[[nodiscard]] constexpr std::uint16_t command(
    std::uint32_t prescaler = 0,
    bool chained = false,
    bool interrupting = false,
    bool running = true
) noexcept {
    return static_cast<std::uint16_t>(
        (prescaler & 3U) | (chained ? 1U << 2U : 0U) | (interrupting ? 1U << 6U : 0U) |
        (running ? 1U << 7U : 0U)
    );
}

/** Une minuterie seule, avec son contrôleur d'interruptions. */
struct Bench {
    InterruptController interrupts{};
    Timers timers{interrupts};
};

// --------------------------------------------------------------------------

void les_diviseurs_sont_ceux_du_materiel() {
    check(Timers::count == 4U, "quatre minuteries par processeur");
    check(Timers::prescalers[0] == 1U, "le premier diviseur ne divise pas");
    check(Timers::prescalers[1] == 64U, "le deuxième divise par soixante-quatre");
    check(Timers::prescalers[2] == 256U, "le troisième par deux cent cinquante-six");
    check(Timers::prescalers[3] == 1024U, "le quatrième par mille vingt-quatre");
}

/** Une minuterie éteinte ne bouge pas ; allumée, elle compte le temps divisé. */
void une_minuterie_compte_le_temps_divise() {
    {
        Bench bench;
        bench.timers.advance(1000U);
        check(bench.timers.counter(0) == 0U, "une minuterie éteinte ne compte pas");
    }
    {
        Bench bench;
        bench.timers.set_control(0, command());
        bench.timers.advance(100U);
        check(bench.timers.counter(0) == 100U, "sans diviseur, un cycle vaut un pas");
    }
    {
        Bench bench;
        bench.timers.set_control(0, command(1));                 // divise par 64
        bench.timers.advance(64U * 5U);
        check(bench.timers.counter(0) == 5U, "avec le deuxième diviseur, soixante-quatre cycles valent un pas");
    }
    {
        Bench bench;
        bench.timers.set_control(0, command(3));                 // divise par 1024
        bench.timers.advance(1024U * 3U);
        check(bench.timers.counter(0) == 3U, "et mille vingt-quatre cycles avec le quatrième");
    }
}

/**
 * Le reste de la division est conservé d'un pas à l'autre.
 *
 * Le jeter ferait dériver une minuterie lente d'autant plus vite qu'on
 * l'interroge souvent, et cette dérive resterait invisible jusqu'au jour où un
 * jeu compte dessus.
 */
void le_reste_de_division_est_conserve() {
    Bench bench;
    bench.timers.set_control(0, command(1));                     // divise par 64

    // Soixante-trois cycles ne font aucun pas ; le soixante-quatrième le fait.
    for (std::uint32_t step = 0; step < 63U; ++step) bench.timers.advance(1U);
    check(bench.timers.counter(0) == 0U, "soixante-trois cycles ne suffisent pas à un pas");
    bench.timers.advance(1U);
    check(bench.timers.counter(0) == 1U, "le soixante-quatrième le complète");

    // Et le reste ne repart pas de zéro à chaque appel : cent appels de dix
    // cycles font mille cycles, donc quinze pas et non zéro.
    Bench other;
    other.timers.set_control(0, command(1));
    for (std::uint32_t step = 0; step < 100U; ++step) other.timers.advance(10U);
    check(other.timers.counter(0) == 1000U / 64U, "mille cycles en cent appels font quinze pas");
}

/** L'allumage recharge le compteur, et c'est un front. */
void l_allumage_recharge_le_compteur() {
    Bench bench;
    bench.timers.set_reload(0, 0xff00U);
    check(bench.timers.counter(0) == 0U, "écrire le rechargement ne déplace pas le compteur");

    bench.timers.set_control(0, command());
    check(bench.timers.counter(0) == 0xff00U, "l'allumage reprend le rechargement");

    bench.timers.advance(0x10U);
    check(bench.timers.counter(0) == 0xff10U, "puis le compteur avance");

    // Réécrire la commande sur une minuterie déjà allumée ne recharge rien : un
    // jeu qui change son diviseur en cours de route ne voit pas son temps
    // repartir de zéro.
    bench.timers.set_reload(0, 0x1000U);
    bench.timers.set_control(0, command(1));
    check(bench.timers.counter(0) == 0xff10U, "réécrire la commande ne recharge pas");

    // L'éteindre puis la rallumer, en revanche, est un nouveau front.
    bench.timers.set_control(0, command(0, false, false, false));
    check(bench.timers.counter(0) == 0xff10U, "éteindre ne change pas le compteur");
    bench.timers.set_control(0, command());
    check(bench.timers.counter(0) == 0x1000U, "rallumer reprend le rechargement courant");
}

/** Au débordement, le compteur repart du rechargement, non de zéro. */
void le_debordement_reprend_le_rechargement() {
    {
        Bench bench;
        bench.timers.set_reload(0, 0xfff0U);
        bench.timers.set_control(0, command());
        bench.timers.advance(0x10U);
        check(bench.timers.counter(0) == 0xfff0U, "le compteur est revenu au rechargement");
        check(bench.timers.overflow_count(0) == 1U, "après un débordement");
    }
    {
        // Un rechargement haut donne une période courte : le compteur déborde
        // plusieurs fois dans le même pas, et tous les débordements comptent.
        Bench bench;
        bench.timers.set_reload(0, 0xfff0U);                     // période de seize pas
        bench.timers.set_control(0, command());
        bench.timers.advance(0x10U + 16U * 3U);
        check(bench.timers.overflow_count(0) == 4U, "quatre débordements dans le même pas");
        check(bench.timers.counter(0) == 0xfff0U, "et le compteur retombe juste");
    }
    {
        // Rechargement nul : la période est l'étendue entière du compteur.
        Bench bench;
        bench.timers.set_control(0, command());
        bench.timers.advance(0x1'0000U + 5U);
        check(bench.timers.overflow_count(0) == 1U, "un seul débordement sur toute l'étendue");
        check(bench.timers.counter(0) == 5U, "et le compteur repart de zéro");
    }
}

/** Le débordement réveille, mais seulement si on l'a demandé. */
void le_debordement_leve_une_interruption() {
    {
        Bench bench;
        // Un rechargement de 0xffff donne une période d'un seul pas.
        bench.timers.set_reload(0, 0xffffU);
        bench.timers.set_control(0, command(0, false, false));
        bench.timers.advance(1U);
        check(bench.timers.overflow_count(0) == 1U, "la minuterie a débordé");
        check(bench.interrupts.requested() == 0U, "sans demander de réveil, rien n'est posé");
    }
    {
        Bench bench;
        bench.timers.set_reload(0, 0xffffU);
        bench.timers.set_control(0, command(0, false, true));
        bench.timers.advance(1U);
        check(bench.interrupts.requested() == 1U << 3U, "la première minuterie pose la source de rang trois");
    }
    {
        // Chaque minuterie a la sienne, et elles se suivent.
        Bench bench;
        for (std::size_t index = 0; index < 4U; ++index) {
            bench.timers.set_reload(index, 0xffffU);
            bench.timers.set_control(index, command(0, false, true));
        }
        bench.timers.advance(1U);
        check(bench.interrupts.requested() == 0x78U, "les quatre sources occupent les rangs trois à six");
    }
}

/**
 * L'enchaînement : compter les débordements du prédécesseur plutôt que le temps.
 *
 * C'est ainsi qu'on obtient un compteur plus large que seize bits, et c'est
 * courant. La première minuterie n'a pas de prédécesseur.
 */
void l_enchainement_compte_les_debordements() {
    {
        Bench bench;
        bench.timers.set_reload(0, 0xfffeU);                     // un pas avant le débordement
        bench.timers.set_control(0, command());
        bench.timers.set_control(1, command(0, true));

        bench.timers.advance(1U);
        check(bench.timers.counter(0) == 0xffffU, "la première est à un pas du débordement");
        check(bench.timers.counter(1) == 0U, "et l'enchaînée n'a pas bougé");

        bench.timers.advance(1U);
        check(bench.timers.overflow_count(0) == 1U, "la première déborde");
        check(bench.timers.counter(1) == 1U, "et l'enchaînée avance d'un pas, dans le même pas de temps");
    }
    {
        // Une minuterie enchaînée ignore son propre diviseur : elle ne compte
        // pas le temps du tout.
        Bench bench;
        bench.timers.set_control(1, command(0, true));
        bench.timers.advance(100'000U);
        check(bench.timers.counter(1) == 0U, "une enchaînée sans prédécesseur qui déborde ne bouge pas");
    }
    {
        // La première n'a pas de prédécesseur : le bit est sans effet chez elle,
        // et elle continue de compter le temps.
        Bench bench;
        bench.timers.set_control(0, command(0, true));
        bench.timers.advance(50U);
        check(bench.timers.counter(0) == 50U, "la première compte le temps malgré le bit d'enchaînement");
    }
    {
        // Une chaîne de trois : le débordement se propage de proche en proche
        // dans le même pas.
        Bench bench;
        // Les deux premières débordent à chaque pas ; la troisième compte.
        bench.timers.set_reload(0, 0xffffU);
        bench.timers.set_reload(1, 0xffffU);
        bench.timers.set_control(0, command());
        bench.timers.set_control(1, command(0, true));
        bench.timers.set_control(2, command(0, true));
        bench.timers.advance(1U);
        check(bench.timers.overflow_count(0) == 1U, "la première déborde");
        check(bench.timers.overflow_count(1) == 1U, "la deuxième aussi, du même coup");
        check(bench.timers.counter(2) == 1U, "et la troisième en a compté un");
    }
}

/** Une minuterie éteinte ne propage rien, et rompt la chaîne. */
void une_minuterie_eteinte_rompt_la_chaine() {
    {
        Bench bench;
        bench.timers.set_reload(0, 0xffffU);
        bench.timers.set_control(0, command(0, false, false, false));  // éteinte
        bench.timers.set_control(1, command(0, true));
        bench.timers.advance(1000U);
        check(bench.timers.counter(1) == 0U, "rien ne remonte d'une minuterie éteinte");
    }
    {
        // Une minuterie éteinte au milieu d'une chaîne ne se laisse pas
        // enjamber : ce qui déborde avant elle ne parvient pas après elle.
        Bench bench;
        bench.timers.set_reload(0, 0xffffU);
        bench.timers.set_control(0, command());                        // déborde à chaque pas
        bench.timers.set_control(1, command(0, true, false, false));   // éteinte
        bench.timers.set_control(2, command(0, true));
        bench.timers.advance(4U);
        check(bench.timers.overflow_count(0) == 4U, "la première déborde bien");
        check(bench.timers.counter(2) == 0U, "et la troisième ne voit rien passer");
    }
}

/** Sans débordement, aucun réveil, même quand il est demandé. */
void sans_debordement_aucun_reveil() {
    Bench bench;
    bench.timers.set_control(0, command(0, false, true));
    bench.timers.advance(100U);
    check(bench.timers.counter(0) == 100U, "la minuterie a compté");
    check(bench.timers.overflow_count(0) == 0U, "sans déborder");
    check(bench.interrupts.requested() == 0U, "et rien n'est posé");
}

/** Les registres répondent où il faut, et ne disent pas la même chose des deux côtés. */
void les_registres_repondent_aux_bonnes_adresses() {
    SystemMemory system;
    InterruptController main_interrupts;
    InterruptController secondary_interrupts;
    InterProcessor link{main_interrupts, secondary_interrupts};
    InputState input{};
    VideoSystem video{main_interrupts, secondary_interrupts};
    Cartridge cartridge{main_interrupts, secondary_interrupts};
    Arm9MemoryMap map{system, video, link, main_interrupts, input, cartridge};
    system.reset();
    video.reset();
    map.reset();

    // Quatre minuteries, quatre octets chacune, à partir de 0x04000100.
    check(timer_base == 0x0400'0100U, "les minuteries commencent à 0x04000100");

    for (std::uint32_t slot = 0; slot < 4U; ++slot) {
        const auto base = timer_base + slot * 4U;
        map.write16(base, static_cast<std::uint16_t>(0x1000U + slot));
        map.write16(base + 2U, command());
        check(map.timers().reload(slot) == 0x1000U + slot, "le registre bas écrit le rechargement");
        check(map.read16(base) == 0x1000U + slot, "et l'allumage a repris ce rechargement dans le compteur");
        check(map.read16(base + 2U) == command(), "la commande se relit");
    }

    map.timers().advance(4U);
    check(map.read16(timer_base) == 0x1004U, "la lecture du registre bas rend le compteur");
    check(map.timers().reload(0) == 0x1000U, "et le rechargement n'a pas bougé");

    // Les bits sans emploi ne se posent pas.
    map.write16(timer_base + 2U, 0xffffU);
    check(
        map.read16(timer_base + 2U) == (command(3, true, true) | 0U),
        "seuls les bits du matériel se lisent"
    );

    check(map.unimplemented_io_count() == 0U, "aucune adresse de minuterie n'est inconnue");
}

/** Chaque processeur a les siennes, et l'ordonnanceur les fait avancer toutes deux. */
void chaque_processeur_a_ses_minuteries() {
    Machine machine;
    machine.reset();

    auto& main_map = machine.main_memory();
    auto& secondary_map = machine.secondary_memory();

    // Le principal règle la sienne, le secondaire pas : ce que l'un écrit ne
    // doit pas apparaître chez l'autre.
    main_map.write16(timer_base + 2U, command());
    check(secondary_map.read16(timer_base + 2U) == 0U, "le secondaire n'a pas reçu cette commande");

    std::vector<std::int32_t> framebuffer(
        static_cast<std::size_t>(screen_width) * static_cast<std::size_t>(framebuffer_height), 0);
    machine.run_line(framebuffer);

    // Une ligne dure 2130 cycles d'horloge maître, la même pour les deux.
    check(main_map.read16(timer_base) == 2130U, "la minuterie du principal a compté une ligne");
    check(secondary_map.read16(timer_base) == 0U, "celle du secondaire, éteinte, n'a rien compté");

    secondary_map.write16(timer_base + 2U, command());
    machine.run_line(framebuffer);
    check(secondary_map.read16(timer_base) == 2130U, "allumée, elle compte la ligne suivante");
    check(main_map.read16(timer_base) == 4260U, "et celle du principal continue");
}

/** Un débordement réveille le processeur qui l'a demandé, et lui seul. */
void un_debordement_reveille_le_bon_processeur() {
    Machine machine;
    machine.reset();
    std::vector<std::int32_t> framebuffer(
        static_cast<std::size_t>(screen_width) * static_cast<std::size_t>(framebuffer_height), 0);

    // Le secondaire seul demande à être réveillé par sa première minuterie. Le
    // rechargement laisse 2048 pas avant le débordement, moins que les 2130
    // cycles d'une ligne : une seule ligne suffit donc à le franchir.
    machine.secondary_memory().write16(timer_base, 0xf800U);
    machine.secondary_memory().write16(timer_base + 2U, command(0, false, true));
    machine.main_memory().write16(timer_base, 0xf800U);
    machine.main_memory().write16(timer_base + 2U, command(0, false, false));

    machine.run_line(framebuffer);

    check(
        machine.interrupts(Processor::secondary).requested() == 1U << 3U,
        "le secondaire a sa demande de minuterie"
    );
    check(
        machine.interrupts(Processor::main).requested() == 0U,
        "et le principal n'a rien, ne l'ayant pas demandé"
    );
}

/** La remise à zéro d'une carte efface ses minuteries. */
void la_remise_a_zero_efface_les_minuteries() {
    Machine machine;
    machine.reset();
    auto& map = machine.main_memory();

    map.write16(timer_base, 0x1234U);
    map.write16(timer_base + 2U, command(2, true, true));
    map.timers().advance(4096U);
    check(map.read16(timer_base) != 0x1234U, "la minuterie a bougé");

    map.reset();
    check(map.read16(timer_base) == 0U, "le compteur repart de zéro");
    check(map.read16(timer_base + 2U) == 0U, "la commande aussi");
    check(map.timers().reload(0) == 0U, "et le rechargement");

    // Le secondaire a les siennes, et sa carte doit les effacer aussi.
    auto& other = machine.secondary_memory();
    other.write16(timer_base, 0x4321U);
    other.write16(timer_base + 2U, command(1, false, true));
    other.timers().advance(4096U);
    check(other.read16(timer_base) != 0x4321U, "la minuterie du secondaire a bougé");

    other.reset();
    check(other.read16(timer_base) == 0U, "son compteur repart de zéro");
    check(other.read16(timer_base + 2U) == 0U, "sa commande aussi");
    check(other.timers().reload(0) == 0U, "et son rechargement");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_diviseurs_sont_ceux_du_materiel();
    une_minuterie_compte_le_temps_divise();
    le_reste_de_division_est_conserve();
    l_allumage_recharge_le_compteur();
    le_debordement_reprend_le_rechargement();
    le_debordement_leve_une_interruption();
    l_enchainement_compte_les_debordements();
    une_minuterie_eteinte_rompt_la_chaine();
    sans_debordement_aucun_reveil();
    les_registres_repondent_aux_bonnes_adresses();
    chaque_processeur_a_ses_minuteries();
    un_debordement_reveille_le_bon_processeur();
    la_remise_a_zero_efface_les_minuteries();
    return 0;
}
