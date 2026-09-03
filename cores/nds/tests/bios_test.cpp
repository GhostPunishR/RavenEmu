#include "system/bios.hpp"
#include "system/machine.hpp"
#include "video/display_controller.hpp"
#include "system/registers.hpp"

#include <ravenemu/nds/core.hpp>

#include "check.hpp"

#include <cstdint>
#include <span>
#include <initializer_list>
#include <string_view>
#include <vector>

/**
 * Les services du programme d'amorçage.
 *
 * C'est ce qui manquait pour qu'un jeu démarre. Un jeu de la console n'appelle
 * pas seulement son propre code : il demande au programme d'amorçage d'attendre
 * le retour vertical, de diviser, de décompresser ses données. Sans personne
 * pour répondre, il ne plante pas — il attend une réponse qui ne vient jamais.
 *
 * Les vérifications sont de deux sortes. Les premières appellent un service et
 * regardent ce qu'il rend : c'est là qu'on éprouve les formats de
 * décompression, dont une erreur d'un bit donne des données plausibles et
 * fausses. La dernière fait démarrer une cartouche qui attend une interruption,
 * et c'est celle qui compte : elle traverse l'appel logiciel, l'arrêt du
 * processeur, le vecteur d'interruption, le gestionnaire du jeu et le retour.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

constexpr std::size_t rom_bytes = 0x8000;
constexpr std::uint32_t arm9_rom_offset = 0x4000;
constexpr std::uint32_t arm7_rom_offset = 0x6000;
constexpr std::uint32_t block_bytes = 0x800;

constexpr std::uint32_t main_ram_base = 0x0200'0000;
constexpr std::uint32_t private_wram_base = 0x0380'0000;

/** Une zone de travail en mémoire principale, loin des deux programmes. */
constexpr std::uint32_t scratch = main_ram_base + 0x1000;
constexpr std::uint32_t scratch_output = main_ram_base + 0x2000;

constexpr std::uint32_t always = 0xeU;

constexpr std::uint32_t mov_immediate(
    std::uint32_t rd,
    std::uint32_t value,
    std::uint32_t rotation = 0U
) noexcept {
    return (always << 28U) | (1U << 25U) | (0xdU << 21U) | (rd << 12U) |
        ((rotation / 2U) << 8U) | value;
}

