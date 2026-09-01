#include "memory/arm7_memory_map.hpp"
#include "memory/arm9_memory_map.hpp"
#include "system/dma.hpp"
#include "system/machine.hpp"
#include "system/registers.hpp"

#include <ravenemu/nds/core.hpp>

#include "check.hpp"

#include <cstdint>
#include <vector>

/**
 * Les quatre canaux de transfert autonome d'un processeur.
 *
 * Trois niveaux se succèdent. Le premier éprouve un transfert pour lui-même :
 * les deux largeurs d'unité, les trois façons dont chaque adresse évolue, le
 * compte nul qui demande l'étendue entière. Le deuxième éprouve **quand** un
 * canal part, ce qui est le point délicat : à l'allumage, au retour vertical, au
 * retour horizontal, et le comptage franc des moments dont l'organe n'existe pas
 * encore. Le troisième le monte sous l'ordonnanceur, où ce qui compte est qu'un
 * transfert armé ait lieu **entre deux instructions** et qu'un canal répété
 * revienne à chaque trame.
 *
 * Les nombres du matériel sont écrits en toutes lettres.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

constexpr std::uint32_t dma_base = 0x0400'00b0;
constexpr std::uint32_t main_ram_base = 0x0200'0000;
constexpr std::uint32_t palette_base = 0x0500'0000;

/** Commande d'un canal, champ par champ, telle qu'elle occupe le demi-mot haut. */
[[nodiscard]] constexpr std::uint32_t command(
    std::uint32_t destination_mode = 0,
    std::uint32_t source_mode = 0,
    bool repeating = false,
    bool words = false,
    std::uint32_t moment = 0,
    bool interrupting = false,
    bool running = true
) noexcept {
    return ((destination_mode & 3U) << 5U) | ((source_mode & 3U) << 7U) |
        (repeating ? 1U << 9U : 0U) | (words ? 1U << 10U : 0U) | ((moment & 7U) << 11U) |
        (interrupting ? 1U << 14U : 0U) | (running ? 1U << 15U : 0U);
}

/** Registre de compte et de commande, tel qu'il tient dans un mot. */
[[nodiscard]] constexpr std::uint32_t control_word(
    std::uint32_t units,
    std::uint32_t order
) noexcept {
    return units | (order << 16U);
}

/**
 * Mémoire plate, pour éprouver les comptes sans monter la console.
 *
 * Les plus longs transferts se comptent en centaines de milliers d'unités : les
 * faire passer par la carte mémoire ne dirait rien de plus sur le compte, et
 * coûterait à chaque vérification.
 */
class FlatMemory final : public Bus {
public:
    explicit FlatMemory(std::size_t bytes) : cells_(bytes, 0) {}

    [[nodiscard]] std::uint8_t read8(std::uint32_t address) override { return at(address); }
    [[nodiscard]] std::uint16_t read16(std::uint32_t address) override {
        return static_cast<std::uint16_t>(at(address) | (at(address + 1U) << 8U));
    }
    [[nodiscard]] std::uint32_t read32(std::uint32_t address) override {
        return static_cast<std::uint32_t>(read16(address)) |
            (static_cast<std::uint32_t>(read16(address + 2U)) << 16U);
    }

    void write8(std::uint32_t address, std::uint8_t value) override { at(address) = value; }
    void write16(std::uint32_t address, std::uint16_t value) override {
        at(address) = static_cast<std::uint8_t>(value & 0xffU);
        at(address + 1U) = static_cast<std::uint8_t>(value >> 8U);
    }
    void write32(std::uint32_t address, std::uint32_t value) override {
        write16(address, static_cast<std::uint16_t>(value & 0xffffU));
        write16(address + 2U, static_cast<std::uint16_t>(value >> 16U));
    }

private:
    /** Les adresses se replient : une mémoire de test n'a pas de bord. */
    [[nodiscard]] std::uint8_t& at(std::uint32_t address) {
        return cells_[static_cast<std::size_t>(address) % cells_.size()];
    }

