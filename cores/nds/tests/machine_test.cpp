#include "system/machine.hpp"
#include "system/registers.hpp"

#include <ravenemu/nds/core.hpp>

#include "check.hpp"

#include <array>
#include <cstdint>
#include <initializer_list>
#include <vector>

/**
 * L'ordonnanceur : la console assemblée, et ce qui la fait tourner.
 *
 * Quatre niveaux se succèdent. Le premier fige les quantités du balayage et le
 * budget qu'elles donnent à chaque processeur. Le deuxième vérifie que les deux
 * avancent **entrelacés** et non l'un après l'autre — c'est la seule différence
 * observable entre un ordonnanceur juste et un qui ne l'est pas, et elle ne se
 * voit qu'en faisant tourner deux programmes qui se parlent. Le troisième
 * éprouve l'arrêt et le réveil des deux processeurs, par leurs deux chemins
 * différents. Le quatrième dessine une trame entière et vérifie qu'un décor
 * changé en cours de route ne touche que les lignes qui suivent : c'est ce qui
 * distingue un balayage d'une capture.
 *
 * Les programmes sont encodés à la main, et les nombres du matériel écrits en
 * toutes lettres plutôt que repris des constantes du cœur.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

/**
 * Adresse d'entrée-sortie qu'aucun organe ne décodera jamais ici.
 *
 * Elle appartient au modèle DSi, hors du périmètre de ce cœur. Les exemples de
 * « registre inconnu » ont déjà dû être déplacés deux fois, parce que l'adresse
 * choisie finissait par être modélisée et désarmait silencieusement les
 * vérifications qui s'en servaient. Celle-ci ne le sera pas.
 */
constexpr std::uint32_t never_decoded_io = 0x0400'4000;

constexpr std::uint32_t main_ram_base = 0x0200'0000;
constexpr std::uint32_t private_wram_base = 0x0380'0000;
constexpr std::uint32_t io_base = 0x0400'0000;
constexpr std::uint32_t palette_base = 0x0500'0000;
/** L'état du balayage répond à la même adresse pour les deux processeurs. */
constexpr std::uint32_t display_status = 0x0400'0004;

constexpr std::uint32_t always = 0xeU;
constexpr std::uint32_t equal = 0x0U;
constexpr std::uint32_t not_equal = 0x1U;

/** `MOV Rd, #value ROR rotation`. */
constexpr std::uint32_t mov_immediate(
    std::uint32_t rd,
    std::uint32_t value,
    std::uint32_t rotation = 0U
) noexcept {
    return (always << 28U) | (1U << 25U) | (0xdU << 21U) | (rd << 12U) |
        ((rotation / 2U) << 8U) | value;
}

/** `ADD Rd, Rn, #value ROR rotation`. */
constexpr std::uint32_t add_immediate(
    std::uint32_t rd,
    std::uint32_t rn,
    std::uint32_t value,
    std::uint32_t rotation = 0U
) noexcept {
    return (always << 28U) | (1U << 25U) | (0x4U << 21U) | (rn << 16U) | (rd << 12U) |
        ((rotation / 2U) << 8U) | value;
}

/** `CMP Rn, #value`. */
constexpr std::uint32_t compare_immediate(std::uint32_t rn, std::uint32_t value) noexcept {
    return (always << 28U) | (1U << 25U) | (0xaU << 21U) | (1U << 20U) | (rn << 16U) | value;
}

/**
 * Branchement exprimé en instructions depuis celle qui branche.
 *
 * Le déplacement encodé en compte deux de moins : le compteur de programme a
 * déjà pris son avance de pipeline quand l'instruction s'exécute.
 */
