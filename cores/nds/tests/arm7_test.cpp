#include "cpu/arm_core.hpp"

#include "check.hpp"

#include <array>
#include <cstdint>
#include <initializer_list>
#include <string>

/**
 * Processeur secondaire ARM7TDMI, jeu ARMv4T.
 *
 * Une seule implémentation sert les deux processeurs de la console. Cette suite
 * n'a donc pas à réexaminer ce que les suites du processeur principal ont déjà
 * établi : elle éprouve **ce qui les sépare**, et rien d'autre. Trois choses,
 * dans l'ordre où elles font tomber un jeu écrit pour l'un et exécuté sur
 * l'autre :
 *
 * - les instructions qui n'existent pas ici, et qui doivent lever l'exception
 *   d'instruction indéfinie plutôt que d'être exécutées comme sur le grand
 *   frère;
 * - l'absence de coprocesseur, donc de mémoires locales, de vecteurs
 *   déplaçables et d'attente d'interruption;
 * - le chargement du compteur de programme, qui n'entrelace pas.
 *
 * Un dernier ensemble vérifie que le tronc commun, lui, fonctionne bien : sans
 * cela, « le jeu réduit » pourrait vouloir dire « rien ne marche ».
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

/** Mémoire plate de 64 Kio, en petit-boutiste comme la console. */
class TestBus final : public Bus {
public:
    static constexpr std::uint32_t size = 0x1'0000;

    [[nodiscard]] std::uint8_t read8(std::uint32_t address) override {
        return memory_[address % size];
    }

    [[nodiscard]] std::uint16_t read16(std::uint32_t address) override {
        return static_cast<std::uint16_t>(
            static_cast<std::uint32_t>(read8(address)) |
            (static_cast<std::uint32_t>(read8(address + 1U)) << 8U)
        );
    }

    [[nodiscard]] std::uint32_t read32(std::uint32_t address) override {
        return static_cast<std::uint32_t>(read16(address)) |
            (static_cast<std::uint32_t>(read16(address + 2U)) << 16U);
    }

    void write8(std::uint32_t address, std::uint8_t value) override {
        memory_[address % size] = value;
    }

    void write16(std::uint32_t address, std::uint16_t value) override {
        write8(address, static_cast<std::uint8_t>(value & 0xffU));
        write8(address + 1U, static_cast<std::uint8_t>((value >> 8U) & 0xffU));
    }

    void write32(std::uint32_t address, std::uint32_t value) override {
        write16(address, static_cast<std::uint16_t>(value & 0xffffU));
        write16(address + 2U, static_cast<std::uint16_t>((value >> 16U) & 0xffffU));
    }

private:
    std::array<std::uint8_t, size> memory_{};
};

constexpr std::uint32_t program_base = 0x0000'0100;
constexpr std::uint32_t data_base = 0x0000'0800;
constexpr std::uint32_t always = 0xeU;

