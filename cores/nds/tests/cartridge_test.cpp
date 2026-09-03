#include "system/cartridge.hpp"
#include "system/machine.hpp"
#include "system/registers.hpp"

#include <ravenemu/nds/core.hpp>

#include "check.hpp"

#include <cstdint>
#include <initializer_list>
#include <span>
#include <string_view>
#include <vector>

/**
 * Le bus de cartouche.
 *
 * L'amorçage recopie les deux blocs de code que l'en-tête décrit, et rien de
 * plus. Tout le reste d'un jeu se lit par ce bus, à la demande : un jeu qui en
 * est privé ne montre pas un écran incomplet, il s'arrête à la première chose
 * qu'il veut charger.
 *
 * Trois choses sont éprouvées ici. La cadence **mot par mot**, que le jeu
 * observe et sur laquelle il règle ses canaux de transfert. Le **partage du
 * port** entre les deux processeurs, dont un seul y accède à la fois. Et la
 * distinction entre ce que le bus sert et ce qu'il ne sert pas, un contenu
 * inventé étant pire qu'un refus lisible.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

constexpr std::size_t rom_bytes = 0x8000;
constexpr std::uint32_t arm9_rom_offset = 0x4000;
constexpr std::uint32_t arm7_rom_offset = 0x6000;
constexpr std::uint32_t block_bytes = 0x400;

constexpr std::uint32_t main_ram_base = 0x0200'0000;
constexpr std::uint32_t private_wram_base = 0x0380'0000;

/** Une zone de l'image, hors des deux blocs de code, qui porte des données. */
constexpr std::uint32_t payload_offset = 0x2000;

constexpr std::uint32_t always = 0xeU;

constexpr std::uint32_t mov_immediate(
    std::uint32_t rd,
    std::uint32_t value,
    std::uint32_t rotation = 0U
) noexcept {
    return (always << 28U) | (1U << 25U) | (0xdU << 21U) | (rd << 12U) |
        ((rotation / 2U) << 8U) | value;
}

constexpr std::uint32_t transfer(
    bool load,
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t offset = 0U
) noexcept {
    return (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) |
        (load ? (1U << 20U) : 0U) | (rn << 16U) | (rd << 12U) | offset;
}

constexpr std::uint32_t add_immediate(
    std::uint32_t rd,
    std::uint32_t rn,
    std::uint32_t value,
    std::uint32_t rotation = 0U
) noexcept {
    return (always << 28U) | (1U << 25U) | (0x4U << 21U) | (rn << 16U) | (rd << 12U) |
        ((rotation / 2U) << 8U) | value;
}