constexpr std::uint32_t branch(std::int32_t words, std::uint32_t condition = always) noexcept {
    return (condition << 28U) | (0x5U << 25U) |
        (static_cast<std::uint32_t>(words - 2) & 0x00ff'ffffU);
}

/** `cond 01 I P U B W L Rn Rd offset`, pré-indexé sans réécriture. */
constexpr std::uint32_t transfer(
    bool load,
    bool byte_access,
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t offset = 0U
) noexcept {
    return (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) |
        (byte_access ? (1U << 22U) : 0U) | (load ? (1U << 20U) : 0U) |
        (rn << 16U) | (rd << 12U) | offset;
}

/** `MOV Rd, Rm`. */
constexpr std::uint32_t mov_register(std::uint32_t rd, std::uint32_t rm) noexcept {
    return (always << 28U) | (0xdU << 21U) | (rd << 12U) | rm;
}

/** `SUBS pc, lr, #4` : le retour d'un gestionnaire d'interruption. */
constexpr std::uint32_t return_from_interrupt() noexcept {
    return (always << 28U) | (1U << 25U) | (0x2U << 21U) | (1U << 20U) | (14U << 16U) |
        (15U << 12U) | 4U;
}

/**
 * `STRH Rd, [Rn, #offset]`, pré-indexé sans réécriture.
 *
 * Le déplacement d'un transfert de demi-mot ne tient que sur huit bits, coupés
 * en deux moitiés de part et d'autre du code d'opération : il faut donc une base
 * proche du registre visé.
 */
constexpr std::uint32_t store_halfword(std::uint32_t rn, std::uint32_t rd, std::uint32_t offset) noexcept {
    return (always << 28U) | (1U << 24U) | (1U << 23U) | (1U << 22U) | (rn << 16U) |
        (rd << 12U) | (((offset >> 4U) & 0xfU) << 8U) | (1U << 7U) | (1U << 5U) | (1U << 4U) |
        (offset & 0xfU);
}

/** `MCR p15, 0, Rd, c7, c0, 4` : l'attente d'interruption du processeur principal. */
constexpr std::uint32_t wait_for_interrupt() noexcept {
    return (always << 28U) | (0xeU << 24U) | (0U << 21U) | (7U << 16U) | (0U << 12U) |
        (15U << 8U) | (4U << 5U) | (1U << 4U) | 0U;
}

/** Une console montée, prête à faire tourner deux programmes. */
struct Assembled {
    Machine machine{};
    std::vector<std::int32_t> framebuffer;

    Assembled()
        : framebuffer(
              static_cast<std::size_t>(screen_width) * static_cast<std::size_t>(framebuffer_height),
              0
          ) {
        machine.reset();
        for (const auto side : {Processor::main, Processor::secondary}) {
            machine.core(side).state().cpsr = static_cast<std::uint32_t>(CpuMode::system);
        }
    }

    /** Dépose un programme et pointe le processeur dessus. */
    void load(Processor side, std::uint32_t address, std::initializer_list<std::uint32_t> program) {
        auto cursor = address;
        for (const auto word : program) {
            if (side == Processor::main) {
                machine.main_memory().write32(cursor, word);
            } else {
                machine.secondary_memory().write32(cursor, word);
            }
            cursor += 4U;
        }
        machine.core(side).state().registers[15] = address;
    }

    [[nodiscard]] std::uint32_t reg(Processor side, std::uint32_t index) noexcept {
        return machine.core(side).state().registers[index];
    }

    /** Avance le faisceau sans donner de temps aux processeurs. */
    void skip_to_line(std::uint32_t line) {
        for (std::uint32_t guard = 0; guard < 263U * 2U; ++guard) {
            if (machine.display().line() == line) return;
            machine.display().advance_line();
        }
        check(false, "la ligne visée est atteignable");
    }

    void run_line() { machine.run_line(framebuffer); }
    void run_frame() { machine.run_frame(framebuffer); }

    [[nodiscard]] std::int32_t pixel(std::uint32_t row, std::uint32_t column) const {
        return framebuffer[static_cast<std::size_t>(row) * static_cast<std::size_t>(screen_width) + column];
    }
};

/** Boucle qui compte : une instruction utile pour une instruction de retour. */
constexpr std::array<std::uint32_t, 2> counting_loop{{
    add_immediate(0U, 0U, 1U),
    branch(-1),
}};

// --------------------------------------------------------------------------

void les_quantites_sont_celles_du_materiel() {
    // Une ligne dure 355 points de six cycles : ce sont les nombres du matériel,
    // écrits ici plutôt que recalculés depuis les constantes qui les portent.
    check(Machine::cycles_per_line == 2130U, "une ligne dure 2130 cycles d'horloge maître");
    check(Machine::main_clock_multiplier == 2U, "le processeur principal bat deux fois plus vite");
    check(Machine::secondary_steps_per_line == 2130U, "le secondaire reçoit 2130 instructions par ligne");
    check(Machine::main_steps_per_line == 4260U, "et le principal le double");
}

/** Chacun reçoit son budget, et le faisceau avance d'une ligne. */
void chaque_processeur_recoit_son_temps() {
    Assembled console;
    console.load(Processor::main, main_ram_base, {counting_loop[0], counting_loop[1]});
    console.load(Processor::secondary, private_wram_base, {counting_loop[0], counting_loop[1]});

    console.run_line();

    // Une addition pour un branchement : le compte vaut la moitié du budget.
    check(console.reg(Processor::main, 0U) == 2130U, "le principal a exécuté 4260 instructions");
    check(console.reg(Processor::secondary, 0U) == 1065U, "le secondaire en a exécuté 2130");
    check(console.machine.display().line() == 1U, "et le faisceau a avancé d'une ligne");

    // Le rapport se lit directement, sans passer par les constantes : c'est lui
    // qui décide si les deux programmes restent en phase.
    check(
        console.reg(Processor::main, 0U) == 2U * console.reg(Processor::secondary, 0U),
        "le principal a fait exactement le double du secondaire"
    );

    // Chacun a lu son programme là où il est : celui du secondaire vit dans une
    // mémoire que l'autre ne voit pas.
    check(console.machine.core(Processor::main).unimplemented_count() == 0U, "aucune instruction inconnue côté principal");
    check(console.machine.core(Processor::secondary).unimplemented_count() == 0U, "ni côté secondaire");
}

/**
 * Les deux processeurs avancent entrelacés.
 *
 * C'est **la** vérification de ce lot. Un ordonnanceur qui ferait tourner le
 * principal pendant toute la ligne, puis le secondaire, passerait toutes les
 * autres : les registres finaux seraient les mêmes. Ici le principal attend une
 * case que le secondaire écrit, et il ne peut la voir que si l'autre a eu la
 * parole entretemps.
 */
void les_deux_processeurs_avancent_ensemble() {
    // Le déplacement d'un transfert tient sur douze bits : la case guettée reste
    // donc à portée de la base.
    constexpr std::uint32_t flag_offset = 0x800U;
    constexpr std::uint32_t flag = main_ram_base + flag_offset;

    Assembled console;

    // Le secondaire dépose une marque dans la mémoire que les deux partagent,
    // puis s'immobilise.
    console.load(Processor::secondary, private_wram_base, {
        mov_immediate(1U, 0x77U),
        mov_immediate(2U, main_ram_base >> 24U, 8U),
        transfer(false, false, 2U, 1U, flag_offset),
        branch(0),
    });

    // Le principal scrute cette case et ne repart que lorsqu'elle change.
    console.load(Processor::main, main_ram_base, {
        mov_immediate(2U, main_ram_base >> 24U, 8U),
        transfer(true, false, 2U, 1U, flag_offset),
        compare_immediate(1U, 0U),
        branch(-2, equal),
        mov_immediate(0U, 0x99U),
        branch(0),
    });

    console.run_line();

    check(console.reg(Processor::main, 1U) == 0x77U, "le principal a vu la marque du secondaire");
    check(console.reg(Processor::main, 0U) == 0x99U, "et il est sorti de son attente");
    check(
        console.machine.main_memory().read32(flag) == 0x77U,
        "la marque est bien passée par la mémoire partagée"
    );
}

/**
 * Le processeur principal s'arrête, et le retour vertical le relance.
 *
 * L'autorisation générale reste **fermée** : c'est ce qui distingue la reprise
 * de la prise d'interruption, et un programme de console coupe couramment cette
 * autorisation avant de s'arrêter.
 */
void le_principal_s_arrete_et_le_balayage_le_reveille() {
    Assembled console;
    console.load(Processor::main, main_ram_base, {
        wait_for_interrupt(),
        add_immediate(0U, 0U, 1U),
        branch(0),
    });

    console.machine.main_memory().write16(
        Arm9MemoryMap::display_status, DisplayController::vertical_blank_interrupt);
    console.machine.main_memory().write32(registers::interrupt_enable, InterruptController::vertical_blank);
    console.machine.main_memory().write8(registers::interrupt_master, 0U);

    console.skip_to_line(190U);
    console.run_line();
    check(console.machine.core(Processor::main).halted(), "le processeur s'est arrêté");
    check(console.reg(Processor::main, 0U) == 0U, "et n'a rien exécuté depuis");

    // Ligne 191 close : le faisceau entre dans le retour vertical.
    console.run_line();
    check(console.machine.display().line() == 192U, "le retour vertical commence");
    check(
        console.machine.interrupts(Processor::main).requested() == InterruptController::vertical_blank,
        "et la demande est posée"
    );

    console.run_line();
    check(!console.machine.core(Processor::main).halted(), "le processeur est reparti");
    check(console.reg(Processor::main, 0U) != 0U, "et il exécute de nouveau");
    check(
        console.machine.core(Processor::main).state().registers[15] != ArmCore::irq_vector,
        "sans prendre l'interruption, que l'autorisation générale retient"
    );
}

/** Le processeur secondaire s'arrête par son registre, faute de coprocesseur. */
void le_secondaire_s_arrete_par_son_registre() {
    Assembled console;
    console.load(Processor::secondary, private_wram_base, {
        mov_immediate(1U, 0x80U),
        mov_immediate(2U, io_base >> 24U, 8U),
        transfer(false, true, 2U, 1U, 0x301U),
        add_immediate(0U, 0U, 1U),
        branch(0),
    });

    console.machine.secondary_memory().write16(
        Arm7MemoryMap::display_status, DisplayController::vertical_blank_interrupt);
    console.machine.secondary_memory().write32(
        registers::interrupt_enable, InterruptController::vertical_blank);

    console.skip_to_line(190U);
    console.run_line();
    check(console.machine.core(Processor::secondary).halted(), "le processeur s'est arrêté");
    check(console.reg(Processor::secondary, 0U) == 0U, "avant d'avoir compté quoi que ce soit");
    check(
        console.machine.secondary_memory().unimplemented_io_count() == 0U,
        "et l'écriture n'a pas été prise pour un registre inconnu"
    );

    console.run_line();
    console.run_line();
    check(!console.machine.core(Processor::secondary).halted(), "le retour vertical le relance");
    check(console.reg(Processor::secondary, 0U) != 0U, "et il reprend où il en était");
}

/**
 * L'interruption du retour vertical est réellement prise, et son gestionnaire
 * tourne.
 *
 * C'est la vérification qui relie le balayage au processeur. Le gestionnaire
 * relève au passage le compteur de la boucle interrompue, ce qui date la prise à
 * l'instruction près : l'interruption se prend **au premier pas de la ligne qui
 * suit** le retour vertical, parce que la ligne est échantillonnée avant chaque
 * instruction et non après.
 */
void le_retour_vertical_est_pris_comme_interruption() {
    constexpr std::uint32_t handler_base = ArmCore::irq_vector;

    Assembled console;
    console.load(Processor::main, main_ram_base, {counting_loop[0], counting_loop[1]});

    // Le gestionnaire vit dans la mémoire locale d'instructions, seule mémoire
    // qui réponde à l'adresse des vecteurs.
    auto& cp15 = console.machine.cp15();
    cp15.write(0U, 9U, 1U, 1U, 5U << 1U);                        // seize kilooctets
    cp15.write(0U, 1U, 0U, 0U, Cp15::itcm_enable);

    const std::uint32_t handler[] = {
        mov_register(5U, 0U),                                    // relève le compteur
        mov_immediate(6U, io_base >> 24U, 8U),
        mov_immediate(7U, 1U),
        transfer(false, false, 6U, 7U, 0x214U),                  // acquitte la demande
        return_from_interrupt(),
    };
    for (std::uint32_t index = 0; index < std::size(handler); ++index) {
        static_cast<void>(cp15.store(handler_base + index * 4U, 4U, handler[index]));
    }

    auto& map = console.machine.main_memory();
    map.write16(display_status, DisplayController::vertical_blank_interrupt);
    map.write32(registers::interrupt_enable, InterruptController::vertical_blank);
    map.write8(registers::interrupt_master, 1U);

    console.skip_to_line(190U);
    console.run_line();
    console.run_line();
    check(console.reg(Processor::main, 5U) == 0U, "rien n'a encore interrompu la boucle");
    check(console.reg(Processor::main, 0U) == 4260U, "qui a tourné deux lignes durant");

    console.run_line();
    check(console.reg(Processor::main, 5U) == 4260U, "le gestionnaire a pris la main dès le premier pas");
    check(
        console.machine.interrupts(Processor::main).requested() == 0U,
        "et il a acquitté la demande"
    );
    check(console.reg(Processor::main, 0U) > 4260U, "puis la boucle a repris");
    check(console.machine.core(Processor::main).unimplemented_count() == 0U, "aucune instruction inconnue");
}

/**
 * Un message d'un processeur réveille l'autre.
 *
 * Le sens compte : c'est le principal qui envoie et le secondaire qui dort. Une
 * console dont les deux files aboutiraient au même contrôleur passerait le sens
 * inverse sans broncher, puisque la demande tomberait par hasard du bon côté.
 */
void un_message_reveille_l_autre_processeur() {
    constexpr std::uint32_t ready_flag = 0x800U;

    Assembled console;

    // Le secondaire demande à être prévenu, se déclare prêt, puis s'arrête.
    console.load(Processor::secondary, private_wram_base, {
        mov_immediate(2U, io_base >> 24U, 8U),
        mov_immediate(3U, main_ram_base >> 24U, 8U),
        add_immediate(5U, 2U, 0x60U, 30U),                       // base des files
        mov_immediate(1U, 0x21U, 22U),                           // files ouvertes, prévenir quand remplies
        store_halfword(5U, 1U, 0x4U),
        mov_immediate(1U, 4U, 16U),                              // source : file reçue remplie
        transfer(false, false, 2U, 1U, 0x210U),
        mov_immediate(1U, 1U),
        transfer(false, false, 3U, 1U, ready_flag),              // je suis prêt
        mov_immediate(1U, 0x80U),
        transfer(false, true, 2U, 1U, 0x301U),                   // arrêt
        add_immediate(0U, 0U, 1U),
        mov_immediate(6U, 0x41U, 12U),                           // file reçue
        transfer(true, false, 6U, 4U),
        branch(0),
    });

    // Le principal attend ce signal avant d'envoyer : il va deux fois plus vite
    // que l'autre, et sans cela il enverrait dans le vide.
    console.load(Processor::main, main_ram_base, {
        mov_immediate(2U, io_base >> 24U, 8U),
        mov_immediate(3U, main_ram_base >> 24U, 8U),
        transfer(true, false, 3U, 1U, ready_flag),
        compare_immediate(1U, 0U),
        branch(-2, equal),
        add_immediate(5U, 2U, 0x60U, 30U),                       // base des files
        mov_immediate(1U, 0x80U, 24U),                           // files ouvertes
        store_halfword(5U, 1U, 0x4U),
        mov_immediate(4U, 0x55U),
        transfer(false, false, 2U, 4U, 0x188U),                  // le message
        branch(0),
    });

    console.run_line();

    check(
        (console.machine.interrupts(Processor::secondary).requested() &
         InterruptController::ipc_receive_queue_filled) != 0U,
        "la demande est tombée du côté du destinataire"
    );
    check(!console.machine.core(Processor::secondary).halted(), "qui s'est réveillé");
    check(console.reg(Processor::secondary, 0U) == 1U, "et a repris son programme");
    check(console.reg(Processor::secondary, 4U) == 0x55U, "et lu le message, entier");
}

/** Les modes de ce registre que rien ne modélise sont comptés, non approchés. */
void les_autres_modes_du_registre_d_arret_sont_comptes() {
    Assembled console;
    auto& map = console.machine.secondary_memory();

    map.write8(Arm7MemoryMap::halt_control, 0x00U);
    check(map.unimplemented_io_count() == 0U, "une écriture sans mode ne fait rien");
    check(!map.take_halt_request(), "et n'arrête pas le processeur");

    map.write8(Arm7MemoryMap::halt_control, 0xc0U);              // mise en veille
    check(map.unimplemented_io_count() == 1U, "la mise en veille est comptée");
    check(map.first_unimplemented_io() == Arm7MemoryMap::halt_control, "et le registre visé retenu");
    check(!map.take_halt_request(), "elle n'est pas approchée par un arrêt");

    map.write8(Arm7MemoryMap::halt_control, 0x40U);              // mode Game Boy Advance
    check(map.unimplemented_io_count() == 2U, "le mode Game Boy Advance aussi");

    static_cast<void>(map.read8(Arm7MemoryMap::halt_control));
    check(map.unimplemented_io_count() == 3U, "et la lecture, dont rien ne dit ce qu'elle rend");

    map.write8(Arm7MemoryMap::halt_control, 0x80U);
    check(map.take_halt_request(), "seul l'arrêt arrête");
    check(!map.take_halt_request(), "et la demande ne se prend qu'une fois");
    check(map.unimplemented_io_count() == 3U, "sans rien compter de plus");
}

/**
 * Une trame se dessine au passage du faisceau.
 *
 * Le programme guette le compteur de lignes et change la couleur de fond à
 * mi-écran. Si la trame était dessinée d'un seul coup à la fin, l'écran entier
 * porterait la seconde couleur et le changement serait invisible.
 */
void une_trame_se_dessine_ligne_par_ligne() {
    constexpr std::uint32_t switch_line = 100U;
    // Bleu pur en cinq bits par composante, tel que la console range ses
    // couleurs : les cinq bits hauts.
    constexpr std::int32_t black = static_cast<std::int32_t>(0xff00'0000U);
    constexpr std::int32_t blue = static_cast<std::int32_t>(0xff00'00ffU);

    Assembled console;

    // Le moteur principal alimente l'écran : sans cela il reste éteint, et un
    // écran éteint est noir quoi que dise la palette.
    console.machine.main_memory().write32(Arm9MemoryMap::main_engine_base, 0x0001'0000U);

    console.load(Processor::main, main_ram_base, {
        mov_immediate(2U, io_base >> 24U, 8U),
        mov_immediate(3U, palette_base >> 24U, 8U),
        mov_immediate(4U, 0x1fU, 22U),                           // 0x7c00 : bleu pur
        transfer(true, true, 2U, 1U, 6U),                        // compteur de lignes
        compare_immediate(1U, switch_line),
        branch(-2, not_equal),
        transfer(false, false, 3U, 4U),                          // couleur de fond
        branch(0),
    });

    console.run_frame();

    check(console.pixel(0U, 0U) == black, "la première ligne garde la couleur d'origine");
    check(console.pixel(switch_line - 1U, 128U) == black, "la ligne d'avant aussi");
    check(console.pixel(switch_line, 0U) == blue, "la ligne du changement porte la nouvelle");
    check(console.pixel(191U, 255U) == blue, "et la dernière également");

    // L'écran du bas est alimenté par l'autre moteur, resté éteint : le
    // changement ne déborde pas d'un écran sur l'autre.
    check(console.pixel(192U + switch_line, 0U) == black, "l'écran du bas est resté noir");
}

/** Une trame couvre tout l'écran, où que le faisceau en soit. */
void une_trame_couvre_l_ecran_ou_que_soit_le_faisceau() {
    constexpr std::int32_t untouched = 0x0123'4567;

    Assembled console;
    console.skip_to_line(50U);
    for (auto& pixel : console.framebuffer) pixel = untouched;

    console.run_frame();

    for (std::size_t index = 0; index < console.framebuffer.size(); ++index) {
        if (console.framebuffer[index] != untouched) continue;
        check(false, "aucun pixel n'est resté vierge");
        return;
    }
    check(true, "les 263 lignes ont couvert les deux écrans");
    check(console.machine.display().line() == 50U, "et le faisceau est revenu là où il était");
}

/**
 * La remise à zéro rend la console à son état de mise sous tension.
 *
 * Chaque organe est sali avant, et vérifié après : un organe oublié dans la
 * remise à zéro laisse une console qui redémarre avec l'état de la partie
 * précédente, ce qui ne se voit qu'à la deuxième exécution.
 */
void la_remise_a_zero_rend_la_console_neuve() {
    Assembled console;
    auto& main_map = console.machine.main_memory();
    auto& secondary_map = console.machine.secondary_memory();

    console.load(Processor::main, main_ram_base, {counting_loop[0], counting_loop[1]});
    console.load(Processor::secondary, private_wram_base, {counting_loop[0], counting_loop[1]});

    // Les deux contrôleurs reçoivent une demande, par le retour horizontal que
    // toute ligne pose.
    for (auto* map : {static_cast<Bus*>(&main_map), static_cast<Bus*>(&secondary_map)}) {
        map->write16(display_status, DisplayController::horizontal_blank_interrupt);
        map->write32(registers::interrupt_enable, InterruptController::horizontal_blank);
        map->write8(registers::interrupt_master, 1U);
        map->write16(registers::sync, InterProcessor::sync_output_mask);
        static_cast<void>(map->read32(0x0f00'0000U));
        static_cast<void>(map->read8(never_decoded_io));
    }
    console.run_line();
    main_map.write16(Arm9MemoryMap::power_control, 0x8000U);
    secondary_map.write8(Arm7MemoryMap::halt_control, 0x80U);

    check(console.machine.interrupts(Processor::main).requested() != 0U, "une demande est bien en attente");
    check(main_map.unmapped_count() != 0U, "et une adresse inconnue bien comptée");

    console.machine.reset();

    check(console.machine.display().line() == 0U, "le faisceau repart de la première ligne");
    check(!console.machine.display().swapped(), "les écrans ne sont plus échangés");
    check(console.reg(Processor::main, 0U) == 0U, "le principal a oublié son compte");
    check(console.reg(Processor::secondary, 0U) == 0U, "le secondaire aussi");
    check(main_map.read32(main_ram_base) == 0U, "la mémoire principale est vide");
    check(secondary_map.read32(private_wram_base) == 0U, "celle du secondaire aussi");
    check(!secondary_map.take_halt_request(), "la demande d'arrêt est oubliée");

    for (const auto side : {Processor::main, Processor::secondary}) {
        auto& controller = console.machine.interrupts(side);
        check(controller.requested() == 0U, "les demandes en attente sont effacées");
        check(controller.enabled() == 0U, "les sources autorisées aussi");
        check(controller.master_enable() == 0U, "et l'autorisation générale");
        check(console.machine.link().read_sync(side) == 0U, "la liaison est vierge");
    }

    check(main_map.unmapped_count() == 0U, "le compte des adresses inconnues du principal repart de zéro");
    check(main_map.unimplemented_io_count() == 0U, "celui de ses registres inconnus aussi");
    check(secondary_map.unmapped_count() == 0U, "côté secondaire également");
    check(secondary_map.unimplemented_io_count() == 0U, "et ses registres inconnus");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_quantites_sont_celles_du_materiel();
    chaque_processeur_recoit_son_temps();
    les_deux_processeurs_avancent_ensemble();
    le_principal_s_arrete_et_le_balayage_le_reveille();
    le_secondaire_s_arrete_par_son_registre();
    le_retour_vertical_est_pris_comme_interruption();
    un_message_reveille_l_autre_processeur();
    les_autres_modes_du_registre_d_arret_sont_comptes();
    une_trame_se_dessine_ligne_par_ligne();
    une_trame_couvre_l_ecran_ou_que_soit_le_faisceau();
    la_remise_a_zero_rend_la_console_neuve();
    return 0;
}
