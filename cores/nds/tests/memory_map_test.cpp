#include "cpu/arm9.hpp"
#include "memory/memory_map.hpp"

#include "check.hpp"

#include <cstdint>
#include <initializer_list>
#include <string>

/**
 * Carte mémoire du processeur principal.
 *
 * Deux niveaux se succèdent ici. Le premier éprouve la carte seule : où mène
 * chaque adresse, comment les régions se répètent, ce que le partage de la
 * mémoire commune change. Le second la met sous le processeur et fait tourner
 * un programme depuis la mémoire principale, pile dans une mémoire locale — la
 * seule vérification qui dise si l'assemblage tient.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

constexpr std::uint32_t main_ram_base = 0x0200'0000;
constexpr std::uint32_t shared_wram_base = 0x0300'0000;
constexpr std::uint32_t palette_base = 0x0500'0000;
constexpr std::uint32_t oam_base = 0x0700'0000;
constexpr std::uint32_t always = 0xeU;

/** `MOV Rd, #value`. */
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

/** `cond 100 P U S W L Rn liste`. */
constexpr std::uint32_t block_transfer(
    std::uint32_t rn,
    std::uint32_t list,
    bool load,
    bool pre,
    bool up,
    bool writeback
) noexcept {
    return (always << 28U) | (0x4U << 25U) | (pre ? (1U << 24U) : 0U) | (up ? (1U << 23U) : 0U) |
        (writeback ? (1U << 21U) : 0U) | (load ? (1U << 20U) : 0U) | (rn << 16U) | list;
}

/** Commande de banque vidéo : allumée et dirigée vers la fenêtre de transfert. */
constexpr std::uint8_t bank_to_transfer_window = 0x80;

void write_word(MemoryMap& map, std::uint32_t address, std::uint32_t value) {
    map.write32(address, value);
}

// --------------------------------------------------------------------------