    std::vector<std::uint8_t> cells_;
};

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

    [[nodiscard]] Arm9MemoryMap& main() noexcept { return machine.main_memory(); }
    [[nodiscard]] Arm7MemoryMap& secondary() noexcept { return machine.secondary_memory(); }

    /** Programme un canal du processeur principal. */
    void program(
        std::uint32_t channel,
        std::uint32_t from,
        std::uint32_t to,
        std::uint32_t control
    ) {
        const auto base = dma_base + channel * 12U;
        main().write32(base, from);
        main().write32(base + 4U, to);
        main().write32(base + 8U, control);
    }

    void run_line() { machine.run_line(framebuffer); }
};

// --------------------------------------------------------------------------

void les_quantites_sont_celles_du_materiel() {
    check(DmaController::count == 4U, "quatre canaux par processeur");
    check(DmaController::channel_bytes == 12U, "douze octets par canal : deux adresses et un compte");
    check(dma_base == 0x0400'00b0U, "les canaux commencent à 0x040000b0");
}

/** Un transfert immédiat a lieu dès l'instruction suivante. */
void un_transfert_immediat_copie_la_memoire() {
    Console console;
    for (std::uint32_t index = 0; index < 8U; ++index) {
        console.main().write16(main_ram_base + index * 2U, static_cast<std::uint16_t>(0x1000U + index));
    }

    console.program(0, main_ram_base, main_ram_base + 0x100U, control_word(8U, command()));
    check(
        console.main().read16(main_ram_base + 0x100U) == 0U,
        "rien n'a encore bougé : le canal est armé, pas exécuté"
    );

    console.main().dma().run(console.main());

    for (std::uint32_t index = 0; index < 8U; ++index) {
        check(
            console.main().read16(main_ram_base + 0x100U + index * 2U) == 0x1000U + index,
            "les huit demi-mots sont arrivés dans l'ordre"
        );
    }
    check(
        console.main().read16(main_ram_base + 0x110U) == 0U,
        "et rien au-delà du compte demandé"
    );

    // Le canal s'éteint de lui-même une fois servi : sans répétition, il ne
    // repart pas.
    check(
        (console.main().read32(dma_base + 8U) & (1U << 31U)) == 0U,
        "le canal s'est éteint après son transfert"
    );
    check(!console.main().dma().pending(), "et plus rien n'attend");
}

/** Les deux largeurs d'unité, et le compte qui s'entend en unités. */
void les_deux_largeurs_d_unite() {
    Console console;
    console.main().write32(main_ram_base, 0xaabb'ccddU);
    console.main().write32(main_ram_base + 4U, 0x1122'3344U);

    // En demi-mots : deux unités font quatre octets.
    console.program(0, main_ram_base, main_ram_base + 0x100U, control_word(2U, command()));
    console.main().dma().run(console.main());
    check(console.main().read32(main_ram_base + 0x100U) == 0xaabb'ccddU, "deux demi-mots font un mot");
    check(console.main().read32(main_ram_base + 0x104U) == 0U, "et pas davantage");

    // En mots : deux unités font huit octets.
    console.program(1, main_ram_base, main_ram_base + 0x200U, control_word(2U, command(0, 0, false, true)));
    console.main().dma().run(console.main());
    check(console.main().read32(main_ram_base + 0x200U) == 0xaabb'ccddU, "le premier mot est passé");
    check(console.main().read32(main_ram_base + 0x204U) == 0x1122'3344U, "le second aussi");
}

/** Chaque adresse évolue comme sa commande le dit. */
void chaque_adresse_evolue_a_sa_facon() {
    {   // Arrivée figée : tout s'empile au même endroit, et la dernière reste.
        Console console;
        for (std::uint32_t index = 0; index < 4U; ++index) {
            console.main().write16(main_ram_base + index * 2U, static_cast<std::uint16_t>(index + 1U));
        }
        console.program(0, main_ram_base, main_ram_base + 0x100U, control_word(4U, command(2)));
        console.main().dma().run(console.main());
        check(console.main().read16(main_ram_base + 0x100U) == 4U, "l'arrivée figée garde la dernière unité");
        check(console.main().read16(main_ram_base + 0x102U) == 0U, "et rien n'a débordé à côté");
    }
    {   // Départ figé : la même unité est recopiée partout.
        Console console;
        console.main().write16(main_ram_base, 0x2222U);
        console.program(0, main_ram_base, main_ram_base + 0x100U, control_word(3U, command(0, 2)));
        console.main().dma().run(console.main());
        for (std::uint32_t index = 0; index < 3U; ++index) {
            check(
                console.main().read16(main_ram_base + 0x100U + index * 2U) == 0x2222U,
                "le départ figé recopie la même unité"
            );
        }
    }
    {   // Arrivée décroissante : l'ordre s'inverse.
        Console console;
        for (std::uint32_t index = 0; index < 4U; ++index) {
            console.main().write16(main_ram_base + index * 2U, static_cast<std::uint16_t>(index + 1U));
        }
        console.program(0, main_ram_base, main_ram_base + 0x106U, control_word(4U, command(1)));
        console.main().dma().run(console.main());
        check(console.main().read16(main_ram_base + 0x106U) == 1U, "la première unité est en haut");
        check(console.main().read16(main_ram_base + 0x100U) == 4U, "et la dernière en bas");
    }
}

/**
 * Le compte s'entend en unités, et un compte nul demande l'étendue entière.
 *
 * L'étendue n'est pas la même des deux côtés : vingt et un bits pour le
 * processeur principal, seize pour le secondaire. Un canal du secondaire qui
 * demanderait deux millions d'unités n'en copierait que soixante-cinq mille.
 */
void le_compte_s_entend_en_unites() {
    constexpr std::uint32_t from = 0x1000;
    constexpr std::uint32_t to = 0x2000;

    {   // Compte nul chez le secondaire : soixante-cinq mille cinq cent
        // trente-six unités, pas une de plus.
        FlatMemory memory{0x40000};
        InterruptController interrupts;
        DmaController channels{Processor::secondary, interrupts};
        memory.write16(from, 0x5a5aU);
        channels.set_source(0, from);
        channels.set_destination(0, to);
        channels.set_control(0, control_word(0U, command(0, 2)));   // départ figé
        check(channels.pending(), "un compte nul arme tout de même le canal");
        channels.run(memory);

        check(memory.read16(to) == 0x5a5aU, "la première unité est écrite");
        check(memory.read16(to + 0xffffU * 2U) == 0x5a5aU, "la 65536e aussi");
        check(memory.read16(to + 0x1'0000U * 2U) == 0U, "et pas la suivante");
    }
    {   // Le principal compte plus loin : un compte que seize bits ne
        // porteraient pas doit être respecté.
        FlatMemory memory{0x80000};
        InterruptController interrupts;
        DmaController channels{Processor::main, interrupts};
        memory.write16(from, 0x3c3cU);
        channels.set_source(0, from);
        channels.set_destination(0, to);
        channels.set_control(0, control_word(0x1'0001U, command(0, 2)));
        channels.run(memory);

        check(memory.read16(to + 0x1'0000U * 2U) == 0x3c3cU, "la 65537e unité est écrite");
        check(memory.read16(to + 0x1'0001U * 2U) == 0U, "et pas la suivante");
    }
}

/** L'ordre des canaux est celui de leurs priorités. */
void les_canaux_sont_servis_dans_l_ordre() {
    FlatMemory memory{0x10000};
    InterruptController interrupts;
    DmaController channels{Processor::main, interrupts};

    memory.write16(0x100U, 0x1111U);
    memory.write16(0x200U, 0x3333U);

    channels.set_source(0, 0x100U);
    channels.set_destination(0, 0x800U);
    channels.set_control(0, control_word(1U, command()));
    channels.set_source(3, 0x200U);
    channels.set_destination(3, 0x800U);
    channels.set_control(3, control_word(1U, command()));

    channels.run(memory);

    // Le premier canal est le plus prioritaire : il passe d'abord, et c'est donc
    // l'écriture du dernier qui reste.
    check(memory.read16(0x800U) == 0x3333U, "le canal le moins prioritaire écrit en dernier");
}

/** Un canal qui n'est pas armé n'est pas servi. */
void un_canal_non_arme_n_est_pas_servi() {
    FlatMemory memory{0x10000};
    InterruptController interrupts;
    DmaController channels{Processor::main, interrupts};

    memory.write16(0x100U, 0x9999U);

    // Le premier part tout de suite, le second attend le retour vertical.
    channels.set_source(0, 0x100U);
    channels.set_destination(0, 0x800U);
    channels.set_control(0, control_word(1U, command()));
    channels.set_source(1, 0x100U);
    channels.set_destination(1, 0x900U);
    channels.set_control(1, control_word(1U, command(0, 0, false, false, 1)));

    channels.run(memory);

    check(memory.read16(0x800U) == 0x9999U, "le canal armé est servi");
    check(memory.read16(0x900U) == 0U, "celui qui attend son moment ne l'est pas");
}

/** Un canal éteint n'attend aucun moment. */
void un_canal_eteint_n_attend_aucun_moment() {
    InterruptController interrupts;
    DmaController channels{Processor::main, interrupts};

    // Le champ dit « retour vertical », mais le canal n'est pas allumé.
    channels.set_control(0, control_word(1U, command(0, 0, false, false, 1, false, false)));
    channels.trigger(DmaController::Timing::vertical_blank);
    check(!channels.pending(), "un canal éteint ne s'arme pas");
}

/** L'allumage fige les adresses, et les rechanger ensuite n'y touche plus. */
void l_allumage_fige_les_adresses() {
    FlatMemory memory{0x10000};
    InterruptController interrupts;
    DmaController channels{Processor::main, interrupts};

    memory.write16(0x100U, 0xaaaaU);
    memory.write16(0x200U, 0xbbbbU);

    channels.set_source(0, 0x100U);
    channels.set_destination(0, 0x800U);
    channels.set_control(0, control_word(1U, command()));

    // Le canal est allumé et armé : changer les registres et réécrire la même
    // commande ne doit pas déplacer ce qu'il va copier.
    channels.set_source(0, 0x200U);
    channels.set_control(0, control_word(1U, command()));
    channels.run(memory);

    check(memory.read16(0x800U) == 0xaaaaU, "le transfert part de l'adresse figée à l'allumage");
}

/** Le moment décide du départ, et il n'est pas le même des deux côtés. */
void le_moment_decide_du_depart() {
    InterruptController interrupts;
    {
        DmaController channels{Processor::main, interrupts};
        channels.set_control(0, control_word(1U, command(0, 0, false, false, 0)));
        check(channels.timing(0) == DmaController::Timing::immediate, "le moment zéro est immédiat");
        check(channels.pending(), "et arme le canal sur-le-champ");
    }
    {
        DmaController channels{Processor::main, interrupts};
        channels.set_control(0, control_word(1U, command(0, 0, false, false, 1)));
        check(channels.timing(0) == DmaController::Timing::vertical_blank, "le moment un est le retour vertical");
        check(!channels.pending(), "qui n'arme rien avant d'arriver");
        channels.trigger(DmaController::Timing::horizontal_blank);
        check(!channels.pending(), "et qu'un autre moment ne déclenche pas");
        channels.trigger(DmaController::Timing::vertical_blank);
        check(channels.pending(), "puis l'arme quand il arrive");
    }
    {
        DmaController channels{Processor::main, interrupts};
        channels.set_control(0, control_word(1U, command(0, 0, false, false, 2)));
        check(
            channels.timing(0) == DmaController::Timing::horizontal_blank,
            "le moment deux est le retour horizontal chez le principal"
        );
    }
    {
        // Le champ n'a pas la même largeur des deux côtés, si bien que les mêmes
        // bits ne disent pas la même chose : ce que le principal lit comme un
        // retour vertical, le secondaire le lit comme un départ immédiat.
        DmaController channels{Processor::secondary, interrupts};
        channels.set_control(0, control_word(1U, command(0, 0, false, false, 1)));
        check(
            channels.timing(0) == DmaController::Timing::immediate,
            "les bits du retour vertical du principal ne disent rien au secondaire"
        );

        DmaController other{Processor::secondary, interrupts};
        other.set_control(0, control_word(1U, command(0, 0, false, false, 2)));
        check(
            other.timing(0) == DmaController::Timing::vertical_blank,
            "son retour vertical à lui est un bit plus haut"
        );

        DmaController third{Processor::secondary, interrupts};
        third.set_control(0, control_word(1U, command(0, 0, false, false, 4)));
        check(
            third.timing(0) == DmaController::Timing::unsupported,
            "et la cartouche, dont l'organe n'existe pas, est comptée"
        );
    }
}

/** Un moment dont l'organe n'existe pas est compté, non approché. */
void un_moment_sans_organe_est_compte() {
    InterruptController interrupts;
    DmaController channels{Processor::main, interrupts};

    channels.set_control(0, control_word(1U, command(0, 0, false, false, 5)));  // cartouche
    check(channels.unsupported_timing_count() == 1U, "le moment inconnu est compté");
    check(!channels.pending(), "et le canal n'est pas armé");

    channels.set_control(1, control_word(1U, command(0, 0, false, false, 7)));  // file géométrique
    check(channels.unsupported_timing_count() == 2U, "chaque canal compte pour lui-même");

    channels.set_control(2, control_word(1U, command()));
    check(channels.unsupported_timing_count() == 2U, "un moment connu ne compte pas");
}

/** L'allumage est un front, et il fige les adresses de départ. */
void l_allumage_est_un_front() {
    Console console;
    console.main().write16(main_ram_base, 0x3333U);
    console.program(0, main_ram_base, main_ram_base + 0x100U, control_word(1U, command()));
    console.main().dma().run(console.main());
    check(console.main().read16(main_ram_base + 0x100U) == 0x3333U, "le transfert a eu lieu");

    // Réécrire la commande sur un canal déjà éteint par son transfert le
    // rallume ; mais tant qu'il est allumé, la réécrire ne relance rien.
    console.program(1, main_ram_base, main_ram_base + 0x200U, control_word(1U, command(0, 0, false, false, 1)));
    check(!console.main().dma().pending(), "un canal au retour vertical n'est pas armé");
    console.main().write32(dma_base + 12U + 8U, control_word(1U, command(0, 0, false, false, 1)));
    check(!console.main().dma().pending(), "et le réécrire allumé ne l'arme pas non plus");
}

/** Le transfert réveille le processeur, s'il l'a demandé. */
void le_transfert_leve_une_interruption() {
    {
        Console console;
        console.program(0, main_ram_base, main_ram_base + 0x100U, control_word(1U, command()));
        console.main().dma().run(console.main());
        check(
            console.machine.interrupts(Processor::main).requested() == 0U,
            "sans le demander, aucun réveil"
        );
    }
    {
        Console console;
        console.program(
            2, main_ram_base, main_ram_base + 0x100U,
            control_word(1U, command(0, 0, false, false, 0, true)));
        console.main().dma().run(console.main());
        check(
            console.machine.interrupts(Processor::main).requested() == 1U << 10U,
            "le troisième canal pose la source de rang dix"
        );
    }
}

/**
 * La répétition : un canal qui revient à chaque retour vertical.
 *
 * C'est la forme dont un jeu se sert pour recopier sa table de sprites à chaque
 * trame sans y revenir.
 */
void un_canal_repete_revient_a_chaque_trame() {
    Console console;
    // Départ figé et arrivée en reprise : c'est la forme qui repose la même
    // chose au même endroit à chaque trame. Sans elle, les deux adresses
    // continueraient d'avancer d'un tour à l'autre.
    console.main().write16(main_ram_base, 0x0101U);
    console.program(
        0, main_ram_base, palette_base,
        control_word(1U, command(3, 2, true, false, 1)));

    // Jusqu'au retour vertical, rien ne part.
    console.run_line();
    check(console.main().read16(palette_base) == 0U, "rien n'est parti avant le retour vertical");

    for (std::uint32_t line = 0; line < 200U; ++line) console.run_line();
    check(console.main().read16(palette_base) == 0x0101U, "le retour vertical a déclenché la copie");
    check(
        (console.main().read32(dma_base + 8U) & (1U << 31U)) != 0U,
        "et le canal répété est resté allumé"
    );

    // La trame suivante recopie de nouveau, avec la valeur courante.
    console.main().write16(main_ram_base, 0x0202U);
    console.main().write16(palette_base, 0U);
    for (std::uint32_t line = 0; line < 263U; ++line) console.run_line();
    check(console.main().read16(palette_base) == 0x0202U, "et la trame suivante recopie la nouvelle valeur");
}

/**
 * Sans reprise, un canal répété continue là où il en était.
 *
 * C'est ce qui distingue les deux façons dont une arrivée évolue : l'une revient
 * à son point de départ à chaque tour, l'autre poursuit son chemin. Le compte
 * exact des unités écrites compte ici : il dit aussi que le retour vertical n'a
 * armé le canal **qu'une fois** par trame, et non à chacune des lignes qui ne
 * s'affichent pas.
 */
void un_canal_repete_sans_reprise_poursuit_son_chemin() {
    Console console;
    console.main().write16(main_ram_base, 0x0303U);
    console.program(
        0, main_ram_base, palette_base,
        control_word(1U, command(0, 2, true, false, 1)));

    for (std::uint32_t line = 0; line < 200U; ++line) console.run_line();
    check(console.main().read16(palette_base) == 0x0303U, "le premier tour écrit au départ");
    check(console.main().read16(palette_base + 2U) == 0U, "une seule unité, donc un seul tour");

    for (std::uint32_t line = 0; line < 263U; ++line) console.run_line();
    check(
        console.main().read16(palette_base + 2U) == 0x0303U,
        "et le second écrit juste après, sans revenir en arrière"
    );
    check(console.main().read16(palette_base + 4U) == 0U, "toujours un seul tour par trame");
}

/**
 * Sans le bit de répétition, un canal ne revient pas.
 *
 * Le départ figé pose ici le bit voisin de celui de la répétition : les deux
 * champs se touchent, et les confondre laisserait un canal se rallumer seul.
 */
void un_canal_sans_repetition_ne_revient_pas() {
    Console console;
    console.main().write16(main_ram_base, 0x0404U);
    console.program(
        0, main_ram_base, palette_base,
        control_word(1U, command(0, 2, false, false, 1)));

    for (std::uint32_t line = 0; line < 200U; ++line) console.run_line();
    check(console.main().read16(palette_base) == 0x0404U, "le transfert a eu lieu");
    check(
        (console.main().read32(dma_base + 8U) & (1U << 31U)) == 0U,
        "et le canal s'est éteint, faute de répétition"
    );
}

/** Un départ immédiat ne se répète pas, même si on le lui demande. */
void un_depart_immediat_ne_se_repete_pas() {
    Console console;
    console.program(
        0, main_ram_base, palette_base, control_word(1U, command(0, 0, true, false, 0)));
    console.main().dma().run(console.main());
    check(
        (console.main().read32(dma_base + 8U) & (1U << 31U)) == 0U,
        "le canal s'éteint tout de même"
    );
}

/**
 * Le retour horizontal arme aussi, une fois par ligne.
 *
 * Le canal s'arme à la fin d'une ligne et se sert au premier pas de la suivante,
 * comme une interruption posée au même instant : le décalage d'une ligne est
 * celui du modèle, et il est le même pour les deux.
 */
void le_retour_horizontal_arme_a_chaque_ligne() {
    Console console;
    console.main().write16(main_ram_base, 0x0606U);
    console.program(
        0, main_ram_base, palette_base,
        control_word(1U, command(0, 2, true, false, 2)));

    console.run_line();
    check(console.main().read16(palette_base) == 0U, "armé en fin de ligne, pas encore servi");
    console.run_line();
    check(console.main().read16(palette_base) == 0x0606U, "servi au premier pas de la ligne suivante");
    check(console.main().read16(palette_base + 2U) == 0U, "une seule unité");
    console.run_line();
    check(console.main().read16(palette_base + 2U) == 0x0606U, "et une de plus à chaque ligne");
    check(console.main().read16(palette_base + 4U) == 0U, "jamais deux dans la même");
}

/** Le processeur secondaire a les siens, et l'ordonnanceur les sert aussi. */
void les_canaux_du_secondaire_sont_servis() {
    Console console;
    constexpr std::uint32_t private_wram_base = 0x0380'0000;

    console.secondary().write16(private_wram_base, 0x0707U);
    const auto base = dma_base;
    console.secondary().write32(base, private_wram_base);
    console.secondary().write32(base + 4U, private_wram_base + 0x100U);
    console.secondary().write32(base + 8U, control_word(1U, command()));

    check(
        console.secondary().read16(private_wram_base + 0x100U) == 0U,
        "rien n'a bougé avant que l'ordonnanceur ne serve le canal"
    );
    console.run_line();
    check(
        console.secondary().read16(private_wram_base + 0x100U) == 0x0707U,
        "la ligne a servi le canal du secondaire"
    );
}

/** Un transfert armé a lieu entre deux instructions, non à la fin de la ligne. */
void un_transfert_a_lieu_entre_deux_instructions() {
    Console console;
    console.main().write16(main_ram_base + 0x40U, 0x7777U);
    console.program(0, main_ram_base + 0x40U, main_ram_base + 0x80U, control_word(1U, command()));

    // Un seul pas du processeur suffit : le canal était armé avant lui.
    console.machine.core(Processor::main).state().registers[15] = main_ram_base + 0x1000U;
    console.machine.core(Processor::main).step();
    check(
        console.main().read16(main_ram_base + 0x80U) == 0U,
        "l'instruction seule ne déclenche rien : c'est l'ordonnanceur qui sert les canaux"
    );

    console.run_line();
    check(console.main().read16(main_ram_base + 0x80U) == 0x7777U, "la ligne a servi le canal");
}

/** Chaque processeur a les siens, et les registres répondent des deux côtés. */
void chaque_processeur_a_ses_canaux() {
    Console console;

    console.main().write32(dma_base, 0x0201'0000U);
    check(console.main().read32(dma_base) == 0x0201'0000U, "l'adresse de départ se relit");
    check(console.secondary().read32(dma_base) == 0U, "et n'apparaît pas chez l'autre");

    console.secondary().write32(dma_base + 4U, 0x0380'1000U);
    check(console.secondary().read32(dma_base + 4U) == 0x0380'1000U, "le secondaire a les siens");
    check(console.main().read32(dma_base + 4U) == 0U, "que le principal ne voit pas");

    // Chaque canal a ses propres registres : les relire doit rendre ce qu'on y a
    // mis, et non ce qu'on a mis dans le premier.
    console.main().write32(dma_base + 24U, 0x0203'0000U);
    console.main().write32(dma_base + 24U + 8U, control_word(7U, command(0, 0, false, false, 1)));
    check(console.main().read32(dma_base + 24U) == 0x0203'0000U, "le troisième canal se relit");
    check(console.main().read32(dma_base + 24U + 8U) == control_word(7U, command(0, 0, false, false, 1)),
          "sa commande aussi");
    check(console.main().read32(dma_base) == 0x0201'0000U, "sans avoir touché au premier");

    check(console.main().unimplemented_io_count() == 0U, "aucune adresse de canal n'est inconnue");
    check(console.secondary().unimplemented_io_count() == 0U, "des deux côtés");
}

/** La remise à zéro d'une carte efface ses canaux. */
void la_remise_a_zero_efface_les_canaux() {
    Console console;
    console.program(0, main_ram_base, main_ram_base + 0x100U, control_word(4U, command(0, 0, false, false, 1)));
    console.main().write32(dma_base + 12U + 8U, control_word(1U, command(0, 0, false, false, 5)));
    check(console.main().dma().unsupported_timing_count() == 1U, "un moment inconnu a été compté");

    console.main().reset();

    check(console.main().read32(dma_base) == 0U, "l'adresse de départ est effacée");
    check(console.main().read32(dma_base + 8U) == 0U, "la commande aussi");
    check(console.main().dma().unsupported_timing_count() == 0U, "et le compte des moments inconnus");
    check(!console.main().dma().pending(), "plus rien n'attend");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_quantites_sont_celles_du_materiel();
    un_transfert_immediat_copie_la_memoire();
    les_deux_largeurs_d_unite();
    chaque_adresse_evolue_a_sa_facon();
    le_compte_s_entend_en_unites();
    les_canaux_sont_servis_dans_l_ordre();
    un_canal_non_arme_n_est_pas_servi();
    un_canal_eteint_n_attend_aucun_moment();
    l_allumage_fige_les_adresses();
    le_moment_decide_du_depart();
    un_moment_sans_organe_est_compte();
    l_allumage_est_un_front();
    le_transfert_leve_une_interruption();
    un_canal_repete_revient_a_chaque_trame();
    un_canal_repete_sans_reprise_poursuit_son_chemin();
    un_canal_sans_repetition_ne_revient_pas();
    un_depart_immediat_ne_se_repete_pas();
    le_retour_horizontal_arme_a_chaque_ligne();
    les_canaux_du_secondaire_sont_servis();
    un_transfert_a_lieu_entre_deux_instructions();
    chaque_processeur_a_ses_canaux();
    la_remise_a_zero_efface_les_canaux();
    return 0;
}