constexpr std::uint32_t branch(std::int32_t words, std::uint32_t condition = always) noexcept {
    return (condition << 28U) | (0x5U << 25U) |
        (static_cast<std::uint32_t>(words - 2) & 0x00ff'ffffU);
}

/** `STRB Rd, [Rn, #offset]` : un octet, non un mot. */
constexpr std::uint32_t store_byte(
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t offset
) noexcept {
    return (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) | (1U << 22U) |
        (rn << 16U) | (rd << 12U) | offset;
}

/** `SUB Rd, Rn, #value`, avec mise à jour des indicateurs. */
constexpr std::uint32_t subtract_immediate(
    std::uint32_t rd,
    std::uint32_t rn,
    std::uint32_t value
) noexcept {
    return (always << 28U) | (1U << 25U) | (0x2U << 21U) | (1U << 20U) |
        (rn << 16U) | (rd << 12U) | value;
}

/** Condition « différent de zéro ». */
constexpr std::uint32_t not_equal = 0x1U;

/** Fabrique d'image de cartouche, avec une charge utile à lire par le bus. */
class Cartouche {
public:
    Cartouche() : image_(rom_bytes, 0) {
        write_text(0x000, "RAVENCARD");
        write_text(0x00c, "ARVE");
        image_[0x012] = static_cast<std::uint8_t>(UnitCode::nintendo_ds);
        write_u32(0x080, static_cast<std::uint32_t>(rom_bytes));
        write_u32(0x084, 0x0000'4000);
        set_block(true, arm9_rom_offset, main_ram_base);
        set_block(false, arm7_rom_offset, private_wram_base);
        set_code(arm9_rom_offset, {branch(0)});
        set_code(arm7_rom_offset, {branch(0)});
        // Une charge utile reconnaissable : chaque mot vaut son propre rang,
        // si bien qu'un mot lu au mauvais endroit se voit immédiatement.
        for (std::uint32_t index = 0; index < 0x100U; ++index) {
            write_u32(payload_offset + index * 4U, 0xda7a'0000U | index);
        }
    }

    void set_block(bool main, std::uint32_t rom_offset, std::uint32_t address) {
        const std::size_t base = main ? 0x020U : 0x030U;
        write_u32(base + 0x0U, rom_offset);
        write_u32(base + 0x4U, address);
        write_u32(base + 0x8U, address);
        write_u32(base + 0xcU, block_bytes);
    }

    void set_code(std::uint32_t rom_offset, std::initializer_list<std::uint32_t> program) {
        auto cursor = static_cast<std::size_t>(rom_offset);
        for (const auto word : program) {
            write_u32(cursor, word);
            cursor += 4U;
        }
    }

    void write_u32(std::size_t offset, std::uint32_t value) {
        for (std::size_t byte = 0; byte < 4U; ++byte) {
            image_[offset + byte] = static_cast<std::uint8_t>((value >> (byte * 8U)) & 0xffU);
        }
    }

    void write_text(std::size_t offset, std::string_view text) {
        for (std::size_t index = 0; index < text.size(); ++index) {
            image_[offset + index] = static_cast<std::uint8_t>(text[index]);
        }
    }

    [[nodiscard]] std::span<const std::uint8_t> image() const noexcept { return image_; }

private:
    std::vector<std::uint8_t> image_;
};

struct Console {
    Machine machine{};
    Cartouche cartouche{};
    std::vector<std::int32_t> framebuffer;

    Console()
        : framebuffer(
              static_cast<std::size_t>(screen_width) * static_cast<std::size_t>(framebuffer_height),
              0
          ) {
        boot();
    }

    void boot() {
        machine.boot(CartridgeHeader::parse(cartouche.image()), cartouche.image());
    }

    void run_frame() { machine.run_frame(framebuffer); }

    [[nodiscard]] Cartridge& bus() noexcept { return machine.cartridge(); }
    [[nodiscard]] Bus& main() noexcept { return machine.main_memory(); }
    [[nodiscard]] Bus& secondary() noexcept { return machine.secondary_memory(); }

    /** Écrit une commande de lecture à [address] et lance un bloc de [field]. */
    void request(std::uint32_t address, std::uint32_t field, Bus& port) {
        port.write8(registers::cartridge_command + 0U, Cartridge::command_read);
        port.write8(registers::cartridge_command + 1U, static_cast<std::uint8_t>(address >> 24U));
        port.write8(registers::cartridge_command + 2U, static_cast<std::uint8_t>(address >> 16U));
        port.write8(registers::cartridge_command + 3U, static_cast<std::uint8_t>(address >> 8U));
        port.write8(registers::cartridge_command + 4U, static_cast<std::uint8_t>(address));
        port.write32(
            registers::cartridge_control,
            Cartridge::start | (field << Cartridge::block_size_shift)
        );
    }
};

// --------------------------------------------------------------------------

/**
 * Les valeurs du matériel, écrites en toutes lettres.
 *
 * Les reprendre des constantes qui les définissent ne prouverait rien : une
 * mutation les déplacerait des deux côtés à la fois, et la vérification
 * suivrait sans rien dire.
 */
void les_valeurs_sont_celles_du_materiel() {
    check(Cartridge::command_read == 0xb7U, "la commande de lecture");
    check(Cartridge::command_chip_id == 0xb8U, "celle de l'identifiant");
    check(Cartridge::start == 1U << 31U, "l'allumage occupe le bit le plus haut");
    check(Cartridge::data_ready == 1U << 23U, "l'indicateur de mot prêt le vingt-quatrième");
    check(Cartridge::block_size_shift == 24U, "le champ de taille commence au vingt-cinquième");
    check(Cartridge::transfer_interrupt == 1U << 14U, "l'autorisation d'interruption");
    check(InterruptController::cartridge == 1U << 19U, "la source d'interruption du bus");
    check(registers::cartridge_to_secondary == 1U << 11U, "le bit qui confie le port");
    check(registers::cartridge_auxiliary == 0x0400'01a0U, "l'adresse du registre auxiliaire");
    check(registers::cartridge_control == 0x0400'01a4U, "celle du registre de commande");
    check(registers::cartridge_command == 0x0400'01a8U, "celle de la commande");
    check(registers::cartridge_data == 0x0410'0010U, "et celle du port de données");
    check(registers::external_memory_control == 0x0400'0204U, "celle du partage des ports");
}

/** La taille du bloc n'est pas la puissance de deux qu'on lirait dans le champ. */
void la_taille_du_bloc_a_deux_cas_a_part() {
    check(Cartridge::block_bytes(0) == 0U, "le champ nul ne demande rien");
    check(Cartridge::block_bytes(1) == 0x100U, "puis les tailles doublent");
    check(Cartridge::block_bytes(2) == 0x200U, "de un en un");
    check(Cartridge::block_bytes(3) == 0x400U, "jusqu'en haut");
    check(Cartridge::block_bytes(4) == 0x800U, "du champ");
    check(Cartridge::block_bytes(5) == 0x1000U, "sans exception");
    check(Cartridge::block_bytes(6) == 0x2000U, "jusque-là");
    // La valeur haute ne poursuit pas la suite : elle demande un seul mot.
    check(Cartridge::block_bytes(7) == 4U, "et la dernière ne demande qu'un mot");
}

/** Un transfert rend ses mots un à un, dans l'ordre de l'image. */
void un_transfert_rend_ses_mots_dans_l_ordre() {
    Console console;
    console.request(payload_offset, 1U, console.main());          // 0x100 octets

    check((console.bus().control() & Cartridge::start) != 0U, "le transfert court");
    check((console.bus().control() & Cartridge::data_ready) != 0U, "et un mot attend");

    for (std::uint32_t index = 0; index < 0x40U; ++index) {
        const auto word = console.main().read32(registers::cartridge_data);
        check(word == (0xda7a'0000U | index), "chaque mot vient de sa place");
    }

    check((console.bus().control() & Cartridge::start) == 0U, "le transfert s'achève");
    check(!console.bus().transferring(), "et rien n'attend plus");

    // Hors transfert, le port n'est tiré par personne : il se lit à un, et non
    // à zéro, qu'un programme prendrait pour un contenu.
    check(
        console.main().read32(registers::cartridge_data) == 0xffff'ffffU,
        "le port au repos se lit à un"
    );
}

/** La commande dit où lire, et une adresse en vaut une autre. */
void la_commande_dit_ou_lire() {
    Console console;
    console.request(payload_offset + 0x40U, 7U, console.main());   // un seul mot
    check(
        console.main().read32(registers::cartridge_data) == (0xda7a'0000U | 0x10U),
        "le bloc commence là où la commande le dit"
    );
    check(!console.bus().transferring(), "et un mot suffit à l'épuiser");
}

/**
 * Les quatre octets de l'adresse, chacun à sa place.
 *
 * Ils se lisent poids fort d'abord, ce qui est l'ordre du bus et non celui de la
 * mémoire du processeur. Une place échangée donne une adresse qui tombe souvent
 * dans l'image quand même : le contenu lu est alors plausible et faux, et c'est
 * pourquoi les deux octets hauts sont éprouvés par une adresse **hors** de
 * l'image, où seule la bonne lecture rend un bus au repos.
 */
void chaque_octet_de_l_adresse_a_sa_place() {
    {   // Les deux octets bas, par le contenu qu'ils désignent.
        Console console;
        console.request(payload_offset + 4U, 7U, console.main());
        check(
            console.main().read32(registers::cartridge_data) == (0xda7a'0000U | 1U),
            "les deux octets bas placent le bloc"
        );
    }
    {   // L'octet de poids fort : mal placé, il ramènerait l'adresse dans l'image.
        Console console;
        console.request(0x0100'2000U, 7U, console.main());
        check(
            console.main().read32(registers::cartridge_data) == 0xffff'ffffU,
            "l'octet de poids fort compte pour ce qu'il vaut"
        );
    }
    {   // Le deuxième, de même.
        Console console;
        console.request(0x0001'2000U, 7U, console.main());
        check(
            console.main().read32(registers::cartridge_data) == 0xffff'ffffU,
            "le deuxième octet aussi"
        );
    }
}

/** Au-delà de l'image, la cartouche ne tire rien. */
void au_dela_de_l_image_le_bus_reste_a_un() {
    Console console;
    console.request(static_cast<std::uint32_t>(rom_bytes), 7U, console.main());
    check(
        console.main().read32(registers::cartridge_data) == 0xffff'ffffU,
        "lire après la fin de l'image ne rend rien"
    );
}

/** L'identifiant de la puce est une commande à part, qui ne lit pas l'image. */
void l_identifiant_de_la_puce_se_demande() {
    Console console;
    console.main().write8(registers::cartridge_command, Cartridge::command_chip_id);
    console.main().write32(
        registers::cartridge_control,
        Cartridge::start | (7U << Cartridge::block_size_shift)
    );
    check(
        console.main().read32(registers::cartridge_data) == Cartridge::chip_id,
        "la puce se nomme"
    );
    check(console.bus().unsupported_count() == 0U, "et cette commande est servie");
}

/**
 * Une commande que ce bus ne sert pas rend un bus au repos, et est comptée.
 *
 * Les autres commandes appartiennent aux phases d'amorçage de la console, qui
 * chiffrent leurs échanges avec des clés que ce dépôt ne contient pas. Rendre
 * zéro serait pire que de ne rien rendre : zéro est une donnée plausible.
 */
void une_commande_non_servie_est_comptee() {
    Console console;
    constexpr std::uint8_t inconnue = 0x3cU;
    console.main().write8(registers::cartridge_command, inconnue);
    console.main().write32(
        registers::cartridge_control,
        Cartridge::start | (7U << Cartridge::block_size_shift)
    );
    check(
        console.main().read32(registers::cartridge_data) == 0xffff'ffffU,
        "elle ne rend pas de contenu"
    );
    check(console.bus().unsupported_count() == 1U, "elle est comptée");
    check(console.bus().first_unsupported() == inconnue, "et son octet retenu");
}

/** Un bloc vide s'achève sans qu'un mot soit lu. */
void un_bloc_vide_s_acheve_aussitot() {
    Console console;
    console.request(payload_offset, 0U, console.main());
    check((console.bus().control() & Cartridge::start) == 0U, "le transfert est déjà fini");
    check(
        (console.bus().control() & Cartridge::data_ready) == 0U,
        "et aucun mot n'attend, faute de quoi un programme attendrait en vain"
    );
}

/** Le registre de commande n'accepte pas qu'on lui pose les bits du matériel. */
void les_bits_du_materiel_ne_s_ecrivent_pas() {
    Console console;
    console.main().write32(registers::cartridge_control, Cartridge::data_ready);
    check(
        (console.bus().control() & Cartridge::data_ready) == 0U,
        "le jeu ne peut pas prétendre qu'un mot attend"
    );

    // L'allumage, lui, s'écrit : c'est ainsi qu'un transfert part. Il est
    // ensuite éteint par le matériel, non par le jeu.
    console.request(payload_offset, 1U, console.main());
    console.main().write32(registers::cartridge_control, 0U);
    check(
        (console.bus().control() & Cartridge::start) != 0U,
        "et il ne peut pas non plus l'éteindre en cours de route"
    );

    // L'allumage est un **front** : réécrit sur un transfert qui court, il ne
    // le relance pas. Le relancer replacerait le curseur au début, et le jeu
    // relirait indéfiniment les mêmes mots sans que rien ne le signale.
    check(console.main().read32(registers::cartridge_data) == 0xda7a'0000U, "premier mot");
    check(
        console.main().read32(registers::cartridge_data) == (0xda7a'0000U | 1U),
        "deuxième mot"
    );
    console.main().write32(
        registers::cartridge_control,
        Cartridge::start | (1U << Cartridge::block_size_shift)
    );
    check(
        console.main().read32(registers::cartridge_data) == (0xda7a'0000U | 2U),
        "et le transfert poursuit là où il en était"
    );
}

/**
 * Un seul processeur tient le port à la fois.
 *
 * L'autre lit une cartouche absente. Sans ce partage, les deux se serviraient du
 * même flux et chacun n'obtiendrait qu'un mot sur deux, ce qu'aucune erreur ne
 * signalerait.
 */
void un_seul_processeur_tient_le_port() {
    Console console;
    check(console.bus().owner() == Processor::main, "le principal le tient d'abord");

    console.request(payload_offset, 1U, console.main());
    check(
        console.secondary().read32(registers::cartridge_data) == 0xffff'ffffU,
        "le secondaire ne voit rien"
    );
    check(console.bus().transferring(), "et n'a rien consommé");

    // Le registre de partage du principal confie le port à l'autre.
    console.main().write16(
        registers::external_memory_control, registers::cartridge_to_secondary);
    check(console.bus().owner() == Processor::secondary, "le port change de main");
    check(
        console.secondary().read32(registers::cartridge_data) == 0xda7a'0000U,
        "et le secondaire lit à son tour"
    );
    check(
        console.main().read32(registers::cartridge_data) == 0xffff'ffffU,
        "tandis que le principal ne voit plus rien"
    );

    // Les registres aussi : sans le port, ils se lisent comme une cartouche
    // absente, et les écritures n'y arrivent pas.
    console.main().write8(registers::cartridge_command, 0x12U);
    check(console.bus().command_byte(0) == Cartridge::command_read, "et n'y écrit plus");
    check(
        console.main().read8(registers::cartridge_command) == 0xffU,
        "ni n'y lit autre chose qu'une cartouche absente"
    );
}

/** Les registres du bus ne sont comptés comme inconnus d'aucun côté. */
void les_registres_du_bus_sont_decodes() {
    Console console;
    // Chaque registre est lu à sa largeur. Celui du haut n'en fait que deux :
    // les deux octets qui le suivent appartiennent à la puce de sauvegarde, que
    // ce cœur ne modélise pas et qui reste donc comptée comme inconnue.
    for (auto* port : {&console.main(), &console.secondary()}) {
        static_cast<void>(port->read16(registers::cartridge_auxiliary));
        static_cast<void>(port->read32(registers::cartridge_control));
        static_cast<void>(port->read32(registers::cartridge_command));
        static_cast<void>(port->read32(registers::cartridge_command + 4U));
        static_cast<void>(port->read32(registers::cartridge_data));
    }
    check(
        console.machine.main_memory().unimplemented_io_count() == 0U,
        "aucun registre du bus n'est inconnu du principal"
    );
    check(
        console.machine.secondary_memory().unimplemented_io_count() == 0U,
        "ni du secondaire"
    );

    // La puce de sauvegarde, elle, n'existe pas : son registre est compté, et
    // c'est ce qui distingue « pas encore fait » de « fait en silence ».
    static_cast<void>(console.main().read16(registers::cartridge_auxiliary + 2U));
    check(
        console.machine.main_memory().unimplemented_io_count() == 2U,
        "la puce de sauvegarde, elle, est signalée"
    );
}

/** La fin d'un transfert peut poser une interruption, si le jeu l'a demandée. */
void la_fin_d_un_transfert_peut_interrompre() {
    {
        Console console;
        console.request(payload_offset, 7U, console.main());
        static_cast<void>(console.main().read32(registers::cartridge_data));
        check(
            (console.machine.interrupts(Processor::main).requested() &
                InterruptController::cartridge) == 0U,
            "sans autorisation, aucune interruption"
        );
    }
    {
        Console console;
        console.main().write16(registers::cartridge_auxiliary, Cartridge::transfer_interrupt);
        console.request(payload_offset, 7U, console.main());
        check(
            (console.machine.interrupts(Processor::main).requested() &
                InterruptController::cartridge) == 0U,
            "elle n'arrive pas avant la fin"
        );
        static_cast<void>(console.main().read32(registers::cartridge_data));
        check(
            (console.machine.interrupts(Processor::main).requested() &
                InterruptController::cartridge) != 0U,
            "mais bien à la fin"
        );
        check(
            (console.machine.interrupts(Processor::secondary).requested() &
                InterruptController::cartridge) == 0U,
            "et seulement au processeur qui tient le port"
        );
    }
}

/**
 * Un jeu lit sa cartouche et se sert de ce qu'il a lu.
 *
 * C'est la vérification qui traverse tout : le programme écrit sa commande,
 * lance le transfert, vide le port mot par mot vers sa mémoire, et range le
 * dernier mot lu à une place qu'on vient constater. Chacun des maillons
 * manquant, la valeur attendue n'y est pas.
 */
/** L'interruption de fin va au processeur qui tient le port, à lui seul. */
void l_interruption_suit_le_port() {
    Console console;
    console.main().write16(
        registers::external_memory_control, registers::cartridge_to_secondary);
    console.secondary().write16(registers::cartridge_auxiliary, Cartridge::transfer_interrupt);
    console.request(payload_offset, 7U, console.secondary());
    static_cast<void>(console.secondary().read32(registers::cartridge_data));

    check(
        (console.machine.interrupts(Processor::secondary).requested() &
            InterruptController::cartridge) != 0U,
        "celui qui tient le port est prévenu"
    );
    check(
        (console.machine.interrupts(Processor::main).requested() &
            InterruptController::cartridge) == 0U,
        "et l'autre ne l'est pas"
    );
}

/** La remise à zéro rend le port au principal et efface le transfert en cours. */
void la_remise_a_zero_efface_le_bus() {
    Console console;
    console.main().write16(
        registers::external_memory_control, registers::cartridge_to_secondary);
    console.request(payload_offset, 1U, console.secondary());
    check(console.bus().transferring(), "un transfert court");
    check(console.bus().owner() == Processor::secondary, "et le port est à l'autre");

    console.machine.reset();

    check(console.bus().owner() == Processor::main, "le port revient au principal");
    check(!console.bus().transferring(), "le transfert est oublié");
    check(console.bus().control() == 0U, "le registre de commande est effacé");
    check(console.bus().command_byte(0) == 0U, "et la commande avec");
}

/** Le secondaire ne fait pas que lire : il commande aussi le bus. */
void le_secondaire_commande_le_bus_quand_il_le_tient() {
    Console console;
    console.main().write16(
        registers::external_memory_control, registers::cartridge_to_secondary);
    console.request(payload_offset + 8U, 7U, console.secondary());
    check(console.bus().command_byte(0) == 0xb7U, "sa commande est arrivée");
    check(
        console.secondary().read32(registers::cartridge_data) == (0xda7a'0000U | 2U),
        "et son transfert est parti"
    );
}

/** Le port de données est un port de mots, et ne s'écrit pas. */
void le_port_de_donnees_ne_se_lit_qu_en_entier() {
    Console console;
    console.request(payload_offset, 1U, console.main());

    // Le lire par morceaux retirerait un mot entier pour un octet demandé.
    static_cast<void>(console.main().read8(registers::cartridge_data));
    check(
        console.machine.main_memory().unimplemented_io_count() != 0U,
        "une lecture partielle est signalée"
    );
    check(
        console.main().read32(registers::cartridge_data) == 0xda7a'0000U,
        "et n'a pas consommé de mot"
    );

    // L'écrire n'a pas de sens : c'est le matériel qui l'alimente. Le refus est
    // silencieux, et surtout n'est pas compté comme une adresse inconnue.
    const auto avant = console.machine.main_memory().unimplemented_io_count();
    console.main().write32(registers::cartridge_data, 0x1234'5678U);
    check(
        console.machine.main_memory().unimplemented_io_count() == avant,
        "l'écriture est ignorée sans être signalée"
    );
    check(
        console.main().read32(registers::cartridge_data) == (0xda7a'0000U | 1U),
        "et n'a rien changé au flux"
    );
}

/**
 * Un canal de transfert autonome vide le port sans que le jeu s'en occupe.
 *
 * C'est ainsi qu'un jeu charge ses données : il arme un canal sur le moment du
 * bus plutôt que de scruter l'indicateur entre chaque mot. Sans ce moment, le
 * canal reste armé pour toujours et rien n'arrive jamais en mémoire.
 */
void un_canal_autonome_vide_le_port() {
    Console console;
    constexpr std::uint32_t dma_base = 0x0400'00b0;
    constexpr std::uint32_t destination = main_ram_base + 0x2000;

    // Source figée sur le port, arrivée qui avance, mots, moment du bus.
    constexpr std::uint32_t command =
        (2U << 7U) | (1U << 10U) | (5U << 11U) | (1U << 15U);
    console.main().write32(dma_base + 0U, registers::cartridge_data);
    console.main().write32(dma_base + 4U, destination);
    console.main().write32(dma_base + 8U, 0x40U | (command << 16U));

    check(
        !console.machine.main_memory().dma().pending(),
        "le canal attend son moment"
    );

    console.request(payload_offset, 1U, console.main());
    check(console.bus().transferring(), "le bus a des mots à rendre");

    console.run_frame();

    check(
        console.main().read32(destination) == 0xda7a'0000U,
        "le canal a vidé le port dans la mémoire"
    );
    check(
        console.main().read32(destination + 0xfcU) == (0xda7a'0000U | 0x3fU),
        "jusqu'au dernier mot du bloc"
    );
    check(!console.bus().transferring(), "et le transfert s'est achevé");
}

/**
 * Le moment du bus n'arme que ce qu'il doit.
 *
 * Deux conditions le gardent, et l'une comme l'autre manquante donne un canal
 * qui part quand il ne faut pas : sans transfert en cours, il copierait un port
 * au repos ; sans le port, il copierait le flux d'un autre processeur.
 */
void le_moment_du_bus_ne_part_pas_a_tort() {
    constexpr std::uint32_t dma_base = 0x0400'00b0;
    constexpr std::uint32_t destination = main_ram_base + 0x2000;
    constexpr std::uint32_t command =
        (2U << 7U) | (1U << 10U) | (5U << 11U) | (1U << 15U);

    {   // Sans transfert en cours, rien ne part.
        Console console;
        console.main().write32(destination, 0x5a5a'5a5aU);
        console.main().write32(dma_base + 0U, registers::cartridge_data);
        console.main().write32(dma_base + 4U, destination);
        console.main().write32(dma_base + 8U, 0x40U | (command << 16U));
        console.run_frame();
        check(
            console.main().read32(destination) == 0x5a5a'5a5aU,
            "un canal du bus ne part pas sans transfert"
        );
    }
    {   // Sans le port, le canal du principal ne part pas non plus.
        Console console;
        console.main().write32(destination, 0x5a5a'5a5aU);
        console.main().write32(dma_base + 0U, registers::cartridge_data);
        console.main().write32(dma_base + 4U, destination);
        console.main().write32(dma_base + 8U, 0x40U | (command << 16U));
        console.main().write16(
            registers::external_memory_control, registers::cartridge_to_secondary);
        console.request(payload_offset, 1U, console.secondary());
        console.run_frame();
        check(
            console.main().read32(destination) == 0x5a5a'5a5aU,
            "et pas davantage sur le flux de l'autre processeur"
        );
        check(console.bus().transferring(), "dont le transfert reste entier");
    }
}

void un_jeu_lit_sa_cartouche() {
    Console console;
    constexpr std::uint32_t destination = main_ram_base + 0x1000;

    console.cartouche.set_code(arm9_rom_offset, {
        mov_immediate(0U, 0x04U, 8U),                       // r0 = 0x0400'0000
        mov_immediate(4U, 0x41U, 12U),                      // r4 = 0x0410'0000, le port
        mov_immediate(1U, 0x02U, 8U),                       // r1 = 0x0200'0000
        add_immediate(1U, 1U, 0x01U, 20U),                  // r1 = 0x0200'1000

        // La commande, octet par octet : elle en fait huit, et un transfert de
        // mot en écraserait quatre à la fois.
        mov_immediate(2U, Cartridge::command_read),
        store_byte(0U, 2U, 0x1a8U),
        mov_immediate(2U, 0U),
        store_byte(0U, 2U, 0x1a9U),
        store_byte(0U, 2U, 0x1aaU),
        mov_immediate(2U, payload_offset >> 8U),
        store_byte(0U, 2U, 0x1abU),
        mov_immediate(2U, payload_offset & 0xffU),
        store_byte(0U, 2U, 0x1acU),

        // Le lancement, avec un bloc de 0x100 octets.
        mov_immediate(2U, 0x81U, 8U),                       // allumage | champ un
        transfer(false, 0U, 2U, 0x1a4U),

        // Vider le port : soixante-quatre mots, un par tour.
        mov_immediate(3U, 0x40U),
        transfer(true, 4U, 2U, 0x010U),                     // r2 = mot du port
        transfer(false, 1U, 2U, 0U),
        add_immediate(1U, 1U, 4U),
        subtract_immediate(3U, 3U, 1U),
        branch(-4, not_equal),
        branch(0),
    });
    console.boot();
    console.run_frame();

    check(
        console.main().read32(destination) == 0xda7a'0000U,
        "le premier mot de la cartouche est arrivé en mémoire"
    );
    check(
        console.main().read32(destination + 0x3cU) == (0xda7a'0000U | 0x0fU),
        "et le seizième aussi"
    );
    check(
        console.main().read32(destination + 0xfcU) == (0xda7a'0000U | 0x3fU),
        "jusqu'au dernier du bloc"
    );
    check(!console.bus().transferring(), "et le transfert s'est achevé");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_valeurs_sont_celles_du_materiel();
    la_taille_du_bloc_a_deux_cas_a_part();
    un_transfert_rend_ses_mots_dans_l_ordre();
    la_commande_dit_ou_lire();
    chaque_octet_de_l_adresse_a_sa_place();
    au_dela_de_l_image_le_bus_reste_a_un();
    l_identifiant_de_la_puce_se_demande();
    une_commande_non_servie_est_comptee();
    un_bloc_vide_s_acheve_aussitot();
    les_bits_du_materiel_ne_s_ecrivent_pas();
    un_seul_processeur_tient_le_port();
    les_registres_du_bus_sont_decodes();
    la_fin_d_un_transfert_peut_interrompre();
    l_interruption_suit_le_port();
    la_remise_a_zero_efface_le_bus();
    le_secondaire_commande_le_bus_quand_il_le_tient();
    le_port_de_donnees_ne_se_lit_qu_en_entier();
    un_canal_autonome_vide_le_port();
    le_moment_du_bus_ne_part_pas_a_tort();
    un_jeu_lit_sa_cartouche();
    return 0;
}