void les_quantites_sont_celles_du_materiel() {
    // Comparer une région à la constante qui la définit ne prouverait rien :
    // muter la constante muterait les deux côtés. Ce sont les quantités du
    // matériel qu'il faut figer.
    check(MemoryMap::main_ram_bytes == 4U * 1024U * 1024U, "quatre mégaoctets de mémoire principale");
    check(MemoryMap::shared_wram_bytes == 32U * 1024U, "trente-deux kilooctets de mémoire commune");
    check(MemoryMap::palette_bytes == 2U * 1024U, "deux kilooctets de palette");
    check(MemoryMap::oam_bytes == 2U * 1024U, "deux kilooctets de mémoire d'objets");
    check(MemoryMap::vram_bank_count == 9U, "neuf banques vidéo");
    check(MemoryMap::vram_transfer_base == 0x0680'0000U, "la fenêtre de transfert commence à son adresse");
    check(MemoryMap::vram_control_base == 0x0400'0240U, "les commandes de banques commencent à la leur");
    check(MemoryMap::shared_wram_control == 0x0400'0247U, "le registre de partage est à la sienne");

    // Les neuf banques totalisent six cent cinquante-six kilooctets.
    MemoryMap map;
    std::size_t total = 0;
    for (std::size_t index = 0; index < MemoryMap::vram_bank_count; ++index) {
        total += map.vram_bank(index).size();
    }
    check(total == 656U * 1024U, "les neuf banques totalisent la mémoire vidéo de la console");
    check(map.vram_bank(MemoryMap::vram_bank_count).empty(), "au-delà, il n'y a pas de banque");
}

void chaque_region_repond_a_sa_place() {
    struct Case {
        std::uint32_t base;
        std::uint32_t size;
        const char* label;
    };
    constexpr Case cases[] = {
        {main_ram_base, MemoryMap::main_ram_bytes, "mémoire principale"},
        {shared_wram_base, MemoryMap::shared_wram_bytes, "mémoire commune"},
        {palette_base, MemoryMap::palette_bytes, "palette"},
        {oam_base, MemoryMap::oam_bytes, "mémoire d'objets"},
    };

    for (const auto& scenario : cases) {
        MemoryMap map;
        map.reset();
        write_word(map, scenario.base, 0x1234'5678U);
        check(map.read32(scenario.base) == 0x1234'5678U, std::string{scenario.label} + " : aller-retour");
        check(map.unmapped_count() == 0U, std::string{scenario.label} + " : rien d'inconnu");

        // Le matériel ne décode pas les bits hauts : chaque région se répète.
        check(
            map.read32(scenario.base + scenario.size) == 0x1234'5678U,
            std::string{scenario.label} + " : la région se répète"
        );
        write_word(map, scenario.base + scenario.size * 3U, 0x0bad'cafeU);
        check(
            map.read32(scenario.base) == 0x0bad'cafeU,
            std::string{scenario.label} + " : le miroir écrit dans l'original"
        );

        // Et le miroir a bien le pas de la région : à la moitié de son étendue,
        // ce n'est pas la même cellule.
        write_word(map, scenario.base, 0x1111'1111U);
        write_word(map, scenario.base + scenario.size / 2U, 0x2222'2222U);
        check(
            map.read32(scenario.base) == 0x1111'1111U,
            std::string{scenario.label} + " : la moitié n'est pas un miroir"
        );
    }
}

void les_regions_ne_se_recouvrent_pas() {
    MemoryMap map;
    map.reset();
    write_word(map, main_ram_base, 0x1111'1111U);
    write_word(map, shared_wram_base, 0x2222'2222U);
    write_word(map, palette_base, 0x3333'3333U);
    write_word(map, oam_base, 0x4444'4444U);

    check(map.read32(main_ram_base) == 0x1111'1111U, "la mémoire principale garde la sienne");
    check(map.read32(shared_wram_base) == 0x2222'2222U, "la mémoire commune aussi");
    check(map.read32(palette_base) == 0x3333'3333U, "la palette aussi");
    check(map.read32(oam_base) == 0x4444'4444U, "la mémoire d'objets aussi");
    check(map.unmapped_count() == 0U, "aucun de ces accès n'est inconnu");
}

void les_trois_largeurs_d_acces() {
    MemoryMap map;
    map.reset();
    write_word(map, main_ram_base, 0x1234'5678U);
    check(map.read8(main_ram_base) == 0x78U, "octet bas");
    check(map.read8(main_ram_base + 3U) == 0x12U, "octet haut");
    check(map.read16(main_ram_base) == 0x5678U, "demi-mot bas");
    check(map.read16(main_ram_base + 2U) == 0x1234U, "demi-mot haut");

    map.write8(main_ram_base + 1U, 0xffU);
    check(map.read32(main_ram_base) == 0x1234'ff78U, "une écriture d'octet ne touche que le sien");
    map.write16(main_ram_base + 2U, 0xaaaaU);
    check(map.read32(main_ram_base) == 0xaaaa'ff78U, "une écriture de demi-mot non plus");

    // Les accès désalignés sont ramenés à leur alignement avant d'atteindre la
    // mémoire ; c'est le processeur qui décide ensuite quoi faire du reste.
    check(map.read32(main_ram_base + 2U) == 0xaaaa'ff78U, "un mot désaligné est ramené au mot");
    check(map.read16(main_ram_base + 1U) == 0xff78U, "un demi-mot désaligné aussi");

    // En écriture aussi : le mot part à son alignement, il n'est pas coupé en
    // deux de part et d'autre.
    map.write32(main_ram_base + 2U, 0x1122'3344U);
    check(map.read32(main_ram_base) == 0x1122'3344U, "un mot écrit désaligné va au mot");
    check(map.read32(main_ram_base + 4U) == 0U, "et ne déborde pas sur le suivant");
    map.write16(main_ram_base + 9U, 0xbeefU);
    check(map.read16(main_ram_base + 8U) == 0xbeefU, "un demi-mot écrit désaligné aussi");
}

void un_octet_seul_n_entre_pas_partout() {
    MemoryMap map;
    map.reset();

    // La palette, les banques vidéo et la mémoire d'objets ignorent l'écriture
    // d'un octet seul : c'est le matériel qui refuse, pas une lacune.
    map.write16(palette_base, 0x1234U);
    map.write8(palette_base, 0xffU);
    check(map.read16(palette_base) == 0x1234U, "la palette ignore l'écriture d'un octet");

    map.write16(oam_base, 0x5678U);
    map.write8(oam_base, 0xffU);
    check(map.read16(oam_base) == 0x5678U, "la mémoire d'objets aussi");

    map.write8(MemoryMap::vram_control_base, bank_to_transfer_window);
    map.write16(MemoryMap::vram_transfer_base, 0x9abcU);
    map.write8(MemoryMap::vram_transfer_base, 0xffU);
    check(map.read16(MemoryMap::vram_transfer_base) == 0x9abcU, "les banques vidéo aussi");

    // La mémoire principale et la mémoire commune l'acceptent, elles.
    map.write16(main_ram_base, 0x1234U);
    map.write8(main_ram_base, 0xffU);
    check(map.read16(main_ram_base) == 0x12ffU, "la mémoire principale accepte l'octet");
    map.write16(shared_wram_base, 0x1234U);
    map.write8(shared_wram_base, 0xffU);
    check(map.read16(shared_wram_base) == 0x12ffU, "la mémoire commune aussi");

    check(map.unmapped_count() == 0U, "aucun de ces refus n'est un accès inconnu");
}

void le_partage_de_la_memoire_commune() {
    {   // Au démarrage, le processeur principal reçoit les trente-deux kilooctets.
        MemoryMap map;
        map.reset();
        const auto window = map.shared_window();
        check(window.offset == 0U, "la fenêtre commence au début");
        check(window.size == MemoryMap::shared_wram_bytes, "et couvre tout");
    }
    {   // Les quatre découpages, du point de vue du processeur principal.
        struct Case {
            std::uint8_t control;
            std::uint32_t offset;
            std::uint32_t size;
            const char* label;
        };
        constexpr Case cases[] = {
            {0, 0U, MemoryMap::shared_wram_bytes, "tout au processeur principal"},
            {1, MemoryMap::shared_wram_bytes / 2U, MemoryMap::shared_wram_bytes / 2U, "la moitié haute"},
            {2, 0U, MemoryMap::shared_wram_bytes / 2U, "la moitié basse"},
            {3, 0U, 0U, "rien du tout"},
        };
        for (const auto& scenario : cases) {
            MemoryMap map;
            map.reset();
            map.write8(MemoryMap::shared_wram_control, scenario.control);
            const auto window = map.shared_window();
            check(window.offset == scenario.offset, std::string{scenario.label} + " : décalage");
            check(window.size == scenario.size, std::string{scenario.label} + " : étendue");
            check(
                map.read8(MemoryMap::shared_wram_control) == scenario.control,
                std::string{scenario.label} + " : le registre se relit"
            );
        }
    }
    {   // Les deux moitiés sont bien distinctes, et le découpage les échange.
        MemoryMap map;
        map.reset();
        map.write8(MemoryMap::shared_wram_control, 2U);          // moitié basse
        write_word(map, shared_wram_base, 0x1111'1111U);
        map.write8(MemoryMap::shared_wram_control, 1U);          // moitié haute
        write_word(map, shared_wram_base, 0x2222'2222U);

        check(map.read32(shared_wram_base) == 0x2222'2222U, "la moitié haute garde la sienne");
        map.write8(MemoryMap::shared_wram_control, 2U);
        check(map.read32(shared_wram_base) == 0x1111'1111U, "et la moitié basse la sienne");

        map.write8(MemoryMap::shared_wram_control, 0U);
        check(map.read32(shared_wram_base) == 0x1111'1111U, "vue entière, la moitié basse vient en premier");
        check(
            map.read32(shared_wram_base + MemoryMap::shared_wram_bytes / 2U) == 0x2222'2222U,
            "et la moitié haute ensuite"
        );
    }
    {   // Le miroir a le pas de la fenêtre, pas celui de la mémoire entière :
        // avec seize kilooctets, l'adresse suivante retombe au début.
        MemoryMap map;
        map.reset();
        map.write8(MemoryMap::shared_wram_control, 2U);          // moitié basse
        write_word(map, shared_wram_base, 0x1234'5678U);
        check(
            map.read32(shared_wram_base + MemoryMap::shared_wram_bytes / 2U) == 0x1234'5678U,
            "la fenêtre de seize kilooctets se répète à son pas"
        );
        // Et ce miroir n'est pas l'autre moitié : celle-ci est restée vierge.
        map.write8(MemoryMap::shared_wram_control, 1U);
        check(map.read32(shared_wram_base) == 0U, "la moitié haute n'a pas été touchée");
    }
    {   // N'en avoir aucune part est un état légitime : la région ne répond plus.
        MemoryMap map;
        map.reset();
        map.write8(MemoryMap::shared_wram_control, 3U);
        static_cast<void>(map.read32(shared_wram_base));
        check(map.unmapped_count() == 4U, "sans part, la région ne décode plus rien");
        check(map.first_unmapped() == shared_wram_base, "et l'adresse est retenue");
    }
    {   // Seuls les deux bits bas du registre comptent.
        MemoryMap map;
        map.reset();
        map.write8(MemoryMap::shared_wram_control, 0xfeU);
        check(map.read8(MemoryMap::shared_wram_control) == 2U, "le registre ne garde que deux bits");
    }
}

void les_banques_video_repondent_par_leur_fenetre() {
    {   // Éteinte, une banque ne répond pas.
        MemoryMap map;
        map.reset();
        static_cast<void>(map.read32(MemoryMap::vram_transfer_base));
        check(map.unmapped_count() == 4U, "une banque éteinte ne décode rien");
    }
    {   // Allumée et dirigée vers la fenêtre de transfert, elle répond.
        MemoryMap map;
        map.reset();
        map.write8(MemoryMap::vram_control_base, bank_to_transfer_window);
        write_word(map, MemoryMap::vram_transfer_base, 0x1234'5678U);
        check(map.read32(MemoryMap::vram_transfer_base) == 0x1234'5678U, "la banque A répond");
        check(map.unmapped_count() == 0U, "sans accès inconnu");
    }
    {   // Dirigée vers un moteur graphique, elle quitte cette fenêtre.
        MemoryMap map;
        map.reset();
        map.write8(MemoryMap::vram_control_base, bank_to_transfer_window | 1U);
        static_cast<void>(map.read32(MemoryMap::vram_transfer_base));
        check(map.unmapped_count() == 4U, "une banque dirigée ailleurs ne répond plus ici");
    }
    {   // Les neuf banques ont chacune leur place et leur taille.
        struct Bank {
            std::uint32_t offset;
            std::uint32_t size;
            const char* name;
        };
        constexpr Bank banks[] = {
            {0x0'0000U, 128U * 1024U, "A"},
            {0x2'0000U, 128U * 1024U, "B"},
            {0x4'0000U, 128U * 1024U, "C"},
            {0x6'0000U, 128U * 1024U, "D"},
            {0x8'0000U,  64U * 1024U, "E"},
            {0x9'0000U,  16U * 1024U, "F"},
            {0x9'4000U,  16U * 1024U, "G"},
            {0x9'8000U,  32U * 1024U, "H"},
            {0xa'0000U,  16U * 1024U, "I"},
        };

        MemoryMap map;
        map.reset();
        // Le registre du partage de la mémoire commune s'est glissé au milieu
        // des commandes de banques : les deux dernières sont décalées d'un cran.
        for (std::uint32_t index = 0; index < 7U; ++index) {
            map.write8(MemoryMap::vram_control_base + index, bank_to_transfer_window);
        }
        map.write8(MemoryMap::vram_control_base + 8U, bank_to_transfer_window);
        map.write8(MemoryMap::vram_control_base + 9U, bank_to_transfer_window);

        for (std::uint32_t index = 0; index < 9U; ++index) {
            write_word(map, MemoryMap::vram_transfer_base + banks[index].offset, 0x1000U + index);
            // Le dernier mot de la banque doit répondre lui aussi : c'est ce qui
            // dit que son étendue est la bonne et qu'elle ne mord pas sur la
            // suivante.
            write_word(
                map,
                MemoryMap::vram_transfer_base + banks[index].offset + banks[index].size - 4U,
                0x2000U + index
            );
        }
        for (std::uint32_t index = 0; index < 9U; ++index) {
            check(
                map.read32(MemoryMap::vram_transfer_base + banks[index].offset) == 0x1000U + index,
                std::string{"banque "} + banks[index].name + " : premier mot"
            );
            check(
                map.read32(
                    MemoryMap::vram_transfer_base + banks[index].offset + banks[index].size - 4U
                ) == 0x2000U + index,
                std::string{"banque "} + banks[index].name + " : dernier mot"
            );
            check(
                map.vram_bank(index).size() == banks[index].size,
                std::string{"banque "} + banks[index].name + " : étendue"
            );
        }
        check(map.unmapped_count() == 0U, "les neuf banques répondent");

        // Elles ne se recouvrent pas : la dernière écriture n'a rien écrasé.
        check(map.read32(MemoryMap::vram_transfer_base) == 0x1000U, "la banque A est intacte");
    }
    {   // La dernière banque s'arrête où elle s'arrête.
        MemoryMap map;
        map.reset();
        map.write8(MemoryMap::vram_control_base + 9U, bank_to_transfer_window);
        constexpr std::uint32_t last = MemoryMap::vram_transfer_base + 0xa'0000U;
        write_word(map, last + 16U * 1024U - 4U, 0xdead'beefU);
        check(map.read32(last + 16U * 1024U - 4U) == 0xdead'beefU, "le dernier mot de la banque I répond");
        static_cast<void>(map.read32(last + 16U * 1024U));
        check(map.unmapped_count() == 4U, "et rien au-delà");
    }
    {   // Les fenêtres des moteurs graphiques ne sont pas encore aiguillées.
        MemoryMap map;
        map.reset();
        map.write8(MemoryMap::vram_control_base, bank_to_transfer_window);
        static_cast<void>(map.read32(0x0600'0000U));
        check(map.unmapped_count() == 4U, "la fenêtre du moteur A est signalée");
    }
}

void les_registres_de_la_carte_se_relisent() {
    MemoryMap map;
    map.reset();
    // Une écriture de mot couvre quatre commandes de banques d'un coup, comme le
    // ferait un programme d'initialisation.
    map.write32(MemoryMap::vram_control_base, 0x8483'8281U);
    check(map.read8(MemoryMap::vram_control_base + 0U) == 0x81U, "commande de la banque A");
    check(map.read8(MemoryMap::vram_control_base + 1U) == 0x82U, "commande de la banque B");
    check(map.read8(MemoryMap::vram_control_base + 2U) == 0x83U, "commande de la banque C");
    check(map.read8(MemoryMap::vram_control_base + 3U) == 0x84U, "commande de la banque D");
    check(map.unimplemented_io_count() == 0U, "aucun de ces registres n'est inconnu");

    // Le registre du partage se lit à sa place, entre les commandes G et H.
    map.write8(MemoryMap::vram_control_base + 6U, 0x87U);
    map.write8(MemoryMap::shared_wram_control, 1U);
    map.write8(MemoryMap::vram_control_base + 8U, 0x88U);
    map.write8(MemoryMap::vram_control_base + 9U, 0x89U);
    check(map.read8(MemoryMap::vram_control_base + 6U) == 0x87U, "commande de la banque G");
    check(map.read8(MemoryMap::shared_wram_control) == 1U, "partage de la mémoire commune");
    // Les deux dernières sont décalées d'un cran par le registre de partage :
    // les confondre ferait répondre l'une pour l'autre.
    check(map.read8(MemoryMap::vram_control_base + 8U) == 0x88U, "commande de la banque H");
    check(map.read8(MemoryMap::vram_control_base + 9U) == 0x89U, "commande de la banque I");
    static_cast<void>(map.read8(MemoryMap::vram_control_base + 10U));
    check(map.unimplemented_io_count() == 1U, "et rien au-delà des dix octets");
}

void ce_qui_n_existe_pas_encore_est_signale() {
    {   // Le BIOS n'est pas fourni.
        MemoryMap map;
        map.reset();
        static_cast<void>(map.read32(0xffff'0000U));
        check(map.unmapped_count() == 4U, "le BIOS est signalé");
        check(map.first_unmapped() == 0xffff'0000U, "et son adresse retenue");
    }
    {   // Le port cartouche non plus.
        MemoryMap map;
        map.reset();
        static_cast<void>(map.read32(0x0800'0000U));
        static_cast<void>(map.read32(0x0a00'0000U));
        check(map.unmapped_count() == 8U, "les deux fenêtres de cartouche sont signalées");
    }
    {   // Les adresses basses n'appartiennent pas à cette carte : c'est la
        // mémoire locale du cœur qui les sert, et le processeur la consulte
        // avant le bus.
        MemoryMap map;
        map.reset();
        static_cast<void>(map.read32(0x0000'0100U));
        check(map.unmapped_count() == 4U, "les adresses basses ne sont pas décodées ici");
    }
    {   // Un registre d'entrée-sortie sans effet est compté à part.
        MemoryMap map;
        map.reset();
        static_cast<void>(map.read16(0x0400'0004U));
        check(map.unimplemented_io_count() == 2U, "un registre inconnu est compté");
        check(map.first_unimplemented_io() == 0x0400'0004U, "et son adresse retenue");
        check(map.unmapped_count() == 0U, "sans compter comme adresse inconnue");

        map.write16(0x0400'0006U, 0xffffU);
        check(map.unimplemented_io_count() == 4U, "une écriture aussi");
        check(map.first_unimplemented_io() == 0x0400'0004U, "sans effacer la première");
    }
    {   // La remise à zéro efface l'ardoise et le contenu.
        MemoryMap map;
        map.reset();
        write_word(map, main_ram_base, 0xdead'beefU);
        map.write8(MemoryMap::shared_wram_control, 3U);
        map.write8(MemoryMap::vram_control_base, 0x81U);
        static_cast<void>(map.read32(0xffff'0000U));
        static_cast<void>(map.read16(0x0400'0004U));

        map.reset();
        check(map.read32(main_ram_base) == 0U, "la mémoire principale est effacée");
        check(map.shared_window().size == MemoryMap::shared_wram_bytes, "le partage revient à son état initial");
        check(map.read8(MemoryMap::vram_control_base) == 0U, "les commandes de banques aussi");
        check(map.unmapped_count() == 0U, "le compte des adresses inconnues repart de zéro");
        check(map.first_unmapped() == 0U, "et la première est oubliée");
        check(map.unimplemented_io_count() == 0U, "celui des registres inconnus aussi");
        check(map.first_unimplemented_io() == 0U, "et sa première adresse");
    }
}

/**
 * Le processeur au-dessus de la carte.
 *
 * C'est la vérification qui compte : les deux moitiés existent séparément
 * depuis des lots différents, et rien jusqu'ici ne disait qu'elles s'emboîtent.
 */
void le_processeur_tourne_sur_la_carte() {
    constexpr std::uint32_t program = main_ram_base + 0x1000U;
    constexpr std::uint32_t stack = 0x0080'4000U;                 // haut de la DTCM
    constexpr std::uint32_t dtcm_base = 0x0080'0000U;

    MemoryMap map;
    map.reset();
    Arm9 cpu{map};
    cpu.reset();
    cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system);

    // La mémoire locale de données reçoit la pile, comme le fait le logiciel de
    // la console : elle est plus rapide que la mémoire principale.
    cpu.cp15().write(0U, 9U, 1U, 0U, dtcm_base | (5U << 1U));     // seize kilooctets
    cpu.cp15().write(0U, 1U, 0U, 0U, Cp15::dtcm_enable);

    // Programme : empile deux registres, les modifie, les dépile, puis range le
    // résultat dans la palette. Trois régions différentes en six instructions.
    const std::uint32_t code[] = {
        mov_immediate(0U, 0x11U),
        mov_immediate(1U, 0x22U),
        block_transfer(13U, 0b0011U, false, true, false, true),   // PUSH {r0, r1}
        mov_immediate(0U, 0U),
        mov_immediate(1U, 0U),
        block_transfer(13U, 0b0011U, true, false, true, true),    // POP {r0, r1}
        mov_immediate(2U, palette_base >> 24U, 8U),               // r2 = 0x05000000
        transfer(false, false, 2U, 0U),                           // STR r0, [r2]
        transfer(false, false, 2U, 1U, 4U),                       // STR r1, [r2, #4]
    };
    for (std::uint32_t index = 0; index < std::size(code); ++index) {
        map.write32(program + index * 4U, code[index]);
    }

    cpu.state().registers[13] = stack;
    cpu.state().registers[15] = program;
    for (std::size_t step = 0; step < std::size(code); ++step) cpu.step();

    check(cpu.state().registers[0] == 0x11U, "le premier registre est revenu de la pile");
    check(cpu.state().registers[1] == 0x22U, "le second aussi");
    check(cpu.state().registers[13] == stack, "la pile est revenue à son niveau");
    check(map.read32(palette_base) == 0x11U, "le premier mot est rangé dans la palette");
    check(map.read32(palette_base + 4U) == 0x22U, "le second aussi");
    check(cpu.unimplemented_count() == 0U, "aucune instruction inconnue");

    // La pile est allée dans la mémoire locale, pas dans la carte : celle-ci n'a
    // jamais vu ces adresses.
    check(map.unmapped_count() == 0U, "la carte n'a vu aucune adresse inconnue");
    check(map.unimplemented_io_count() == 0U, "ni aucun registre inconnu");

    // Le programme lui-même a bien été lu depuis la mémoire principale : le
    // modifier là change ce que le processeur exécute.
    map.write32(program, mov_immediate(0U, 0x99U));
    cpu.state().registers[15] = program;
    cpu.step();
    check(cpu.state().registers[0] == 0x99U, "le code est bien relu depuis la mémoire principale");
}

/** Une mémoire locale posée par-dessus une région masque celle-ci. */
void la_memoire_locale_passe_devant_la_carte() {
    MemoryMap map;
    map.reset();
    Arm9 cpu{map};
    cpu.reset();
    cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system);

    constexpr std::uint32_t address = main_ram_base + 0x100U;
    map.write32(address, 0xdead'beefU);

    // La mémoire locale de données est placée sur la mémoire principale, comme
    // le fait le logiciel de la console.
    cpu.cp15().write(0U, 9U, 1U, 0U, main_ram_base | (5U << 1U));
    cpu.cp15().write(0U, 1U, 0U, 0U, Cp15::dtcm_enable);

    const std::uint32_t code[] = {
        mov_immediate(1U, main_ram_base >> 24U, 8U),
        transfer(true, false, 1U, 0U, 0x100U),                    // LDR r0, [r1, #0x100]
        mov_immediate(2U, 0x55U),
        transfer(false, false, 1U, 2U, 0x100U),                   // STR r2, [r1, #0x100]
    };
    constexpr std::uint32_t program = 0x0201'0000U;
    for (std::uint32_t index = 0; index < std::size(code); ++index) {
        map.write32(program + index * 4U, code[index]);
    }
    cpu.state().registers[15] = program;

    // Le programme est hors de la fenêtre locale, donc lu depuis la carte ; la
    // donnée, elle, est dedans.
    for (std::size_t step = 0; step < std::size(code); ++step) cpu.step();

    check(cpu.state().registers[0] == 0U, "la lecture vient de la mémoire locale, encore vierge");
    check(map.read32(address) == 0xdead'beefU, "et la mémoire principale n'a pas été écrite");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_quantites_sont_celles_du_materiel();
    chaque_region_repond_a_sa_place();
    les_regions_ne_se_recouvrent_pas();
    les_trois_largeurs_d_acces();
    un_octet_seul_n_entre_pas_partout();
    le_partage_de_la_memoire_commune();
    les_banques_video_repondent_par_leur_fenetre();
    les_registres_de_la_carte_se_relisent();
    ce_qui_n_existe_pas_encore_est_signale();
    le_processeur_tourne_sur_la_carte();
    la_memoire_locale_passe_devant_la_carte();
    return 0;
}
