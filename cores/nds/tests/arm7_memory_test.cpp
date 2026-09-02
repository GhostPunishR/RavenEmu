#include "cpu/arm_core.hpp"
#include "memory/arm7_memory_map.hpp"
#include "system/cartridge.hpp"
#include "memory/arm9_memory_map.hpp"
#include "memory/system_memory.hpp"
#include "system/inter_processor.hpp"

#include "check.hpp"

#include <cstdint>
#include <initializer_list>
#include <string>

/**
 * Carte mémoire du processeur secondaire, et ce que les deux processeurs
 * voient de la mémoire qu'ils partagent.
 *
 * La carte prise seule n'apprend pas grand-chose : c'est **à deux** qu'elle a un
 * sens. La moitié des vérifications ci-dessous montent donc les deux cartes sur
 * la même mémoire système et regardent ce que chacune y voit — la seule façon
 * de dire si le partage est juste, et le préalable à toute communication entre
 * les deux processeurs.
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
constexpr std::uint32_t shared_window_base = 0x0300'0000;
constexpr std::uint32_t always = 0xeU;

constexpr std::uint32_t mov_immediate(std::uint32_t rd, std::uint32_t value, std::uint32_t rotation = 0U) noexcept {
    return (always << 28U) | (1U << 25U) | (0xdU << 21U) | (rd << 12U) |
        (((rotation / 2U) << 8U) | value);
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

/** Les deux cartes montées sur la même mémoire système, comme sur la console. */
struct Console {
    SystemMemory system{};
    InterruptController main_interrupts{};
    InterruptController secondary_interrupts{};
    InterProcessor link{main_interrupts, secondary_interrupts};
    InputState input{};
    VideoSystem video{main_interrupts, secondary_interrupts};
    Cartridge cartridge{main_interrupts, secondary_interrupts};
    SerialPort serial{secondary_interrupts, input};
    Arm9MemoryMap main_map{system, video, link, main_interrupts, input, cartridge};
    Arm7MemoryMap secondary_map{
        system, video, link, secondary_interrupts, input, cartridge, serial};

    Console() {
        system.reset();
        main_interrupts.reset();
        secondary_interrupts.reset();
        link.reset();
        main_map.reset();
        secondary_map.reset();
    }

    /** Change le partage de la mémoire commune, comme le fait le principal. */
    void share(std::uint8_t control) {
        main_map.write8(Arm9MemoryMap::shared_wram_control, control);
    }
};

// --------------------------------------------------------------------------