/** `MSR CPSR_c, #value` : change le mode et les masques d'interruption. */
constexpr std::uint32_t set_status(std::uint32_t value) noexcept {
    return 0xe321'f000U | value;
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

/** `SWI number` : le numéro occupe les huit bits hauts du champ de commentaire. */
constexpr std::uint32_t software_interrupt(std::uint32_t number) noexcept {
    return (always << 28U) | (0xfU << 24U) | (number << 16U);
}

/** `BX LR`. */
constexpr std::uint32_t return_to_link = 0xe12f'ff1eU;

/** `ADD Rd, Rn, Rm`. */
constexpr std::uint32_t add_register(
    std::uint32_t rd,
    std::uint32_t rn,
    std::uint32_t rm
) noexcept {
    return (always << 28U) | (0x4U << 21U) | (rn << 16U) | (rd << 12U) | rm;
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

/** `MOV Rd, Rm`. */
constexpr std::uint32_t move_register(std::uint32_t rd, std::uint32_t rm) noexcept {
    return (always << 28U) | (0xdU << 21U) | (rd << 12U) | rm;
}

/** `ORR Rd, Rn, #value`. */
constexpr std::uint32_t or_immediate(
    std::uint32_t rd,
    std::uint32_t rn,
    std::uint32_t value
) noexcept {
    return (always << 28U) | (1U << 25U) | (0xcU << 21U) | (rn << 16U) | (rd << 12U) | value;
}

constexpr std::uint32_t branch(std::int32_t words) noexcept {
    return (always << 28U) | (0x5U << 25U) |
        (static_cast<std::uint32_t>(words - 2) & 0x00ff'ffffU);
}

/** Fabrique d'image de cartouche, réduite à ce dont ces vérifications ont besoin. */
class Cartridge {
public:
    Cartridge() : image_(rom_bytes, 0) {
        write_text(0x000, "RAVENBIOS");
        write_text(0x00c, "ARVE");
        image_[0x012] = static_cast<std::uint8_t>(UnitCode::nintendo_ds);
        write_u32(0x080, static_cast<std::uint32_t>(rom_bytes));
        write_u32(0x084, 0x0000'4000);
        set_block(true, arm9_rom_offset, main_ram_base, main_ram_base);
        set_block(false, arm7_rom_offset, private_wram_base, private_wram_base);
        // Chaque binaire s'immobilise par défaut : une vérification n'a
        // d'ordinaire besoin que d'un seul des deux.
        set_code(arm9_rom_offset, {branch(0)});
        set_code(arm7_rom_offset, {branch(0)});
    }

    void set_block(
        bool main,
        std::uint32_t rom_offset,
        std::uint32_t ram_address,
        std::uint32_t entry_address
    ) {
        const std::size_t base = main ? 0x020U : 0x030U;
        write_u32(base + 0x0U, rom_offset);
        write_u32(base + 0x4U, entry_address);
        write_u32(base + 0x8U, ram_address);
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

/** Une console amorcée sur une cartouche, prête à recevoir des appels. */
struct Console {
    Machine machine{};
    Cartridge cartridge{};
    std::vector<std::int32_t> framebuffer;

    Console()
        : framebuffer(
              static_cast<std::size_t>(screen_width) * static_cast<std::size_t>(framebuffer_height),
              0
          ) {
        boot();
    }

    void boot() {
        machine.boot(CartridgeHeader::parse(cartridge.image()), cartridge.image());
    }

    void run_frame() { machine.run_frame(framebuffer); }

    [[nodiscard]] Bios& bios(Processor side = Processor::main) noexcept {
        return machine.bios(side);
    }

    [[nodiscard]] std::uint32_t& reg(std::size_t index, Processor side = Processor::main) noexcept {
        return machine.core(side).state().registers[index];
    }

    [[nodiscard]] Bus& memory(Processor side = Processor::main) noexcept {
        if (side == Processor::main) return machine.main_memory();
        return machine.secondary_memory();
    }

    /** Dépose une suite d'octets en mémoire, pour servir d'entrée à un service. */
    void write_bytes(std::uint32_t address, std::initializer_list<std::uint8_t> bytes) {
        std::uint32_t offset = 0;
        for (const auto byte : bytes) memory().write8(address + offset++, byte);
    }

    /** Appelle un service et rend ce qu'il a fait du premier registre. */
    std::uint32_t call(std::uint32_t number, Processor side = Processor::main) {
        check(
            bios(side).handle_software_interrupt(number),
            "l'appel doit être servi"
        );
        return reg(0, side);
    }
};

// --------------------------------------------------------------------------

/**
 * La table des vecteurs mène quelque part.
 *
 * Sans elle, une interruption saute dans des octets nuls, qui se décodent en
 * instructions sans effet : le processeur traverse la région entière et part à
 * la dérive, sans que rien ne le signale.
 */
void la_table_des_vecteurs_est_ecrite() {
    Console console;

    const auto word_at = [](std::span<const std::uint8_t> region, std::uint32_t offset) {
        std::uint32_t value = 0;
        for (std::uint32_t byte = 0; byte < 4U; ++byte) {
            value |= static_cast<std::uint32_t>(region[offset + byte]) << (byte * 8U);
        }
        return value;
    };

    // Les deux régions sont écrites : celle du processeur principal l'est
    // aussi, et rien d'autre ne le dirait, son processeur n'ayant pas encore
    // pris d'interruption dans ces vérifications.
    for (const auto region : {
             console.machine.secondary_memory().bios(),
             console.machine.main_memory().bios(),
         }) {
        // Les vecteurs qu'aucun service ne dessert s'arrêtent sur eux-mêmes.
        for (const std::uint32_t offset : {0x00U, 0x04U, 0x08U, 0x0cU, 0x10U, 0x14U, 0x1cU}) {
            check(
                word_at(region, offset) == 0xeaff'fffeU,
                "un vecteur sans service s'arrête sur place"
            );
        }

        // Celui d'interruption saute juste après la table.
        check(
            word_at(region, ArmCore::irq_vector) == 0xea00'0000U,
            "le vecteur d'interruption branche"
        );
        check(word_at(region, 0x20) == 0xe92d'500fU, "le gestionnaire empile ce qu'il peut écraser");
        check(word_at(region, 0x24) == 0xe59f'000cU, "charge le littéral qui suit son code");
        check(word_at(region, 0x28) == 0xe28f'e000U, "pose l'adresse de retour");
        check(word_at(region, 0x2c) == 0xe590'f000U, "puis saute au gestionnaire du jeu");
        check(word_at(region, 0x30) == 0xe8bd'500fU, "rend les registres empilés");
        check(word_at(region, 0x34) == 0xe25e'f004U, "et reprend l'instruction interrompue");
    }

    check(
        word_at(console.machine.secondary_memory().bios(), 0x38) ==
            Bios::secondary_handler_address,
        "le littéral porte la place où le jeu range son gestionnaire"
    );
}

/**
 * Les numéros d'appel sont ceux du matériel.
 *
 * Écrits en toutes lettres : les reprendre des constantes qui les définissent ne
 * prouverait rien, les deux bougeant ensemble. Un numéro décalé donne un jeu qui
 * demande une division et obtient une racine.
 */
void les_numeros_d_appel_sont_ceux_du_materiel() {
    check(Bios::call_soft_reset == 0x00U, "relance");
    check(Bios::call_wait_by_loop == 0x03U, "attente par boucle");
    check(Bios::call_interrupt_wait == 0x04U, "attente d'interruption");
    check(Bios::call_vertical_blank_wait == 0x05U, "attente du retour vertical");
    check(Bios::call_halt == 0x06U, "arrêt");
    check(Bios::call_divide == 0x09U, "division");
    check(Bios::call_copy == 0x0bU, "recopie");
    check(Bios::call_fast_copy == 0x0cU, "recopie rapide");
    check(Bios::call_square_root == 0x0dU, "racine");
    check(Bios::call_checksum == 0x0eU, "somme de contrôle");
    check(Bios::call_is_debugger == 0x0fU, "matériel de mise au point");
    check(Bios::call_bit_unpack == 0x10U, "dépaquetage de bits");
    check(Bios::call_lz77_to_memory == 0x11U, "distance, vers la mémoire");
    check(Bios::call_lz77_to_video == 0x12U, "distance, vers la vidéo");
    check(Bios::call_huffman == 0x13U, "arbre de codage");
    check(Bios::call_run_length_to_memory == 0x14U, "répétitions, vers la mémoire");
    check(Bios::call_run_length_to_video == 0x15U, "répétitions, vers la vidéo");
    check(Bios::call_unfilter_8bit_to_memory == 0x16U, "défiltrage huit bits");
    check(Bios::call_unfilter_8bit_to_video == 0x17U, "défiltrage huit bits, vers la vidéo");
    check(Bios::call_unfilter_16bit == 0x18U, "défiltrage seize bits");
}

/** Où chaque processeur cherche le gestionnaire du jeu. */
void chaque_processeur_a_sa_place_de_rendez_vous() {
    Console console;
    check(
        console.bios(Processor::secondary).interrupt_handler_address() == 0x0380'fffcU,
        "le secondaire, en bout de sa mémoire propre"
    );
    check(
        console.bios(Processor::secondary).interrupt_flags_address() == 0x0380'fff8U,
        "et le mot d'indicateurs juste avant"
    );

    // Le principal suit sa mémoire locale de données, qui se déplace. Le champ
    // de taille vaut 0x0a, soit seize kilooctets, à la base 0x0b00'0000.
    console.machine.cp15().write(0U, 9U, 1U, 0U, 0x0b00'000aU);
    check(
        console.bios(Processor::main).interrupt_handler_address() == 0x0b00'3ffcU,
        "le principal, en bout de sa mémoire locale de données"
    );
    check(
        console.bios(Processor::main).interrupt_flags_address() == 0x0b00'3ff8U,
        "avec le mot d'indicateurs juste avant, là aussi"
    );

    // Et la place suit vraiment la mémoire locale : la déplacer déplace le
    // rendez-vous, ce qui est tout l'intérêt de la lui demander.
    console.machine.cp15().write(0U, 9U, 1U, 0U, 0x0300'000aU);
    check(
        console.bios(Processor::main).interrupt_handler_address() == 0x0300'3ffcU,
        "elle suit la mémoire locale là où le jeu la met"
    );
}

/**
 * Le rendez-vous écrit dans la région suit la mémoire locale, et non seulement
 * celui que l’organe calcule.
 *
 * La vérification voisine contrôlait l’accesseur, qui a toujours dit vrai. Le
 * défaut était ailleurs : le littéral que le gestionnaire lit vraiment était
 * écrit une seule fois, à l’installation, c’est-à-dire à la remise à zéro. Le
 * jeu place sa mémoire locale bien après ; le littéral gardait donc l’adresse
 * d’avant, qui ne désigne rien.
 *
 * Le gestionnaire cherchait alors son pointeur à l’adresse zéro et sautait où
 * ce qui s’y trouve l’envoyait. L’interruption n’étant jamais acquittée, elle
 * repartait aussitôt, et le processeur principal passait sa trame entière dans
 * ce tourniquet. Un vrai jeu l’a montré : mode interruption, un million cent
 * vingt mille instructions, et pas un pixel.
 *
 * Les adresses sont écrites en toutes lettres.
 */
void le_rendez_vous_ecrit_suit_la_memoire_locale() {
    Console console;
    // Le gestionnaire tient six instructions à partir du décalage 0x20 ; le
    // littéral les suit immédiatement, à 0x38 de la base des vecteurs.
    constexpr std::uint32_t literal = 0xffff'0038;

    // À la mise sous tension, la mémoire locale fait cinq cents octets à
    // l'adresse zéro : le rendez-vous tombe donc à 0x1FC, une adresse
    // plausible et vide de sens. C'est ce qui rendait le défaut si discret —
    // le gestionnaire ne sautait pas à zéro, il sautait à ce qu'un jeu avait
    // laissé traîner là.
    check(console.memory().read32(literal) == 0x0000'01fcU, "le rendez-vous de la mise sous tension");

    // Le jeu pose sa mémoire locale : seize kilooctets à 0x0200'0000.
    console.machine.cp15().write(0U, 9U, 1U, 0U, 0x0200'000aU);
    console.run_frame();
    check(
        console.memory().read32(literal) == 0x0200'3ffcU,
        "le littéral suit la mémoire locale dès que la console tourne"
    );

    // Et il la suit encore quand elle se déplace.
    console.machine.cp15().write(0U, 9U, 1U, 0U, 0x0b00'000aU);
    console.run_frame();
    check(
        console.memory().read32(literal) == 0x0b00'3ffcU,
        "et il la suit quand elle se déplace"
    );

    // Ce que le littéral porte est bien ce que l’organe calcule : les deux
    // étaient d’accord en théorie et brouillés en pratique.
    check(
        console.memory().read32(literal) ==
            console.bios(Processor::main).interrupt_handler_address(),
        "les deux disent la même chose"
    );
}

/** La division, telle que le programme d'amorçage la rend. */
void la_division_rend_quotient_reste_et_valeur_absolue() {
    Console console;
    console.reg(0) = static_cast<std::uint32_t>(-17);
    console.reg(1) = 5U;
    console.call(Bios::call_divide);
    check(static_cast<std::int32_t>(console.reg(0)) == -3, "le quotient est tronqué vers zéro");
    check(static_cast<std::int32_t>(console.reg(1)) == -2, "le reste garde le signe du numérateur");
    check(console.reg(3) == 3U, "et le troisième registre porte la valeur absolue du quotient");

    // Une division par zéro n'a pas de résultat défini : les registres sont
    // laissés tels quels plutôt que remplis d'une valeur inventée.
    console.reg(0) = 42U;
    console.reg(1) = 0U;
    console.call(Bios::call_divide);
    check(console.reg(0) == 42U, "une division par zéro ne rend rien");

    // La seule division qui déborde sur trente-deux bits.
    console.reg(0) = 0x8000'0000U;
    console.reg(1) = static_cast<std::uint32_t>(-1);
    console.call(Bios::call_divide);
    check(console.reg(0) == 0x8000'0000U, "le plus petit entier divisé par moins un ne déborde pas");
}

/** La racine entière, sans passer par un flottant dont l'arrondi varierait. */
void la_racine_est_entiere_et_par_defaut() {
    Console console;
    for (const auto& [value, root] :
         std::initializer_list<std::pair<std::uint32_t, std::uint32_t>>{
             {0U, 0U}, {1U, 1U}, {2U, 1U}, {3U, 1U}, {4U, 2U}, {80U, 8U}, {81U, 9U},
             {0xffff'ffffU, 0xffffU}, {0xfffe'0001U, 0xffffU}, {0xfffe'0000U, 0xfffeU},
         }) {
        console.reg(0) = value;
        check(console.call(Bios::call_square_root) == root, "racine entière");
    }
}

/** La somme de contrôle, avec sa valeur de départ venue du jeu. */
void la_somme_de_controle_repart_de_ce_qu_on_lui_donne() {
    Console console;
    console.write_bytes(scratch, {'1', '2', '3', '4', '5', '6', '7', '8', '9'});

    console.reg(0) = 0xffffU;
    console.reg(1) = scratch;
    console.reg(2) = 9U;
    // Valeur publiée du CRC-16/MODBUS pour la chaîne « 123456789 ».
    check(console.call(Bios::call_checksum) == 0x4b37U, "somme d'un vecteur connu");

    // En deux morceaux, la seconde moitié repartant de la première : le total
    // doit être le même, et c'est à cela que sert la valeur de départ.
    console.reg(0) = 0xffffU;
    console.reg(1) = scratch;
    console.reg(2) = 4U;
    const auto partial = console.call(Bios::call_checksum);
    console.reg(0) = partial;
    console.reg(1) = scratch + 4U;
    console.reg(2) = 5U;
    check(console.call(Bios::call_checksum) == 0x4b37U, "un contenu découpé donne la même somme");
}

/** La recopie, dans ses deux largeurs et sa forme à source figée. */
void la_recopie_suit_sa_commande() {
    Console console;
    // Seize demi-mots, soit huit mots : de quoi éprouver l'arrondi de la
    // recopie rapide, qui sert toujours des groupes de huit.
    for (std::uint32_t index = 0; index < 16U; ++index) {
        console.memory().write16(scratch + index * 2U, static_cast<std::uint16_t>(0x1000U + index));
    }

    console.reg(0) = scratch;
    console.reg(1) = scratch_output;
    console.reg(2) = 4U;                                  // quatre demi-mots
    console.call(Bios::call_copy);
    check(console.memory().read16(scratch_output) == 0x1000U, "le premier demi-mot est recopié");
    check(console.memory().read16(scratch_output + 6U) == 0x1003U, "et le quatrième aussi");
    check(console.memory().read16(scratch_output + 8U) == 0U, "le cinquième ne l'est pas");

    // Source figée : la même valeur remplit l'arrivée.
    console.reg(0) = scratch;
    console.reg(1) = scratch_output;
    console.reg(2) = 3U | (1U << 24U) | (1U << 26U);      // trois mots, source figée
    console.call(Bios::call_copy);
    check(console.memory().read32(scratch_output) == 0x1001'1000U, "le mot lu une fois");
    check(console.memory().read32(scratch_output + 8U) == 0x1001'1000U, "est écrit partout");

    // La recopie rapide ne connaît que les mots, et arrondit au groupe de huit.
    console.reg(0) = scratch;
    console.reg(1) = scratch_output;
    console.reg(2) = 1U;
    console.call(Bios::call_fast_copy);
    check(
        console.memory().read32(scratch_output + 28U) == 0x100f'100eU,
        "un mot demandé en fait recopier huit"
    );
}

/** Le dépaquetage de bits, avec son décalage et son traitement du zéro. */
void le_depaquetage_de_bits_elargit_chaque_unite() {
    Console console;
    // Deux octets, quatre unités de quatre bits chacune : 1, 0, 3, 2.
    console.write_bytes(scratch, {0x01, 0x23});
    console.memory().write16(scratch + 0x40U, 2U);        // deux octets d'entrée
    console.memory().write8(scratch + 0x42U, 4U);         // unités de quatre bits
    console.memory().write8(scratch + 0x43U, 8U);         // sorties de huit bits
    console.memory().write32(scratch + 0x44U, 0x10U);     // décalage, zéro laissé nu
    // Une sentinelle après la sortie attendue : sans elle, un mot écrit en trop
    // se confondrait avec la mémoire vierge.
    console.memory().write32(scratch_output + 4U, 0xdead'beefU);

    console.reg(0) = scratch;
    console.reg(1) = scratch_output;
    console.reg(2) = scratch + 0x40U;
    console.call(Bios::call_bit_unpack);
    check(console.memory().read32(scratch_output) == 0x1213'0011U, "chaque unité est élargie");
    check(
        console.memory().read32(scratch_output + 4U) == 0xdead'beefU,
        "et rien n'est écrit au-delà"
    );

    // Le bit haut du décalage le fait aussi porter sur les unités nulles.
    console.memory().write32(scratch_output + 4U, 0xdead'beefU);
    console.memory().write32(scratch + 0x44U, 0x10U | (1U << 31U));
    console.reg(0) = scratch;
    console.reg(1) = scratch_output;
    console.reg(2) = scratch + 0x40U;
    console.call(Bios::call_bit_unpack);
    check(console.memory().read32(scratch_output) == 0x1213'1011U, "le zéro reçoit le décalage aussi");
    check(
        console.memory().read32(scratch_output + 4U) == 0xdead'beefU,
        "sans écrire au-delà là non plus"
    );

    // Des largeurs que le matériel ne définit pas sont refusées plutôt que
    // devinées : deviner produirait une image plausible et fausse.
    for (const auto& [source_width, destination_width] :
         std::initializer_list<std::pair<std::uint32_t, std::uint32_t>>{
             {0U, 8U}, {16U, 16U}, {8U, 4U}, {4U, 0U},
         }) {
        Console refused;
        refused.memory().write16(scratch + 0x40U, 2U);
        refused.memory().write8(scratch + 0x42U, static_cast<std::uint8_t>(source_width));
        refused.memory().write8(scratch + 0x43U, static_cast<std::uint8_t>(destination_width));
        refused.memory().write32(scratch + 0x44U, 0U);
        refused.memory().write32(scratch_output, 0xdead'beefU);
        refused.reg(0) = scratch;
        refused.reg(1) = scratch_output;
        refused.reg(2) = scratch + 0x40U;
        refused.call(Bios::call_bit_unpack);
        check(
            refused.memory().read32(scratch_output) == 0xdead'beefU,
            "une largeur impossible n'écrit rien"
        );
        check(refused.bios().unsupported_count() == 1U, "et elle est comptée");
    }
}

/** La décompression par répétitions. */
void la_decompression_par_repetitions() {
    Console console;
    // En-tête : type 3 en bits hauts de l'octet bas, longueur sur trois octets.
    console.memory().write32(scratch, 0x30U | (8U << 8U));
    console.write_bytes(
        scratch + 4U,
        {0x82, 0xaa,           // cinq octets 0xAA
         0x00, 0xbb,           // un seul octet littéral
         0x81, 0xcc}           // puis quatre octets 0xCC
    );
    console.memory().write8(scratch_output + 8U, 0x5aU);
    console.reg(0) = scratch;
    console.reg(1) = scratch_output;
    console.call(Bios::call_run_length_to_memory);
    check(console.memory().read32(scratch_output) == 0xaaaa'aaaaU, "la répétition remplit");
    check(console.memory().read8(scratch_output + 4U) == 0xaaU, "jusqu'à sa longueur");
    // Le littéral ne compte qu'un octet : en compter trois avalerait la commande
    // suivante, et la répétition qui suit ne commencerait pas où il faut.
    check(console.memory().read8(scratch_output + 5U) == 0xbbU, "puis le littéral, un seul");
    check(console.memory().read16(scratch_output + 6U) == 0xccccU, "et la répétition suivante");
    check(console.memory().read8(scratch_output + 8U) == 0x5aU, "sans déborder");
}

/** La décompression par distance et longueur. */
void la_decompression_par_distance() {
    Console console;
    console.memory().write32(scratch, 0x10U | (8U << 8U));
    console.write_bytes(
        scratch + 4U,
        {
            0x30,                       // deux littéraux, puis deux références
            'a', 'b',
            0x00, 0x01,                 // longueur 3, distance 2
            0x00, 0x02,                 // longueur 3, distance 3
        }
    );
    // Une sentinelle après la sortie annoncée : sans elle, une longueur non
    // bornée écrirait plus loin sans que rien ne le dise.
    console.memory().write8(scratch_output + 8U, 0x5aU);
    console.reg(0) = scratch;
    console.reg(1) = scratch_output;
    console.call(Bios::call_lz77_to_memory);
    check(
        console.memory().read8(scratch_output + 8U) == 0x5aU,
        "la longueur annoncée borne la sortie"
    );
    // La première référence dépasse ce qui existait quand elle a commencé :
    // elle recopie des octets qu'elle vient elle-même d'écrire, et c'est ce que
    // le format autorise. Une mise en œuvre qui lirait la source d'un bloc avant
    // de l'écrire donnerait « ababab » ici.
    const std::string_view expected = "ababaaba";
    for (std::size_t index = 0; index < expected.size(); ++index) {
        check(
            console.memory().read8(scratch_output + static_cast<std::uint32_t>(index)) ==
                static_cast<std::uint8_t>(expected[index]),
            "la référence recopie ce qui vient d'être écrit"
        );
    }

    // Une distance qui remonte avant le début de la sortie ne désigne rien : le
    // flux est corrompu, et poursuivre lirait de la mémoire au hasard.
    //
    // Ce qui précède la sortie est **rempli** avant l'essai : la laisser vierge
    // ferait recopier des zéros, indiscernables d'un refus.
    for (std::uint32_t back = 1U; back <= 32U; ++back) {
        console.memory().write8(scratch_output - back, 0xccU);
    }
    console.memory().write32(scratch, 0x10U | (4U << 8U));
    console.write_bytes(scratch + 4U, {0x80, 0x00, 0x10});
    console.memory().write8(scratch_output, 0U);
    console.reg(0) = scratch;
    console.reg(1) = scratch_output;
    console.call(Bios::call_lz77_to_memory);
    check(console.memory().read8(scratch_output) == 0U, "un flux corrompu s'arrête net");
}

/** Le défiltrage, qui n'écrit que des différences. */
void le_defiltrage_additionne_les_differences() {
    Console console;
    console.memory().write32(scratch, 0x80U | (4U << 8U));
    console.write_bytes(scratch + 4U, {0x05, 0x03, 0xfe, 0x01});
    console.reg(0) = scratch;
    console.reg(1) = scratch_output;
    console.call(Bios::call_unfilter_8bit_to_memory);
    // 05, puis 05+03, puis 08+FE qui se replie, puis 06+01.
    check(console.memory().read32(scratch_output) == 0x0706'0805U, "chaque octet ajoute le précédent");

    console.memory().write32(scratch, 0x81U | (4U << 8U));
    console.memory().write16(scratch + 4U, 0x0100U);
    console.memory().write16(scratch + 6U, 0xff00U);
    console.memory().write16(scratch_output + 4U, 0x5a5aU);
    console.reg(0) = scratch;
    console.reg(1) = scratch_output;
    console.call(Bios::call_unfilter_16bit);
    check(console.memory().read16(scratch_output) == 0x0100U, "en demi-mots aussi");
    check(console.memory().read16(scratch_output + 2U) == 0x0000U, "et la somme se replie");
    // Quatre octets font deux demi-mots : en écrire trois déborderait, et la
    // longueur se compte bien en octets.
    check(console.memory().read16(scratch_output + 4U) == 0x5a5aU, "et deux demi-mots, pas trois");
}

/** Un appel que cet organe ne couvre pas est compté, et rend la main. */
void un_appel_inconnu_est_compte_et_rend_la_main() {
    Console console;
    constexpr std::uint32_t unknown = 0x7fU;
    check(
        console.bios().handle_software_interrupt(unknown),
        "l’appel inconnu ne redescend pas au vecteur"
    );
    check(console.bios().unsupported_count() == 1U, "il est compté");
    check(console.bios().first_unsupported() == unknown, "et son numéro retenu");
}

/** Une attente sans source ne s'endort pas. */
void une_attente_sans_source_ne_s_endort_pas() {
    Console console;
    console.reg(0, Processor::secondary) = 1U;
    console.reg(1, Processor::secondary) = 0U;
    console.call(Bios::call_interrupt_wait, Processor::secondary);
    check(
        !console.machine.core(Processor::secondary).halted(),
        "sans source à attendre, l'appel rend la main"
    );
    check(!console.bios(Processor::secondary).waiting(), "et rien n'est mis en attente");
    check(
        console.machine.interrupts(Processor::secondary).master_enable() == 1U,
        "l'attente rallume tout de même l'autorisation générale"
    );
}

/**
 * Une attente s'achève sur le mot d'indicateurs du jeu, et sur lui seul.
 *
 * C'est ce qui distingue cette attente d'un simple arrêt : sans cette
 * indirection, elle se terminerait sur n'importe quelle interruption, et un jeu
 * qui attend le retour vertical repartirait sur un débordement de minuterie.
 */
void une_attente_s_acheve_sur_le_mot_du_jeu() {
    Console console;
    auto& bios = console.bios(Processor::secondary);
    auto& memory = console.machine.secondary_memory();

    console.reg(0, Processor::secondary) = 1U;
    console.reg(1, Processor::secondary) = InterruptController::vertical_blank;
    console.call(Bios::call_interrupt_wait, Processor::secondary);
    check(console.machine.core(Processor::secondary).halted(), "le processeur s'arrête");
    check(bios.waiting(), "et l'attente est retenue");

    // Une autre source posée dans le mot ne la termine pas.
    memory.write32(bios.interrupt_flags_address(), InterruptController::horizontal_blank);
    console.reg(0, Processor::secondary) = 1U;
    console.reg(1, Processor::secondary) = InterruptController::vertical_blank;
    console.call(Bios::call_interrupt_wait, Processor::secondary);
    check(bios.waiting(), "une autre source ne termine pas l'attente");
    check(
        memory.read32(bios.interrupt_flags_address()) == InterruptController::horizontal_blank,
        "et elle reste posée pour qui l'attend"
    );

    // La sienne, oui, et elle est consommée.
    memory.write32(
        bios.interrupt_flags_address(),
        InterruptController::horizontal_blank | InterruptController::vertical_blank
    );
    console.reg(0, Processor::secondary) = 1U;
    console.reg(1, Processor::secondary) = InterruptController::vertical_blank;
    console.call(Bios::call_interrupt_wait, Processor::secondary);
    check(!bios.waiting(), "sa source termine l'attente");
    check(
        memory.read32(bios.interrupt_flags_address()) == InterruptController::horizontal_blank,
        "seul le bit attendu est consommé"
    );
}

/**
 * Le premier registre décide du sort des anciens indicateurs.
 *
 * Les deux formes ne se distinguent que sur un indicateur **déjà posé**. Le
 * rejet l'écarte et fait attendre le suivant ; sans rejet, il termine l'attente
 * sur-le-champ. Confondre les deux fait croire à un jeu qu'il a vu passer un
 * retour vertical qui datait de la trame précédente.
 */
void le_rejet_des_anciens_indicateurs_se_demande() {
    {
        Console console;
        auto& bios = console.bios(Processor::secondary);
        console.machine.secondary_memory().write32(
            bios.interrupt_flags_address(), InterruptController::vertical_blank);
        console.reg(0, Processor::secondary) = 1U;
        console.reg(1, Processor::secondary) = InterruptController::vertical_blank;
        console.call(Bios::call_interrupt_wait, Processor::secondary);
        check(bios.waiting(), "avec rejet, un indicateur déjà posé est écarté");
        check(
            console.machine.secondary_memory().read32(bios.interrupt_flags_address()) == 0U,
            "et effacé du mot du jeu"
        );
    }
    {
        Console console;
        auto& bios = console.bios(Processor::secondary);
        console.machine.secondary_memory().write32(
            bios.interrupt_flags_address(), InterruptController::vertical_blank);
        console.reg(0, Processor::secondary) = 0U;
        console.reg(1, Processor::secondary) = InterruptController::vertical_blank;
        console.call(Bios::call_interrupt_wait, Processor::secondary);
        check(!bios.waiting(), "sans rejet, il termine l'attente aussitôt");
        check(
            !console.machine.core(Processor::secondary).halted(),
            "et le processeur ne s'arrête pas"
        );
    }
}

/**
 * Un appel non couvert ne coûte que cet appel : le programme continue.
 *
 * Vu depuis le programme, et non depuis l’organe : l’appeler directement
 * n’éprouverait pas le chemin que suit le processeur.
 *
 * Cette vérification a d’abord dit le contraire. L’appel redescendait au
 * vecteur, où la table posée par cet organe porte un branchement sur lui-même :
 * un seul service manquant arrêtait donc le processeur pour de bon. Un vrai jeu
 * l’a montré : processeur secondaire figé à l’adresse huit, processeur
 * principal tournant dans le vide en l’attendant. Un service qui manque doit
 * coûter ce service, pas la console.
 *
 * Le registre écrit après l’appel est la preuve du passage : le lire prouve que
 * l’instruction suivante a bien été atteinte, ce qu’une adresse de retour ne
 * dirait qu’à demi.
 */
void un_appel_non_couvert_laisse_le_programme_continuer() {
    Console console;
    constexpr std::uint32_t unknown = 0x7fU;
    constexpr std::uint32_t witness = 0x2aU;
    console.cartridge.set_code(arm7_rom_offset, {
        software_interrupt(unknown),
        mov_immediate(0U, witness),
        branch(0),
    });
    console.boot();
    console.run_frame();

    const auto& state = console.machine.core(Processor::secondary).state();
    check(state.registers[0] == witness, "l’instruction suivant l’appel a été exécutée");
    check(
        state.registers[15] != ArmCore::software_interrupt_vector,
        "et le programme ne s’est pas arrêté sur le vecteur"
    );
    check(console.bios(Processor::secondary).unsupported_count() == 1U, "l'appel est compté");
    check(console.bios(Processor::secondary).first_unsupported() == unknown, "et son numéro retenu");
}

/**
 * Un appel logiciel écrit en Thumb porte son numéro ailleurs, et l'appel se
 * repose la question à la bonne adresse.
 *
 * Le compteur de programme n'a pas la même avance dans les deux jeux
 * d'instructions. Un rembobinage qui l'ignorerait ferait repartir l'attente
 * quatre octets trop loin, c'est-à-dire au milieu du programme.
 */
void un_appel_ecrit_en_thumb_est_servi_de_meme() {
    Console console;
    constexpr std::uint32_t thumb_entry = private_wram_base + 0x300;

    // Le programme ARM passe en Thumb, où il demande l'attente.
    console.cartridge.set_code(arm7_rom_offset, {
        mov_immediate(0U, 0x0eU, 10U),                 // r0 = 0x0380'0000
        add_immediate(1U, 0U, 0x03U, 24U),             // r1 = 0x0380'0300
        or_immediate(1U, 1U, 1U),                      // le bit bas demande Thumb
        (always << 28U) | 0x012f'ff10U | 1U,           // BX r1
        branch(0),
    });
    // Deux instructions Thumb : l'appel, puis une boucle sur place.
    console.cartridge.write_u32(
        arm7_rom_offset + (thumb_entry - private_wram_base),
        0xdf05U | (0xe7feU << 16U)                     // SWI 5 ; B .
    );
    console.boot();
    console.run_frame();

    check(
        console.machine.core(Processor::secondary).state().thumb(),
        "le processeur est bien passé en Thumb"
    );
    check(
        console.machine.core(Processor::secondary).state().registers[15] == thumb_entry,
        "et l'appel s'est reposé sur lui-même, non deux octets plus loin"
    );
    check(console.bios(Processor::secondary).waiting(), "l'attente est en cours");
}

/**
 * Chaque processeur est servi par son propre organe.
 *
 * Les deux se ressemblent assez pour qu'un croisement passe inaperçu sur la
 * plupart des services. Il ne passe pas sur l'attente : les deux processeurs ne
 * cherchent pas le mot d'indicateurs à la même place, et c'est l'organe du
 * processeur qui appelle qui doit se mettre en attente.
 */
void chaque_processeur_est_servi_par_son_organe() {
    Console console;
    console.cartridge.set_code(arm9_rom_offset, {
        software_interrupt(Bios::call_vertical_blank_wait),
        branch(0),
    });
    console.boot();
    console.run_frame();

    check(console.bios(Processor::main).waiting(), "l'organe du principal attend");
    check(
        !console.bios(Processor::secondary).waiting(),
        "et celui du secondaire, que rien n'a appelé, non"
    );
}

/**
 * Un jeu qui attend le retour vertical repart, et la suite de son programme
 * s'exécute.
 *
 * C'est la vérification qui traverse tout : l'appel logiciel, l'arrêt du
 * processeur, le réveil, le vecteur d'interruption, le gestionnaire écrit par le
 * jeu, le retour à l'appel, et la reprise du programme. Chacun de ces maillons
 * manquant, le marqueur final n'apparaît pas.
 */
void un_jeu_qui_attend_le_retour_vertical_repart() {
    Console console;

    constexpr std::uint32_t marker = private_wram_base + 0x100;
    constexpr std::uint32_t handler_entry = private_wram_base + 0x200;
    constexpr std::uint32_t supervisor_with_interrupts = 0x13;
    constexpr std::uint32_t interrupt_mode_masked = 0x92;

    console.cartridge.set_code(arm7_rom_offset, {
        mov_immediate(0U, 0x0eU, 10U),                 // r0 = 0x0380'0000
        mov_immediate(3U, 0x0fU, 20U),                 // r3 = 0x0000'f000
        add_register(6U, 0U, 3U),                      // r6 = 0x0380'f000
        mov_immediate(3U, 0x0eU, 20U),                 // r3 = 0x0000'e000
        add_register(5U, 0U, 3U),                      // r5 = 0x0380'e000

        // Deux piles : celle du mode d'interruption, où le vecteur empile, et
        // celle du programme. Sans la première, le gestionnaire empilerait à
        // l'adresse zéro et ne saurait plus où revenir.
        set_status(interrupt_mode_masked),
        move_register(13U, 6U),
        set_status(supervisor_with_interrupts),
        move_register(13U, 5U),

        // L'adresse du gestionnaire du jeu, là où le programme d'amorçage la
        // cherche : en bout de la mémoire propre de ce processeur.
        add_immediate(2U, 0U, 0x02U, 24U),             // r2 = 0x0380'0200
        transfer(false, 6U, 2U, 0xffcU),

        // Trois autorisations, et il les faut toutes les trois : l'écran doit
        // accepter de signaler son retour vertical, le contrôleur d'accepter
        // cette source, et l'autorisation générale d'être posée.
        mov_immediate(4U, 0x04U, 8U),                  // r4 = 0x0400'0000
        mov_immediate(3U, DisplayController::vertical_blank_interrupt),
        transfer(false, 4U, 3U, 0x004U),
        mov_immediate(3U, 1U),
        transfer(false, 4U, 3U, 0x210U),
        transfer(false, 4U, 3U, 0x208U),

        software_interrupt(Bios::call_vertical_blank_wait),

        // Repris : une trace que rien d'autre dans ce programme ne pourrait
        // laisser, l'instruction qui la pose étant après l'attente.
        mov_immediate(5U, 0xa5U),
        transfer(false, 0U, 5U, 0x100U),
        branch(0),
    });

    // Le gestionnaire du jeu. Il pose son bit dans le mot d'indicateurs, sans
    // lequel l'attente ne s'achèverait pas, puis acquitte la demande : la
    // laisser posée le rappellerait sans fin.
    console.cartridge.set_code(arm7_rom_offset + (handler_entry - private_wram_base), {
        mov_immediate(0U, 0x0eU, 10U),                 // r0 = 0x0380'0000
        mov_immediate(3U, 0x0fU, 20U),                 // r3 = 0x0000'f000
        add_register(0U, 0U, 3U),                      // r0 = 0x0380'f000
        transfer(true, 0U, 1U, 0xff8U),
        or_immediate(1U, 1U, 1U),
        transfer(false, 0U, 1U, 0xff8U),
        mov_immediate(2U, 0x04U, 8U),
        mov_immediate(3U, 1U),
        transfer(false, 2U, 3U, 0x214U),               // acquitte le retour vertical
        return_to_link,
    });
    console.boot();

    check(console.machine.secondary_memory().read8(marker) == 0U, "rien n'est écrit avant");
    console.run_frame();

    check(
        console.machine.secondary_memory().read8(marker) == 0xa5U,
        "le programme a repris après l'attente"
    );
    check(
        !console.machine.core(Processor::secondary).halted(),
        "et le processeur n'est plus arrêté"
    );
    check(
        !console.bios(Processor::secondary).waiting(),
        "l'attente est consommée, non laissée en suspens"
    );
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    la_table_des_vecteurs_est_ecrite();
    chaque_processeur_a_sa_place_de_rendez_vous();
    le_rendez_vous_ecrit_suit_la_memoire_locale();
    la_division_rend_quotient_reste_et_valeur_absolue();
    la_racine_est_entiere_et_par_defaut();
    la_somme_de_controle_repart_de_ce_qu_on_lui_donne();
    la_recopie_suit_sa_commande();
    le_depaquetage_de_bits_elargit_chaque_unite();
    la_decompression_par_repetitions();
    la_decompression_par_distance();
    le_defiltrage_additionne_les_differences();
    les_numeros_d_appel_sont_ceux_du_materiel();
    un_appel_inconnu_est_compte_et_rend_la_main();
    le_rejet_des_anciens_indicateurs_se_demande();
    un_appel_non_couvert_laisse_le_programme_continuer();
    un_appel_ecrit_en_thumb_est_servi_de_meme();
    chaque_processeur_est_servi_par_son_organe();
    une_attente_sans_source_ne_s_endort_pas();
    une_attente_s_acheve_sur_le_mot_du_jeu();
    un_jeu_qui_attend_le_retour_vertical_repart();
    return 0;
}