/** `cond 001 op S Rn Rd rot imm8` : traitement de données à opérande immédiate. */
constexpr std::uint32_t data_immediate(
    std::uint32_t op,
    bool set_flags,
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t value
) noexcept {
    return (always << 28U) | (1U << 25U) | (op << 21U) | (set_flags ? (1U << 20U) : 0U) |
        (rn << 16U) | (rd << 12U) | value;
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

constexpr std::uint32_t block_transfer(
    std::uint32_t rn,
    std::uint32_t list,
    bool load,
    bool pre,
    bool up,
    bool writeback,
    bool user_bank = false
) noexcept {
    return (always << 28U) | (0x4U << 25U) | (pre ? (1U << 24U) : 0U) | (up ? (1U << 23U) : 0U) |
        (user_bank ? (1U << 22U) : 0U) | (writeback ? (1U << 21U) : 0U) | (load ? (1U << 20U) : 0U) |
        (rn << 16U) | list;
}

constexpr std::uint32_t branch_exchange(std::uint32_t rm, bool link) noexcept {
    return (always << 28U) | 0x012f'ff10U | (link ? (1U << 5U) : 0U) | rm;
}

constexpr std::uint32_t branch(std::int32_t words, bool link) noexcept {
    return (always << 28U) | (0x5U << 25U) | (link ? (1U << 24U) : 0U) |
        (static_cast<std::uint32_t>(words) & 0x00ff'ffffU);
}

/** `1111 101 H offset` : appel avec échange, absent d'ARMv4T. */
constexpr std::uint32_t branch_link_exchange(std::int32_t words) noexcept {
    return (0xfU << 28U) | (0x5U << 25U) | (static_cast<std::uint32_t>(words) & 0x00ff'ffffU);
}

constexpr std::uint32_t count_leading_zeros(std::uint32_t rd, std::uint32_t rm) noexcept {
    return (always << 28U) | 0x016f'0f10U | (rd << 12U) | rm;
}

constexpr std::uint32_t saturating(std::uint32_t op, std::uint32_t rn, std::uint32_t rd, std::uint32_t rm) noexcept {
    return (always << 28U) | (1U << 24U) | (op << 21U) | (rn << 16U) | (rd << 12U) | (0x5U << 4U) | rm;
}

/** `cond 000 P U I W L Rn Rd offhi 1 S H 1 offlo`. */
constexpr std::uint32_t halfword_transfer(
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t offset,
    std::uint32_t kind,
    bool load
) noexcept {
    return (always << 28U) | (1U << 24U) | (1U << 23U) | (1U << 22U) |
        (load ? (1U << 20U) : 0U) | (rn << 16U) | (rd << 12U) |
        (((offset >> 4U) & 0xfU) << 8U) | (1U << 7U) | (kind << 5U) | (1U << 4U) | (offset & 0xfU);
}

constexpr std::uint32_t swap(std::uint32_t rn, std::uint32_t rd, std::uint32_t rm, bool byte_access) noexcept {
    return (always << 28U) | (1U << 24U) | (byte_access ? (1U << 22U) : 0U) |
        (rn << 16U) | (rd << 12U) | (0x9U << 4U) | rm;
}

constexpr std::uint32_t multiply(std::uint32_t rd, std::uint32_t rs, std::uint32_t rm) noexcept {
    return (always << 28U) | (rd << 16U) | (rs << 8U) | (0x9U << 4U) | rm;
}

constexpr std::uint32_t move_from_psr(std::uint32_t rd) noexcept {
    return (always << 28U) | (1U << 24U) | (0xfU << 16U) | (rd << 12U);
}

constexpr std::uint32_t move_to_psr(std::uint32_t fields, std::uint32_t rm) noexcept {
    return (always << 28U) | (0x2U << 23U) | (1U << 21U) | (fields << 16U) | (0xfU << 12U) | rm;
}

constexpr std::uint32_t software_interrupt = (always << 28U) | (0xfU << 24U);
/** `MRC p15, 0, r0, c0, c0, 0` : sans emploi faute de coprocesseur. */
constexpr std::uint32_t read_coprocessor = 0xee10'0f10U;
constexpr std::uint32_t coprocessor_data_operation = 0xee00'0f00U;

// Instructions Thumb.
constexpr std::uint32_t thumb_mov_immediate(std::uint32_t rd, std::uint32_t value) noexcept {
    return 0x2000U | (rd << 8U) | value;
}
constexpr std::uint32_t thumb_branch_exchange(std::uint32_t rm, bool link) noexcept {
    return 0x4700U | (link ? (1U << 7U) : 0U) | ((rm & 0xfU) << 3U);
}
constexpr std::uint32_t thumb_push_pop(bool load, bool extra, std::uint32_t list) noexcept {
    return 0xb400U | (load ? (1U << 11U) : 0U) | (extra ? (1U << 8U) : 0U) | list;
}
constexpr std::uint32_t thumb_long_branch_prefix(std::int32_t high) noexcept {
    return 0xf000U | (static_cast<std::uint32_t>(high) & 0x7ffU);
}
constexpr std::uint32_t thumb_long_branch_exchange_suffix(std::uint32_t low) noexcept {
    return 0xe800U | (low & 0x7ffU);
}

struct Machine {
    TestBus bus{};
    Arm7 cpu{bus};

    Machine() {
        cpu.reset();
        cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system);
    }

    void load(std::uint32_t address, std::initializer_list<std::uint32_t> program) {
        auto cursor = address;
        for (const auto word : program) {
            bus.write32(cursor, word);
            cursor += 4U;
        }
    }

    void load_thumb(std::uint32_t address, std::initializer_list<std::uint32_t> program) {
        auto cursor = address;
        for (const auto halfword : program) {
            bus.write16(cursor, static_cast<std::uint16_t>(halfword));
            cursor += 2U;
        }
    }

    void run(std::initializer_list<std::uint32_t> program) {
        load(program_base, program);
        cpu.state().registers[15] = program_base;
        for (std::size_t index = 0; index < program.size(); ++index) cpu.step();
    }

    [[nodiscard]] std::uint32_t& reg(std::uint32_t index) noexcept {
        return cpu.state().registers[index];
    }
};

// --------------------------------------------------------------------------

void le_processeur_secondaire_n_a_pas_de_coprocesseur() {
    Machine machine;
    check(machine.cpu.architecture() == Architecture::v4t, "l'architecture est la quatrième");
    check(machine.cpu.coprocessor() == nullptr, "et il n'y a pas de coprocesseur");

    // Faute de coprocesseur, les instructions qui lui parlent lèvent
    // l'exception d'instruction indéfinie, comme sur console.
    {
        Machine local;
        local.run({read_coprocessor});
        check(local.cpu.unimplemented_count() == 1U, "la lecture de coprocesseur est refusée");
        check(local.cpu.state().mode() == CpuMode::undefined, "par l'exception prévue à cet effet");
    }
    {
        Machine local;
        local.run({coprocessor_data_operation});
        check(local.cpu.unimplemented_count() == 1U, "l'opération de coprocesseur aussi");
    }
}

void les_vecteurs_restent_en_position_basse() {
    // Rien ne peut déplacer la table : il n'y a pas de registre pour le faire.
    {
        Machine machine;
        machine.run({software_interrupt});
        check(machine.reg(15) == ArmCore::software_interrupt_vector, "l'appel superviseur saute au vecteur nu");
        check(machine.cpu.state().mode() == CpuMode::supervisor, "et bascule en mode superviseur");
    }
    {
        Machine machine;
        machine.reg(15) = program_base;
        machine.cpu.set_irq_line(true);
        machine.cpu.step();
        check(machine.reg(15) == ArmCore::irq_vector, "l'interruption aussi");
    }
    {
        Machine machine;
        machine.run({coprocessor_data_operation});
        check(machine.reg(15) == ArmCore::undefined_vector, "l'instruction inconnue aussi");
    }
}

void les_instructions_d_armv5_n_existent_pas() {
    struct Case {
        std::uint32_t opcode;
        const char* label;
    };
    const Case cases[] = {
        {branch_link_exchange(2), "l'appel avec échange immédiat"},
        {branch_exchange(1U, true), "l'appel avec échange par registre"},
        {count_leading_zeros(0U, 1U), "le comptage de zéros"},
        {saturating(0U, 2U, 0U, 1U), "l'addition saturante"},
        {saturating(1U, 2U, 0U, 1U), "la soustraction saturante"},
        {saturating(2U, 2U, 0U, 1U), "le doublement saturant"},
        {halfword_transfer(0U, 2U, 0U, 0x2U, false), "le chargement de double mot"},
        {halfword_transfer(0U, 2U, 0U, 0x3U, false), "le rangement de double mot"},
    };

    for (const auto& scenario : cases) {
        Machine machine;
        machine.reg(0) = data_base;
        machine.reg(1) = 0x0000'0001U;
        machine.reg(2) = 0x0000'0002U;
        machine.run({scenario.opcode});
        check(
            machine.cpu.unimplemented_count() == 1U,
            std::string{scenario.label} + " n'existe pas sur ce processeur"
        );
        check(
            machine.reg(15) == ArmCore::undefined_vector,
            std::string{scenario.label} + " lève l'exception prévue"
        );
        check(
            machine.cpu.first_unimplemented() == scenario.opcode,
            std::string{scenario.label} + " est retenue telle quelle"
        );
    }

    // Et l'espace de condition 0xF n'est plus une extension : il n'y a rien
    // dedans.
    Machine machine;
    machine.run({0xf000'0000U});
    check(machine.cpu.unimplemented_count() == 1U, "l'espace 0xF est entièrement sans emploi");
}

void les_instructions_thumb_d_armv5_n_existent_pas_non_plus() {
    {
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb;
        machine.reg(1) = data_base;
        machine.load_thumb(program_base, {thumb_branch_exchange(1U, true)});
        machine.reg(15) = program_base;
        machine.cpu.step();
        check(machine.cpu.unimplemented_count() == 1U, "l'appel avec échange Thumb n'existe pas");
        check(machine.reg(14) != ((program_base + 2U) | 1U), "et n'a pas écrit de lien");
    }
    {   // Le branchement sans lien, lui, existe : c'est le « T » du processeur.
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb;
        machine.reg(1) = data_base;
        machine.load_thumb(program_base, {thumb_branch_exchange(1U, false)});
        machine.reg(15) = program_base;
        machine.cpu.step();
        check(machine.cpu.unimplemented_count() == 0U, "le branchement par registre existe");
        check(machine.reg(15) == data_base, "et branche");
        check(!machine.cpu.state().thumb(), "en ramenant en ARM sur une cible paire");
    }
    {   // Le suffixe d'appel long qui bascule en ARM n'existe pas davantage.
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb;
        machine.load_thumb(program_base, {
            thumb_long_branch_prefix(0),
            thumb_long_branch_exchange_suffix(4),
        });
        machine.reg(15) = program_base;
        machine.cpu.step();
        machine.cpu.step();
        check(machine.cpu.unimplemented_count() == 1U, "le suffixe d'échange n'existe pas");
        check(machine.reg(15) == ArmCore::undefined_vector, "il lève l'exception au lieu de brancher");
        // L'exception ramène en ARM, comme toute exception ; ce qui compte est
        // que l'état Thumb ait été sauvegardé pour le retour, et non écrasé par
        // une bascule que l'instruction aurait faite.
        check(
            (machine.cpu.state().undefined_spsr & psr::thumb) != 0U,
            "et l'état Thumb d'avant est sauvegardé intact"
        );
    }
}

void le_chargement_du_compteur_n_entrelace_pas() {
    {   // Une adresse chargée avec son bit bas posé ne fait pas basculer en
        // Thumb : les deux bits bas sont simplement écartés.
        // L'adresse porte ses deux bits bas posés : c'est ce qui distingue un
        // alignement sur le mot d'un alignement sur le demi-mot.
        Machine machine;
        machine.bus.write32(data_base, (data_base + 0x43U));
        machine.reg(0) = data_base;
        machine.run({transfer(true, false, 0U, 15U)});
        check(machine.reg(15) == data_base + 0x40U, "le chargement aligne sur le mot");
        check(!machine.cpu.state().thumb(), "et ne bascule pas en Thumb");
    }
    {   // Même règle pour un transfert par blocs.
        Machine machine;
        machine.bus.write32(data_base, (data_base + 0x23U));
        machine.reg(0) = data_base;
        machine.run({block_transfer(0U, 0x8000U, true, false, true, false)});
        check(machine.reg(15) == data_base + 0x20U, "le transfert par blocs aligne sur le mot");
        check(!machine.cpu.state().thumb(), "et ne bascule pas non plus");
    }
    {   // Dépilé depuis l'état Thumb, le compteur reste en Thumb même si son bit
        // bas est absent — là où le processeur principal repasserait en ARM.
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb;
        // Dépilé, le compteur n'est aligné que sur le demi-mot : le bit 1 doit
        // survivre là où un chargement l'aurait écarté.
        machine.reg(13) = data_base;
        machine.bus.write32(data_base, data_base + 0x43U);
        machine.load_thumb(program_base, {thumb_push_pop(true, true, 0U)});
        machine.reg(15) = program_base;
        machine.cpu.step();
        check(machine.reg(15) == data_base + 0x42U, "le dépilement n'aligne que sur le demi-mot");
        check(machine.cpu.state().thumb(), "et reste en Thumb quel que soit le bit bas");
    }
    {   // Le cas décisif est celui du bit bas absent : le processeur principal y
        // repasserait en ARM, celui-ci reste en Thumb. Une adresse dont le bit
        // bas est posé ne distinguerait rien, puisqu'elle demande justement
        // l'état où l'on se trouve déjà.
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb;
        machine.reg(13) = data_base;
        machine.bus.write32(data_base, data_base + 0x42U);
        machine.load_thumb(program_base, {thumb_push_pop(true, true, 0U)});
        machine.reg(15) = program_base;
        machine.cpu.step();
        check(machine.reg(15) == data_base + 0x42U, "le dépilement branche à l'adresse dépilée");
        check(machine.cpu.state().thumb(), "et une cible paire ne fait pas basculer en ARM");
    }
    {   // Le branchement par registre, lui, entrelace : c'est sa raison d'être,
        // et il existe depuis ARMv4T.
        Machine machine;
        machine.reg(1) = data_base + 1U;
        machine.run({branch_exchange(1U, false)});
        check(machine.reg(15) == data_base, "le branchement par registre aligne");
        check(machine.cpu.state().thumb(), "et bascule bien en Thumb");
    }
    {   // Le retour d'exception aussi : c'est le CPSR sauvegardé qui décide, et
        // non le bit bas de l'adresse.
        // Le gestionnaire s'exécute en ARM, comme après toute exception ; c'est
        // le registre sauvegardé qui porte l'état d'avant.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::irq);
        machine.cpu.state().irq_spsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb;
        machine.reg(13) = data_base;
        machine.bus.write32(data_base, data_base + 0x43U);
        machine.run({block_transfer(13U, 0x8000U, true, false, true, false, true)});
        check(machine.reg(15) == data_base + 0x42U, "le retour n'écarte que le bit d'état");
        check(machine.cpu.state().thumb(), "et l'état vient du registre sauvegardé");
        check(machine.cpu.state().mode() == CpuMode::system, "le mode aussi");
    }
}

void le_tronc_commun_fonctionne() {
    {   // Traitement de données et indicateurs.
        Machine machine;
        machine.reg(1) = 0x7fff'ffffU;
        machine.run({data_immediate(0x4U, true, 1U, 0U, 1U)});   // ADDS r0, r1, #1
        check(machine.reg(0) == 0x8000'0000U, "l'addition déborde");
        check(machine.cpu.state().flag(psr::overflow), "et pose le débordement");
    }
    {   // Multiplication.
        Machine machine;
        machine.reg(1) = 7U;
        machine.reg(2) = 6U;
        machine.run({multiply(0U, 2U, 1U)});
        check(machine.reg(0) == 42U, "la multiplication existe");
    }
    {   // Échange atomique.
        Machine machine;
        machine.bus.write32(data_base, 0x1234'5678U);
        machine.reg(0) = data_base;
        machine.reg(1) = 0xdead'beefU;
        machine.run({swap(0U, 2U, 1U, false)});
        check(machine.reg(2) == 0x1234'5678U, "l'échange atomique existe");
        check(machine.bus.read32(data_base) == 0xdead'beefU, "et écrit bien");
    }
    {   // Demi-mots et octets signés, qui datent d'ARMv4.
        Machine machine;
        machine.bus.write16(data_base, 0x8000U);
        machine.reg(0) = data_base;
        machine.run({
            halfword_transfer(0U, 1U, 0U, 0x1U, true),
            halfword_transfer(0U, 2U, 0U, 0x3U, true),
        });
        check(machine.reg(1) == 0x0000'8000U, "le chargement de demi-mot existe");
        check(machine.reg(2) == 0xffff'8000U, "le demi-mot signé aussi");
    }
    {   // Registres d'état et bancarisation.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::supervisor);
        machine.reg(13) = 0x1111'1111U;
        machine.reg(1) = static_cast<std::uint32_t>(CpuMode::irq);
        machine.run({move_to_psr(0b0001U, 1U), move_from_psr(2U)});
        check(machine.cpu.state().mode() == CpuMode::irq, "le changement de mode existe");
        check(
            machine.cpu.state().supervisor_r13_r14[0] == 0x1111'1111U,
            "et la banque quittée est conservée"
        );
        check((machine.reg(2) & psr::mode_mask) == static_cast<std::uint32_t>(CpuMode::irq),
              "la lecture du registre d'état aussi");
    }
    {   // Branchement avec lien.
        Machine machine;
        machine.run({branch(0, true)});
        check(machine.reg(14) == program_base + 4U, "le branchement avec lien existe");
        check(machine.reg(15) == program_base + 8U, "et saute");
    }
    {   // Un petit programme complet, en Thumb puisque c'est là que le
        // processeur secondaire passe le plus clair de son temps.
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb;
        machine.reg(2) = data_base;
        machine.load_thumb(program_base, {
            thumb_mov_immediate(0U, 0U),
            thumb_mov_immediate(1U, 10U),
            0x1809U,                                    // ADD r1, r1, r0 -> remplacé
            0x3901U,                                    // SUB r1, #1
            0xd1fc,                                     // BNE -4
            0x9000U,                                    // STR r0, [sp, #0]
        });
        // L'addition qui accumule, écrite après coup pour garder la liste
        // lisible dans l'ordre du programme.
        machine.bus.write16(program_base + 4U, 0x1840U);   // ADD r0, r0, r1
        machine.reg(13) = data_base;
        machine.reg(15) = program_base;
        for (int step = 0; step < 2 + 10 * 3 + 1; ++step) machine.cpu.step();

        check(machine.reg(0) == 55U, "la boucle somme les dix premiers entiers");
        check(machine.bus.read32(data_base) == 55U, "et range son résultat");
        check(machine.cpu.unimplemented_count() == 0U, "sans rencontrer d'instruction inconnue");
    }
    {   // Les mémoires locales n'existent pas : tout passe par le bus.
        Machine machine;
        machine.bus.write32(data_base, 0x0bad'cafeU);
        machine.reg(0) = data_base;
        machine.run({transfer(true, false, 0U, 1U)});
        check(machine.reg(1) == 0x0bad'cafeU, "la lecture vient du bus");
    }
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    le_processeur_secondaire_n_a_pas_de_coprocesseur();
    les_vecteurs_restent_en_position_basse();
    les_instructions_d_armv5_n_existent_pas();
    les_instructions_thumb_d_armv5_n_existent_pas_non_plus();
    le_chargement_du_compteur_n_entrelace_pas();
    le_tronc_commun_fonctionne();
    return 0;
}