void les_quantites_sont_celles_du_materiel() {
    check(SystemMemory::main_ram_bytes == 4U * 1024U * 1024U, "quatre mégaoctets de mémoire principale");
    check(SystemMemory::shared_wram_bytes == 32U * 1024U, "trente-deux kilooctets de mémoire commune");
    check(Arm7MemoryMap::private_wram_bytes == 64U * 1024U, "soixante-quatre kilooctets en propre");
    check(Arm7MemoryMap::private_wram_base == 0x0380'0000U, "la mémoire propre a sa propre fenêtre");
    check(Arm7MemoryMap::shared_wram_status == 0x0400'0241U, "le partage se constate à son adresse");
}

void chaque_region_repond_a_sa_place() {
    Console console;
    auto& map = console.secondary_map;

    map.write32(main_ram_base, 0x1111'1111U);
    check(map.read32(main_ram_base) == 0x1111'1111U, "mémoire principale : aller-retour");
    check(
        map.read32(main_ram_base + SystemMemory::main_ram_bytes) == 0x1111'1111U,
        "mémoire principale : la région se répète"
    );

    // Et le miroir a bien le pas de la région : à la moitié de son étendue, ce
    // n'est pas la même cellule.
    map.write32(main_ram_base + SystemMemory::main_ram_bytes / 2U, 0x4444'4444U);
    check(map.read32(main_ram_base) == 0x1111'1111U, "mémoire principale : la moitié n'est pas un miroir");

    map.write32(Arm7MemoryMap::private_wram_base, 0x2222'2222U);
    check(map.read32(Arm7MemoryMap::private_wram_base) == 0x2222'2222U, "mémoire propre : aller-retour");
    check(
        map.read32(Arm7MemoryMap::private_wram_base + Arm7MemoryMap::private_wram_bytes) == 0x2222'2222U,
        "mémoire propre : la région se répète"
    );

    // Et à la moitié de son étendue, ce n'est pas la même cellule.
    map.write32(Arm7MemoryMap::private_wram_base + Arm7MemoryMap::private_wram_bytes / 2U, 0x3333'3333U);
    check(
        map.read32(Arm7MemoryMap::private_wram_base) == 0x2222'2222U,
        "mémoire propre : la moitié n'est pas un miroir"
    );

    check(map.unmapped_count() == 0U, "aucun de ces accès n'est inconnu");
}

void les_trois_largeurs_et_l_alignement() {
    Console console;
    auto& map = console.secondary_map;

    map.write32(main_ram_base, 0x1234'5678U);
    check(map.read8(main_ram_base) == 0x78U, "octet bas");
    check(map.read8(main_ram_base + 3U) == 0x12U, "octet haut");
    check(map.read16(main_ram_base + 2U) == 0x1234U, "demi-mot haut");

    // Contrairement à la carte du processeur principal, aucune région d'ici ne
    // refuse l'écriture d'un octet seul : il n'y a ni palette, ni banque vidéo,
    // ni mémoire d'objets.
    map.write8(main_ram_base + 1U, 0xffU);
    check(map.read32(main_ram_base) == 0x1234'ff78U, "l'écriture d'un octet est acceptée");
    map.write8(Arm7MemoryMap::private_wram_base, 0xaaU);
    check(map.read8(Arm7MemoryMap::private_wram_base) == 0xaaU, "en mémoire propre aussi");

    // Les accès désalignés sont ramenés à leur alignement.
    map.write32(main_ram_base + 2U, 0x1122'3344U);
    check(map.read32(main_ram_base) == 0x1122'3344U, "un mot écrit désaligné va au mot");
    check(map.read32(main_ram_base + 4U) == 0U, "et ne déborde pas sur le suivant");
    check(map.read16(main_ram_base + 1U) == 0x3344U, "un demi-mot lu désaligné est ramené");
    check(map.read32(main_ram_base + 2U) == 0x1122'3344U, "un mot lu désaligné est ramené au mot");
}

void la_memoire_principale_est_la_meme_des_deux_cotes() {
    Console console;

    // Ce qu'un processeur écrit, l'autre le lit. C'est la vérification qui
    // justifie tout le découpage de ce lot.
    console.main_map.write32(main_ram_base + 0x100U, 0x0bad'cafeU);
    check(
        console.secondary_map.read32(main_ram_base + 0x100U) == 0x0bad'cafeU,
        "le processeur secondaire lit ce que le principal a écrit"
    );

    console.secondary_map.write32(main_ram_base + 0x200U, 0xdead'beefU);
    check(
        console.main_map.read32(main_ram_base + 0x200U) == 0xdead'beefU,
        "et réciproquement"
    );

    // Les mémoires propres, elles, ne se voient pas : la palette du principal
    // et la mémoire de travail du secondaire sont chacune à soi.
    console.main_map.write32(0x0500'0000U, 0x1111'1111U);
    console.secondary_map.write32(Arm7MemoryMap::private_wram_base, 0x2222'2222U);
    check(console.main_map.read32(0x0500'0000U) == 0x1111'1111U, "la palette reste au principal");
    check(
        console.secondary_map.read32(Arm7MemoryMap::private_wram_base) == 0x2222'2222U,
        "la mémoire de travail reste au secondaire"
    );
}

void le_partage_de_la_memoire_commune_est_complementaire() {
    struct Case {
        std::uint8_t control;
        std::uint32_t main_offset;
        std::uint32_t main_size;
        std::uint32_t secondary_offset;
        std::uint32_t secondary_size;
        const char* label;
    };
    constexpr auto whole = SystemMemory::shared_wram_bytes;
    constexpr auto half = SystemMemory::shared_wram_bytes / 2U;
    constexpr Case cases[] = {
        {0, 0U, whole, 0U, 0U, "tout au principal"},
        {1, half, half, 0U, half, "moitié haute au principal, basse au secondaire"},
        {2, 0U, half, half, half, "moitié basse au principal, haute au secondaire"},
        {3, 0U, 0U, 0U, whole, "tout au secondaire"},
    };

    for (const auto& scenario : cases) {
        Console console;
        console.share(scenario.control);

        const auto main_window = console.main_map.shared_window();
        const auto secondary_window = console.secondary_map.shared_window();
        check(main_window.offset == scenario.main_offset, std::string{scenario.label} + " : décalage du principal");
        check(main_window.size == scenario.main_size, std::string{scenario.label} + " : part du principal");
        check(secondary_window.offset == scenario.secondary_offset, std::string{scenario.label} + " : décalage du secondaire");
        check(secondary_window.size == scenario.secondary_size, std::string{scenario.label} + " : part du secondaire");

        // Les deux parts ne se recouvrent jamais et ne laissent jamais de trou.
        check(
            main_window.size + secondary_window.size == whole,
            std::string{scenario.label} + " : les deux parts font le tout"
        );
        if (main_window.size != 0U && secondary_window.size != 0U) {
            const auto main_end = main_window.offset + main_window.size;
            const auto secondary_end = secondary_window.offset + secondary_window.size;
            check(
                main_window.offset >= secondary_end || secondary_window.offset >= main_end,
                std::string{scenario.label} + " : les deux parts ne se recouvrent pas"
            );
        }
    }
}

void chacun_ecrit_dans_sa_part_sans_toucher_a_l_autre() {
    {   // Découpage en deux moitiés : chacun la sienne.
        Console console;
        console.share(1);                                     // haute au principal
        console.main_map.write32(shared_window_base, 0x1111'1111U);
        console.secondary_map.write32(shared_window_base, 0x2222'2222U);
        check(console.main_map.read32(shared_window_base) == 0x1111'1111U, "le principal garde la sienne");
        check(console.secondary_map.read32(shared_window_base) == 0x2222'2222U, "le secondaire garde la sienne");

        // Et l'échange des moitiés échange bien ce que chacun voit.
        console.share(2);                                     // basse au principal
        check(
            console.main_map.read32(shared_window_base) == 0x2222'2222U,
            "après échange, le principal voit ce que le secondaire avait écrit"
        );
        check(
            console.secondary_map.read32(shared_window_base) == 0x1111'1111U,
            "et le secondaire voit ce que le principal avait écrit"
        );
    }
    {   // Le miroir a le pas de la part, pas celui de la mémoire entière : avec
        // seize kilooctets, l'adresse suivante retombe au début de la part — et
        // non au-delà, où elle sortirait de la mémoire commune.
        Console console;
        console.share(2);                                     // haute au secondaire
        console.secondary_map.write32(shared_window_base, 0x1234'5678U);
        check(
            console.secondary_map.read32(shared_window_base + SystemMemory::shared_wram_bytes / 2U) == 0x1234'5678U,
            "la part de seize kilooctets se répète à son pas"
        );
        // Et ce miroir n'est pas la part du principal, restée vierge.
        check(console.main_map.read32(shared_window_base) == 0U, "la part du principal n'a pas été touchée");
    }
    {   // Tout au secondaire : le principal ne décode plus rien, le secondaire
        // voit les deux moitiés à la suite.
        Console console;
        console.share(3);
        console.secondary_map.write32(shared_window_base, 0xaaaa'aaaaU);
        console.secondary_map.write32(shared_window_base + SystemMemory::shared_wram_bytes / 2U, 0xbbbb'bbbbU);
        check(console.secondary_map.read32(shared_window_base) == 0xaaaa'aaaaU, "première moitié");
        check(
            console.secondary_map.read32(shared_window_base + SystemMemory::shared_wram_bytes / 2U) == 0xbbbb'bbbbU,
            "seconde moitié"
        );

        static_cast<void>(console.main_map.read32(shared_window_base));
        check(console.main_map.unmapped_count() == 4U, "le principal n'a plus de part et ne décode rien");
        check(console.secondary_map.unmapped_count() == 0U, "le secondaire, lui, décode tout");
    }
}

void sans_part_le_secondaire_retombe_sur_sa_memoire_propre() {
    // Le repli est le point qui distingue les deux processeurs : privé de sa
    // part, le secondaire ne se retrouve pas devant une fenêtre muette.
    Console console;
    console.share(0);                                         // tout au principal
    check(console.secondary_map.shared_window().size == 0U, "le secondaire n'a aucune part");

    console.secondary_map.write32(shared_window_base, 0x0bad'cafeU);
    check(console.secondary_map.unmapped_count() == 0U, "la fenêtre répond quand même");
    check(
        console.secondary_map.read32(Arm7MemoryMap::private_wram_base) == 0x0bad'cafeU,
        "elle donne sur la mémoire propre"
    );

    // Et cette écriture n'a pas touché la mémoire commune, dont le principal a
    // reçu la totalité.
    check(console.main_map.read32(shared_window_base) == 0U, "la mémoire commune est intacte");

    // Rendue une part, la fenêtre cesse de donner sur la mémoire propre.
    console.share(3);
    check(console.secondary_map.read32(shared_window_base) == 0U, "la part rendue prend le pas");
    check(
        console.secondary_map.read32(Arm7MemoryMap::private_wram_base) == 0x0bad'cafeU,
        "et la mémoire propre garde ce qu'elle avait reçu"
    );
}

void le_partage_se_constate_mais_ne_se_decide_pas() {
    Console console;
    console.share(2);
    check(
        console.secondary_map.read8(Arm7MemoryMap::shared_wram_status) == 2U,
        "le secondaire lit le partage décidé par le principal"
    );
    check(console.secondary_map.unimplemented_io_count() == 0U, "et ce registre est bien modélisé");

    // Il ne le décide pas : l'écriture est ignorée par le matériel.
    console.secondary_map.write8(Arm7MemoryMap::shared_wram_status, 0U);
    check(
        console.secondary_map.read8(Arm7MemoryMap::shared_wram_status) == 2U,
        "l'écriture du secondaire n'a rien changé"
    );
    check(console.main_map.shared_window().size == SystemMemory::shared_wram_bytes / 2U,
          "et le principal garde sa part");
    check(console.secondary_map.unimplemented_io_count() == 0U, "ce refus n'est pas une lacune");

    // Le principal, lui, décide bien.
    console.share(3);
    check(
        console.secondary_map.read8(Arm7MemoryMap::shared_wram_status) == 3U,
        "le nouveau partage se constate aussitôt"
    );
}

/** La région du programme d'amorçage existe, en bas de l'espace, et ne s'écrit pas. */
void le_programme_d_amorcage_se_lit_et_ne_s_ecrit_pas() {
    Console console;
    auto& map = console.secondary_map;

    // L'adresse est écrite en toutes lettres, pour la même raison que du côté
    // du processeur principal.
    check(Arm7MemoryMap::bios_base == 0U, "la région est en bas de l'espace");
    check(Arm7MemoryMap::bios_bytes == 0x4000U, "et fait seize kilooctets");

    static_cast<void>(map.read32(Arm7MemoryMap::bios_base));
    check(map.unmapped_count() == 0U, "la région est décodée");

    map.bios()[0] = 0x5aU;
    check(map.read8(Arm7MemoryMap::bios_base) == 0x5aU, "ce qu'on y met s'y lit");

    map.write8(Arm7MemoryMap::bios_base, 0xffU);
    check(map.read8(Arm7MemoryMap::bios_base) == 0x5aU, "mais le processeur ne l'écrit pas");
    check(map.unmapped_count() == 0U, "et ce refus n'est pas une adresse inconnue");
}

void ce_qui_n_existe_pas_encore_est_signale() {
    {   // Les banques vidéo qui peuvent lui être confiées non plus.
        Console console;
        static_cast<void>(console.secondary_map.read32(0x0600'0000U));
        check(console.secondary_map.unmapped_count() == 4U, "la fenêtre vidéo est signalée");
    }
    {   // La cartouche et le port Game Boy Advance non plus.
        Console console;
        static_cast<void>(console.secondary_map.read32(0x0800'0000U));
        static_cast<void>(console.secondary_map.read32(0x0a00'0000U));
        check(console.secondary_map.unmapped_count() == 8U, "les deux fenêtres sont signalées");
    }
    {   // Un registre d'entrée-sortie sans effet est compté à part.
        Console console;
        // Les minuteries n'ont pas d'organe : deux octets, deux comptes.
        static_cast<void>(console.secondary_map.read16(never_decoded_io));
        check(console.secondary_map.unimplemented_io_count() == 2U, "un registre inconnu est compté");
        check(console.secondary_map.first_unimplemented_io() == never_decoded_io, "et son adresse retenue");
        check(console.secondary_map.unmapped_count() == 0U, "sans compter comme adresse inconnue");

        console.secondary_map.write16(0x0400'0006U, 0xffffU);
        check(console.secondary_map.unimplemented_io_count() == 4U, "une écriture aussi");
        check(console.secondary_map.first_unimplemented_io() == never_decoded_io, "sans effacer la première");
    }
    {   // La remise à zéro efface l'ardoise et la mémoire propre, mais pas la
        // mémoire partagée, qui appartient à son propriétaire.
        Console console;
        console.secondary_map.write32(Arm7MemoryMap::private_wram_base, 0xdead'beefU);
        console.secondary_map.write32(main_ram_base, 0x0bad'cafeU);
        // Une adresse réellement sans organe : celle du programme d'amorçage est
        // décodée depuis que sa région existe, et ne remplirait plus l'ardoise
        // qu'on veut voir effacée.
        static_cast<void>(console.secondary_map.read32(0x0600'0000U));
        check(console.secondary_map.unmapped_count() == 4U, "l'ardoise est bien remplie");
        // La première retenue est celle du premier octet, non celle du dernier :
        // un mot en compte quatre, et seul le premier doit rester.
        check(
            console.secondary_map.first_unmapped() == 0x0600'0000U,
            "et c'est la première adresse qui est retenue"
        );
        // Un registre réellement sans organe : l'état du balayage est modélisé
        // depuis que le contrôleur d'affichage existe, et ne remplirait plus
        // l'ardoise qu'on veut voir effacée.
        static_cast<void>(console.secondary_map.read16(never_decoded_io));

        console.secondary_map.reset();
        check(console.secondary_map.read32(Arm7MemoryMap::private_wram_base) == 0U, "la mémoire propre est effacée");
        check(console.secondary_map.read32(main_ram_base) == 0x0bad'cafeU, "la mémoire partagée ne l'est pas");
        check(console.secondary_map.unmapped_count() == 0U, "le compte des adresses inconnues repart de zéro");
        check(console.secondary_map.first_unmapped() == 0U, "et la première est oubliée");
        check(console.secondary_map.unimplemented_io_count() == 0U, "celui des registres inconnus aussi");
        check(console.secondary_map.first_unimplemented_io() == 0U, "et sa première adresse");
    }
    {   // La mémoire partagée, elle, est effacée par son propriétaire, et les
        // deux blocs qu'il tient le sont bien tous les deux.
        Console console;
        console.share(3);                                     // tout au secondaire
        console.secondary_map.write32(shared_window_base, 0xdead'beefU);
        console.secondary_map.write32(main_ram_base, 0x0bad'cafeU);

        console.system.reset();
        check(console.secondary_map.read32(main_ram_base) == 0U, "la mémoire principale est effacée");
        // Le partage étant revenu à son état initial, la part du secondaire est
        // nulle : c'est par le principal qu'on vérifie la mémoire commune.
        check(console.main_map.read32(shared_window_base) == 0U, "la mémoire commune aussi");
        check(console.main_map.shared_window().size == SystemMemory::shared_wram_bytes,
              "et le partage repart de son état initial");
    }
}

/** Le processeur secondaire au-dessus de sa carte. */
void le_processeur_secondaire_tourne_sur_sa_carte() {
    constexpr std::uint32_t program = main_ram_base + 0x1000U;
    constexpr std::uint32_t work = Arm7MemoryMap::private_wram_base + 0x400U;

    Console console;
    Arm7 cpu{console.secondary_map};
    cpu.reset();
    cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system);

    // Lit un mot que le processeur principal a déposé en mémoire partagée, et
    // le range dans sa mémoire de travail. C'est la forme la plus simple de ce
    // que les deux processeurs feront pour se parler.
    console.main_map.write32(main_ram_base + 0x800U, 0x0000'0042U);

    const std::uint32_t code[] = {
        mov_immediate(1U, main_ram_base >> 24U, 8U),              // r1 = 0x02000000
        transfer(true, false, 1U, 0U, 0x800U),                    // LDR r0, [r1, #0x800]
        mov_immediate(2U, Arm7MemoryMap::private_wram_base >> 20U, 12U),
        transfer(false, false, 2U, 0U, 0x400U),                   // STR r0, [r2, #0x400]
    };
    for (std::uint32_t index = 0; index < std::size(code); ++index) {
        console.secondary_map.write32(program + index * 4U, code[index]);
    }

    cpu.state().registers[15] = program;
    for (std::size_t step = 0; step < std::size(code); ++step) cpu.step();

    check(cpu.state().registers[0] == 0x42U, "le mot déposé par le principal est lu");
    check(console.secondary_map.read32(work) == 0x42U, "et rangé en mémoire de travail");
    check(cpu.unimplemented_count() == 0U, "aucune instruction inconnue");
    check(console.secondary_map.unmapped_count() == 0U, "aucune adresse inconnue");
    check(console.secondary_map.unimplemented_io_count() == 0U, "aucun registre inconnu");

    // Le principal ne voit pas cette mémoire de travail : elle est bien propre
    // au secondaire.
    check(console.main_map.read32(work) != 0x42U, "la mémoire de travail reste invisible au principal");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_quantites_sont_celles_du_materiel();
    chaque_region_repond_a_sa_place();
    les_trois_largeurs_et_l_alignement();
    la_memoire_principale_est_la_meme_des_deux_cotes();
    le_partage_de_la_memoire_commune_est_complementaire();
    chacun_ecrit_dans_sa_part_sans_toucher_a_l_autre();
    sans_part_le_secondaire_retombe_sur_sa_memoire_propre();
    le_partage_se_constate_mais_ne_se_decide_pas();
    le_programme_d_amorcage_se_lit_et_ne_s_ecrit_pas();
    ce_qui_n_existe_pas_encore_est_signale();
    le_processeur_secondaire_tourne_sur_sa_carte();
    return 0;
}
