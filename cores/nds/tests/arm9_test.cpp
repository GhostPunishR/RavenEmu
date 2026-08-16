#include "cpu/arm9.hpp"

#include "check.hpp"

#include <array>
#include <cstdint>
#include <initializer_list>
#include <string>
#include <vector>

/**
 * Jeu d'instructions ARM du cœur ARM946E-S.
 *
 * Les instructions sont encodées à la main, champ par champ, plutôt que
 * produites par un assembleur : le test décrit alors le même encodage que le
 * manuel, et une faute de décodage ne peut pas être masquée par un outil qui
 * partagerait la lecture erronée. Chaque cas fixe un état de départ, exécute un
 * nombre connu d'instructions, et vérifie registres, indicateurs et mémoire.
 *
 * Le processeur est éprouvé contre une mémoire plate : ni cartouche, ni banques
 * vidéo, ni second processeur. Une faute observée ici est donc une faute du
 * processeur, pas de la machine autour.
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

/** Adresse où sont déposés les programmes, hors de la table des vecteurs. */
constexpr std::uint32_t program_base = 0x0000'0100;
/** Adresse de travail pour les tests de mémoire. */
constexpr std::uint32_t data_base = 0x0000'0800;

constexpr std::uint32_t always = 0xeU;

// Codes d'opération du traitement de données, dans l'ordre du manuel.
constexpr std::uint32_t op_and = 0x0;
constexpr std::uint32_t op_eor = 0x1;
constexpr std::uint32_t op_sub = 0x2;
constexpr std::uint32_t op_rsb = 0x3;
constexpr std::uint32_t op_add = 0x4;
constexpr std::uint32_t op_adc = 0x5;
constexpr std::uint32_t op_sbc = 0x6;
constexpr std::uint32_t op_rsc = 0x7;
constexpr std::uint32_t op_tst = 0x8;
constexpr std::uint32_t op_teq = 0x9;
constexpr std::uint32_t op_cmp = 0xa;
constexpr std::uint32_t op_cmn = 0xb;
constexpr std::uint32_t op_orr = 0xc;
constexpr std::uint32_t op_mov = 0xd;
constexpr std::uint32_t op_bic = 0xe;
constexpr std::uint32_t op_mvn = 0xf;

constexpr std::uint32_t lsl = 0x0;
constexpr std::uint32_t lsr = 0x1;
constexpr std::uint32_t asr = 0x2;
constexpr std::uint32_t ror = 0x3;

/** `cond 00 I op S Rn Rd operand`. */
constexpr std::uint32_t data_op(
    std::uint32_t op,
    bool set_flags,
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t operand,
    bool immediate,
    std::uint32_t cond = always
) noexcept {
    return (cond << 28U) | (immediate ? (1U << 25U) : 0U) | (op << 21U) |
        (set_flags ? (1U << 20U) : 0U) | (rn << 16U) | (rd << 12U) | operand;
}

/** Opérande immédiate : huit bits tournés d'un nombre pair de positions. */
constexpr std::uint32_t immediate_operand(std::uint32_t value, std::uint32_t rotation) noexcept {
    return ((rotation / 2U) << 8U) | value;
}

/** Opérande registre décalé d'une quantité fixe. */
constexpr std::uint32_t shifted_operand(std::uint32_t rm, std::uint32_t type, std::uint32_t amount) noexcept {
    return ((amount & 0x1fU) << 7U) | (type << 5U) | rm;
}

/** Opérande registre décalé d'une quantité tenue par un registre. */
constexpr std::uint32_t register_shifted_operand(std::uint32_t rm, std::uint32_t type, std::uint32_t rs) noexcept {
    return (rs << 8U) | (type << 5U) | (1U << 4U) | rm;
}

/** `MOV Rd, #value` sans écriture d'indicateurs. */
constexpr std::uint32_t mov_immediate(std::uint32_t rd, std::uint32_t value, std::uint32_t rotation = 0U) noexcept {
    return data_op(op_mov, false, 0U, rd, immediate_operand(value, rotation), true);
}

/** `cond 0000 00AS Rd Rn Rs 1001 Rm`. */
constexpr std::uint32_t multiply(
    std::uint32_t rd,
    std::uint32_t rn,
    std::uint32_t rs,
    std::uint32_t rm,
    bool accumulate,
    bool set_flags
) noexcept {
    return (always << 28U) | (accumulate ? (1U << 21U) : 0U) | (set_flags ? (1U << 20U) : 0U) |
        (rd << 16U) | (rn << 12U) | (rs << 8U) | (0x9U << 4U) | rm;
}

/** `cond 0000 1UAS RdHi RdLo Rs 1001 Rm`. */
constexpr std::uint32_t multiply_long(
    std::uint32_t rd_high,
    std::uint32_t rd_low,
    std::uint32_t rs,
    std::uint32_t rm,
    bool is_signed,
    bool accumulate,
    bool set_flags
) noexcept {
    return (always << 28U) | (1U << 23U) | (is_signed ? (1U << 22U) : 0U) |
        (accumulate ? (1U << 21U) : 0U) | (set_flags ? (1U << 20U) : 0U) |
        (rd_high << 16U) | (rd_low << 12U) | (rs << 8U) | (0x9U << 4U) | rm;
}

/** `cond 01 I P U B W L Rn Rd offset`. */
constexpr std::uint32_t single_transfer(
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t offset,
    bool load,
    bool byte_access,
    bool pre,
    bool up,
    bool writeback,
    bool register_offset = false
) noexcept {
    return (always << 28U) | (1U << 26U) | (register_offset ? (1U << 25U) : 0U) |
        (pre ? (1U << 24U) : 0U) | (up ? (1U << 23U) : 0U) | (byte_access ? (1U << 22U) : 0U) |
        (writeback ? (1U << 21U) : 0U) | (load ? (1U << 20U) : 0U) | (rn << 16U) | (rd << 12U) | offset;
}

/** `cond 000 P U I W L Rn Rd offhi 1 S H 1 offlo`. */
constexpr std::uint32_t halfword_transfer(
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t offset,
    std::uint32_t kind,
    bool load,
    bool pre,
    bool up,
    bool writeback
) noexcept {
    return (always << 28U) | (pre ? (1U << 24U) : 0U) | (up ? (1U << 23U) : 0U) | (1U << 22U) |
        (writeback ? (1U << 21U) : 0U) | (load ? (1U << 20U) : 0U) | (rn << 16U) | (rd << 12U) |
        (((offset >> 4U) & 0xfU) << 8U) | (1U << 7U) | (kind << 5U) | (1U << 4U) | (offset & 0xfU);
}

/** `cond 100 P U S W L Rn liste`. */
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

/** `cond 101 L offset`, l'offset comptant en mots. */
constexpr std::uint32_t branch(std::int32_t words, bool link, std::uint32_t cond = always) noexcept {
    return (cond << 28U) | (0x5U << 25U) | (link ? (1U << 24U) : 0U) |
        (static_cast<std::uint32_t>(words) & 0x00ff'ffffU);
}

/** `1111 101 H offset` : BLX immédiat, hors de l'espace conditionnel. */
constexpr std::uint32_t branch_link_exchange(std::int32_t words, bool half) noexcept {
    return (0xfU << 28U) | (0x5U << 25U) | (half ? (1U << 24U) : 0U) |
        (static_cast<std::uint32_t>(words) & 0x00ff'ffffU);
}

constexpr std::uint32_t branch_exchange(std::uint32_t rm, bool link) noexcept {
    return (always << 28U) | 0x012f'ff10U | (link ? (1U << 5U) : 0U) | rm;
}

/** `cond 0001 0110 1111 Rd 1111 0001 Rm`. */
constexpr std::uint32_t count_leading_zeros(std::uint32_t rd, std::uint32_t rm) noexcept {
    return (always << 28U) | 0x016f'0f10U | (rd << 12U) | rm;
}

/** `cond 0001 0op0 Rn Rd 0000 0101 Rm`. */
constexpr std::uint32_t saturating(std::uint32_t op, std::uint32_t rn, std::uint32_t rd, std::uint32_t rm) noexcept {
    return (always << 28U) | (1U << 24U) | (op << 21U) | (rn << 16U) | (rd << 12U) | (0x5U << 4U) | rm;
}

/** `cond 0001 0B00 Rn Rd 0000 1001 Rm`. */
constexpr std::uint32_t swap(std::uint32_t rn, std::uint32_t rd, std::uint32_t rm, bool byte_access) noexcept {
    return (always << 28U) | (1U << 24U) | (byte_access ? (1U << 22U) : 0U) |
        (rn << 16U) | (rd << 12U) | (0x9U << 4U) | rm;
}

/** `cond 0001 0R00 1111 Rd 0000 0000 0000` : MRS. */
constexpr std::uint32_t move_from_psr(std::uint32_t rd, bool spsr) noexcept {
    return (always << 28U) | (1U << 24U) | (spsr ? (1U << 22U) : 0U) | (0xfU << 16U) | (rd << 12U);
}

/** MSR, forme registre ou immédiate selon [immediate]. */
constexpr std::uint32_t move_to_psr(
    std::uint32_t fields,
    std::uint32_t operand,
    bool spsr,
    bool immediate
) noexcept {
    return (always << 28U) | (immediate ? (1U << 25U) : 0U) | (0x2U << 23U) |
        (spsr ? (1U << 22U) : 0U) | (1U << 21U) | (fields << 16U) | (0xfU << 12U) | operand;
}

constexpr std::uint32_t software_interrupt(std::uint32_t comment) noexcept {
    return (always << 28U) | (0xfU << 24U) | (comment & 0x00ff'ffffU);
}

/** Instruction de coprocesseur : décodée, non implémentée. */
constexpr std::uint32_t coprocessor_data_operation = 0xee00'0000U;

/** Un processeur, sa mémoire, et de quoi y déposer un programme. */
struct Machine {
    TestBus bus{};
    Arm9 cpu{bus};

    Machine() { cpu.reset(); }

    void load(std::uint32_t address, std::initializer_list<std::uint32_t> program) {
        auto cursor = address;
        for (const auto word : program) {
            bus.write32(cursor, word);
            cursor += 4U;
        }
    }

    /** Dépose un programme à [program_base] et l'exécute entièrement. */
    void run(std::initializer_list<std::uint32_t> program) {
        load(program_base, program);
        cpu.state().registers[15] = program_base;
        for (std::size_t index = 0; index < program.size(); ++index) cpu.step();
    }

    [[nodiscard]] std::uint32_t& reg(std::uint32_t index) noexcept {
        return cpu.state().registers[index];
    }

    [[nodiscard]] bool flag(std::uint32_t bit) const noexcept { return cpu.state().flag(bit); }
};

void check_flags(const Machine& machine, bool n, bool z, bool c, bool v, const std::string& context) {
    check(machine.flag(psr::negative) == n, context + " : indicateur N");
    check(machine.flag(psr::zero) == z, context + " : indicateur Z");
    check(machine.flag(psr::carry) == c, context + " : indicateur C");
    check(machine.flag(psr::overflow) == v, context + " : indicateur V");
}

/**
 * Table des conditions telle que le manuel les définit, réécrite ici pour que
 * le test ait sa propre source plutôt que de relire celle du cœur.
 */
[[nodiscard]] bool condition_holds(std::uint32_t cond, bool n, bool z, bool c, bool v) noexcept {
    switch (cond) {
    case 0x0: return z;                     // EQ  égal
    case 0x1: return !z;                    // NE  différent
    case 0x2: return c;                     // CS  retenue posée
    case 0x3: return !c;                    // CC  retenue absente
    case 0x4: return n;                     // MI  négatif
    case 0x5: return !n;                    // PL  positif ou nul
    case 0x6: return v;                     // VS  débordement
    case 0x7: return !v;                    // VC  sans débordement
    case 0x8: return c && !z;                // HI  strictement supérieur, non signé
    case 0x9: return !c || z;                // LS  inférieur ou égal, non signé
    case 0xa: return n == v;                // GE  supérieur ou égal, signé
    case 0xb: return n != v;                // LT  strictement inférieur, signé
    case 0xc: return !z && n == v;           // GT  strictement supérieur, signé
    case 0xd: return z || n != v;            // LE  inférieur ou égal, signé
    default: return true;                   // AL  toujours
    }
}

// --------------------------------------------------------------------------

void les_conditions_gouvernent_chaque_instruction() {
    for (std::uint32_t cond = 0; cond <= 0xeU; ++cond) {
        for (std::uint32_t flags = 0; flags < 16U; ++flags) {
            const bool n = (flags & 0x8U) != 0U;
            const bool z = (flags & 0x4U) != 0U;
            const bool c = (flags & 0x2U) != 0U;
            const bool v = (flags & 0x1U) != 0U;

            Machine machine;
            machine.cpu.state().cpsr =
                static_cast<std::uint32_t>(CpuMode::system) |
                (n ? psr::negative : 0U) | (z ? psr::zero : 0U) |
                (c ? psr::carry : 0U) | (v ? psr::overflow : 0U);
            machine.reg(0) = 0U;
            machine.run({data_op(op_mov, false, 0U, 0U, immediate_operand(1U, 0U), true, cond)});

            const auto expected = condition_holds(cond, n, z, c, v) ? 1U : 0U;
            check(
                machine.reg(0) == expected,
                "condition " + std::to_string(cond) + " avec les indicateurs " +
                    std::to_string(flags) + " : exécution inattendue"
            );
            // Une instruction non exécutée avance quand même le compteur.
            check(machine.reg(15) == program_base + 4U, "le compteur avance même sans exécution");
        }
    }

    // L'espace 0xF n'est pas une seizième condition : c'est l'extension d'ARMv5,
    // et une instruction inconnue qui y traîne doit être signalée.
    Machine machine;
    machine.run({0xf000'0000U});
    check(machine.cpu.unimplemented_count() == 1U, "l'espace 0xF inconnu doit être signalé");
}

void le_decaleur_produit_valeur_et_retenue() {
    struct Case {
        std::uint32_t input;
        std::uint32_t type;
        std::uint32_t amount;
        bool carry_in;
        std::uint32_t result;
        bool carry_out;
        const char* label;
    };

    // Un décalage immédiat nul n'est pas neutre pour tous les types : LSR et ASR
    // y codent 32, et ROR y code RRX.
    constexpr Case cases[] = {
        {0x1234'5678U, lsl, 0U, true, 0x1234'5678U, true, "LSL #0 laisse tout en place"},
        {0x8000'0000U, lsl, 1U, false, 0x0000'0000U, true, "LSL #1 pousse le bit haut dans la retenue"},
        {0x0000'0003U, lsl, 31U, false, 0x8000'0000U, true, "LSL #31"},
        {0x8000'0000U, lsr, 0U, false, 0x0000'0000U, true, "LSR #0 vaut LSR #32"},
        {0x0000'0003U, lsr, 1U, false, 0x0000'0001U, true, "LSR #1 sort le bit 0"},
        {0x0000'0002U, lsr, 1U, true, 0x0000'0001U, false, "LSR #1 sort bien le bit sorti, pas le suivant"},
        {0x0000'0010U, lsr, 4U, true, 0x0000'0001U, false, "LSR #4"},
        {0x8000'0000U, asr, 0U, false, 0xffff'ffffU, true, "ASR #0 vaut ASR #32"},
        {0x7fff'ffffU, asr, 0U, false, 0x0000'0000U, false, "ASR #32 sur un positif"},
        {0x8000'0000U, asr, 4U, false, 0xf800'0000U, false, "ASR #4 recopie le signe"},
        {0x1234'5678U, ror, 4U, false, 0x8123'4567U, true, "ROR #4"},
        {0x0000'0003U, ror, 0U, true, 0x8000'0001U, true, "ROR #0 vaut RRX, retenue entrante en tête"},
        {0x0000'0002U, ror, 0U, false, 0x0000'0001U, false, "RRX avec retenue nulle"},
    };

    for (const auto& scenario : cases) {
        Machine machine;
        machine.cpu.state().set_flag(psr::carry, scenario.carry_in);
        machine.reg(1) = scenario.input;
        machine.run({data_op(op_mov, true, 0U, 0U, shifted_operand(1U, scenario.type, scenario.amount), false)});
        check(machine.reg(0) == scenario.result, std::string{scenario.label} + " : valeur");
        check(machine.flag(psr::carry) == scenario.carry_out, std::string{scenario.label} + " : retenue");
    }

    struct RegisterCase {
        std::uint32_t input;
        std::uint32_t type;
        std::uint32_t amount;
        bool carry_in;
        std::uint32_t result;
        bool carry_out;
        const char* label;
    };

    // Décalé par registre, un décalage nul est bien neutre, et une quantité
    // supérieure à 32 n'est pas repliée modulo 32 sauf pour ROR.
    constexpr RegisterCase register_cases[] = {
        {0x1234'5678U, lsl, 0U, true, 0x1234'5678U, true, "LSL Rs=0 est neutre, retenue comprise"},
        {0x1234'5678U, lsl, 0U, false, 0x1234'5678U, false, "LSL Rs=0 conserve une retenue nulle"},
        {0x0000'0003U, lsl, 32U, false, 0x0000'0000U, true, "LSL Rs=32 fait sortir le bit 0"},
        {0x0000'0003U, lsl, 33U, true, 0x0000'0000U, false, "LSL Rs=33 vide tout, retenue comprise"},
        {0x8000'0000U, lsr, 32U, false, 0x0000'0000U, true, "LSR Rs=32 fait sortir le bit 31"},
        {0x8000'0000U, lsr, 40U, true, 0x0000'0000U, false, "LSR Rs>32"},
        {0x8000'0000U, asr, 40U, false, 0xffff'ffffU, true, "ASR Rs>32 sature au signe"},
        {0x1234'5678U, ror, 32U, false, 0x1234'5678U, false, "ROR Rs=32 est neutre mais sort le bit 31"},
        {0x8234'5678U, ror, 32U, false, 0x8234'5678U, true, "ROR Rs=32 sur un négatif pose la retenue"},
        {0x1234'5678U, ror, 36U, false, 0x8123'4567U, true, "ROR Rs=36 se replie sur 4"},
        {0x0000'0003U, ror, 0U, false, 0x0000'0003U, false, "ROR Rs=0 est neutre et n'est pas RRX"},
    };

    for (const auto& scenario : register_cases) {
        Machine machine;
        machine.cpu.state().set_flag(psr::carry, scenario.carry_in);
        machine.reg(1) = scenario.input;
        machine.reg(2) = scenario.amount;
        machine.run({data_op(op_mov, true, 0U, 0U, register_shifted_operand(1U, scenario.type, 2U), false)});
        check(machine.reg(0) == scenario.result, std::string{scenario.label} + " : valeur");
        check(machine.flag(psr::carry) == scenario.carry_out, std::string{scenario.label} + " : retenue");
    }

    // Seuls les huit bits bas du registre de décalage comptent : 256 est une
    // quantité nulle, pas un décalage de 256.
    Machine machine;
    machine.reg(1) = 0x1234'5678U;
    machine.reg(2) = 0x0000'0100U;
    machine.run({data_op(op_mov, false, 0U, 0U, register_shifted_operand(1U, lsl, 2U), false)});
    check(machine.reg(0) == 0x1234'5678U, "le décalage par registre ne lit que huit bits");

    // Une opérande immédiate tournée pose la retenue sur son bit haut.
    Machine rotated;
    rotated.cpu.state().set_flag(psr::carry, false);
    rotated.run({data_op(op_mov, true, 0U, 0U, immediate_operand(0x80U, 8U), true)});
    check(rotated.reg(0) == 0x8000'0000U, "l'immédiate tournée est reconstruite");
    check(rotated.flag(psr::carry), "une immédiate tournée pose la retenue sur son bit 31");

    // Sans rotation, l'immédiate laisse la retenue intacte.
    Machine plain;
    plain.cpu.state().set_flag(psr::carry, true);
    plain.run({data_op(op_mov, true, 0U, 0U, immediate_operand(0x01U, 0U), true)});
    check(plain.flag(psr::carry), "une immédiate non tournée n'altère pas la retenue");
}

void le_traitement_de_donnees_pose_les_indicateurs() {
    {   // Débordement signé vers le négatif, sans retenue.
        Machine machine;
        machine.reg(1) = 0x7fff'ffffU;
        machine.run({data_op(op_add, true, 1U, 0U, immediate_operand(1U, 0U), true)});
        check(machine.reg(0) == 0x8000'0000U, "ADDS déborde vers le négatif");
        check_flags(machine, true, false, false, true, "ADDS 0x7fffffff + 1");
    }
    {   // Retenue sortante sans débordement signé.
        Machine machine;
        machine.reg(1) = 0xffff'ffffU;
        machine.run({data_op(op_add, true, 1U, 0U, immediate_operand(1U, 0U), true)});
        check(machine.reg(0) == 0U, "ADDS boucle à zéro");
        check_flags(machine, false, true, true, false, "ADDS 0xffffffff + 1");
    }
    {   // Sur ARM, la retenue d'une soustraction est l'absence d'emprunt.
        Machine machine;
        machine.reg(1) = 0U;
        machine.run({data_op(op_sub, true, 1U, 0U, immediate_operand(1U, 0U), true)});
        check(machine.reg(0) == 0xffff'ffffU, "SUBS 0 - 1");
        check_flags(machine, true, false, false, false, "SUBS 0 - 1");
    }
    {
        Machine machine;
        machine.reg(1) = 0x8000'0000U;
        machine.run({data_op(op_sub, true, 1U, 0U, immediate_operand(1U, 0U), true)});
        check(machine.reg(0) == 0x7fff'ffffU, "SUBS 0x80000000 - 1");
        check_flags(machine, false, false, true, true, "SUBS 0x80000000 - 1");
    }
    {   // Une soustraction exacte ne laisse pas d'emprunt : la retenue reste posée.
        Machine machine;
        machine.reg(1) = 9U;
        machine.run({data_op(op_sub, true, 1U, 0U, immediate_operand(9U, 0U), true)});
        check(machine.reg(0) == 0U, "SUBS d'égaux");
        check_flags(machine, false, true, true, false, "SUBS d'égaux");
    }
    {   // RSB inverse les opérandes, y compris pour le calcul du débordement.
        Machine machine;
        machine.reg(1) = 0x0000'0001U;
        machine.run({data_op(op_rsb, true, 1U, 0U, immediate_operand(0U, 0U), true)});
        check(machine.reg(0) == 0xffff'ffffU, "RSBS #0 nie le registre");
        check_flags(machine, true, false, false, false, "RSBS 0 - 1");
    }
    {
        Machine machine;
        machine.reg(1) = 0x8000'0000U;
        machine.run({data_op(op_rsb, true, 1U, 0U, immediate_operand(0U, 0U), true)});
        check(machine.reg(0) == 0x8000'0000U, "RSBS nie le minimum signé");
        check_flags(machine, true, false, false, true, "RSBS 0 - 0x80000000");
    }
    {
        Machine machine;
        machine.reg(1) = 5U;
        machine.run({data_op(op_rsb, true, 1U, 0U, immediate_operand(5U, 0U), true)});
        check(machine.reg(0) == 0U, "RSBS d'égaux");
        check_flags(machine, false, true, true, false, "RSBS d'égaux");
    }
    {   // ADC ajoute la retenue entrante.
        Machine machine;
        machine.cpu.state().set_flag(psr::carry, true);
        machine.reg(1) = 1U;
        machine.run({data_op(op_adc, false, 1U, 0U, immediate_operand(1U, 0U), true)});
        check(machine.reg(0) == 3U, "ADC ajoute la retenue");
    }
    {   // SBC retranche l'emprunt, c'est-à-dire la retenue inversée.
        Machine machine;
        machine.cpu.state().set_flag(psr::carry, false);
        machine.reg(1) = 5U;
        machine.run({data_op(op_sbc, true, 1U, 0U, immediate_operand(1U, 0U), true)});
        check(machine.reg(0) == 3U, "SBC retranche l'emprunt");
        check_flags(machine, false, false, true, false, "SBCS 5 - 1 - 1");
    }
    {   // Un emprunt qui traverse zéro efface la retenue.
        Machine machine;
        machine.cpu.state().set_flag(psr::carry, false);
        machine.reg(1) = 0U;
        machine.run({data_op(op_sbc, true, 1U, 0U, immediate_operand(0U, 0U), true)});
        check(machine.reg(0) == 0xffff'ffffU, "SBCS 0 - 0 - 1");
        check(!machine.flag(psr::carry), "SBCS qui emprunte efface la retenue");
    }
    {   // RSC inverse aussi les opérandes de l'emprunt.
        Machine machine;
        machine.cpu.state().set_flag(psr::carry, true);
        machine.reg(1) = 1U;
        machine.run({data_op(op_rsc, true, 1U, 0U, immediate_operand(5U, 0U), true)});
        check(machine.reg(0) == 4U, "RSC 5 - 1");
        check(machine.flag(psr::carry), "RSC sans emprunt garde la retenue");
    }
    {   // Les comparaisons n'écrivent pas leur destination.
        Machine machine;
        machine.reg(0) = 0xdead'beefU;
        machine.reg(1) = 7U;
        machine.run({data_op(op_cmp, true, 1U, 0U, immediate_operand(7U, 0U), true)});
        check(machine.reg(0) == 0xdead'beefU, "CMP n'écrit pas sa destination");
        check_flags(machine, false, true, true, false, "CMP d'égaux");
    }
    {
        Machine machine;
        machine.reg(0) = 0xdead'beefU;
        machine.reg(1) = 0x7fff'ffffU;
        machine.run({data_op(op_cmn, true, 1U, 0U, immediate_operand(1U, 0U), true)});
        check(machine.reg(0) == 0xdead'beefU, "CMN n'écrit pas sa destination");
        check_flags(machine, true, false, false, true, "CMN qui déborde");
    }
    {   // TST et TEQ sont logiques : ils laissent V tel quel.
        Machine machine;
        machine.cpu.state().set_flag(psr::overflow, true);
        machine.reg(0) = 0xdead'beefU;
        machine.reg(1) = 0x0000'00f0U;
        machine.run({data_op(op_tst, true, 1U, 0U, immediate_operand(0x0fU, 0U), true)});
        check(machine.reg(0) == 0xdead'beefU, "TST n'écrit pas sa destination");
        check(machine.flag(psr::zero), "TST sans bit commun pose Z");
        check(machine.flag(psr::overflow), "une opération logique ne touche pas V");
    }
    {
        Machine machine;
        machine.reg(1) = 0xa5a5'a5a5U;
        machine.run({data_op(op_teq, true, 1U, 0U, shifted_operand(1U, lsl, 0U), false)});
        check(machine.flag(psr::zero), "TEQ d'un registre avec lui-même pose Z");
    }
    {   // Le reste des opérations logiques, sur une même paire.
        Machine machine;
        machine.reg(1) = 0xf0f0'f0f0U;
        machine.reg(2) = 0x00ff'00ffU;
        machine.run({
            data_op(op_and, false, 1U, 0U, shifted_operand(2U, lsl, 0U), false),
            data_op(op_eor, false, 1U, 3U, shifted_operand(2U, lsl, 0U), false),
            data_op(op_orr, false, 1U, 4U, shifted_operand(2U, lsl, 0U), false),
            data_op(op_bic, false, 1U, 5U, shifted_operand(2U, lsl, 0U), false),
            data_op(op_mvn, false, 0U, 6U, shifted_operand(2U, lsl, 0U), false),
        });
        check(machine.reg(0) == 0x00f0'00f0U, "AND");
        check(machine.reg(3) == 0xf00f'f00fU, "EOR");
        check(machine.reg(4) == 0xf0ff'f0ffU, "ORR");
        check(machine.reg(5) == 0xf000'f000U, "BIC efface les bits de l'opérande");
        check(machine.reg(6) == 0xff00'ff00U, "MVN complémente");
    }
    {   // MOVS d'un résultat nul pose Z sans toucher V.
        Machine machine;
        machine.cpu.state().set_flag(psr::overflow, true);
        machine.run({data_op(op_mov, true, 0U, 0U, immediate_operand(0U, 0U), true)});
        check_flags(machine, false, true, false, true, "MOVS #0");
    }
    {   // Lu comme opérande, R15 vaut l'instruction plus huit.
        Machine machine;
        machine.run({data_op(op_mov, false, 0U, 0U, shifted_operand(15U, lsl, 0U), false)});
        check(machine.reg(0) == program_base + 8U, "R15 lu vaut l'instruction plus huit");
    }
    {   // Un décalage par registre coûte un cycle de plus, et R15 y vaut douze.
        Machine machine;
        machine.reg(2) = 0U;
        machine.run({data_op(op_mov, false, 0U, 0U, register_shifted_operand(15U, lsl, 2U), false)});
        check(machine.reg(0) == program_base + 12U, "R15 décalé par registre vaut l'instruction plus douze");
    }
    {   // Même règle pour l'opérande de gauche.
        Machine machine;
        machine.reg(2) = 0U;
        machine.reg(1) = 0U;
        machine.run({data_op(op_add, false, 15U, 0U, register_shifted_operand(1U, lsl, 2U), false)});
        check(machine.reg(0) == program_base + 12U, "Rn vaut aussi douze de plus dans ce cas");
    }
    {   // Écrire R15 branche.
        Machine machine;
        machine.reg(1) = data_base;
        machine.run({data_op(op_mov, false, 0U, 15U, shifted_operand(1U, lsl, 0U), false)});
        check(machine.reg(15) == data_base, "écrire R15 branche au lieu d'avancer");
    }
}

void les_multiplications_courtes_et_longues() {
    {
        Machine machine;
        machine.reg(1) = 7U;
        machine.reg(2) = 6U;
        machine.run({multiply(0U, 0U, 2U, 1U, false, false)});
        check(machine.reg(0) == 42U, "MUL");
    }
    {   // MUL tronque à 32 bits, sans notion de débordement.
        Machine machine;
        machine.reg(1) = 0x0001'0000U;
        machine.reg(2) = 0x0001'0000U;
        machine.run({multiply(0U, 0U, 2U, 1U, false, true)});
        check(machine.reg(0) == 0U, "MULS tronque");
        check(machine.flag(psr::zero), "MULS pose Z sur un résultat tronqué nul");
    }
    {
        Machine machine;
        machine.reg(1) = 0xffff'ffffU;
        machine.reg(2) = 3U;
        machine.run({multiply(0U, 0U, 2U, 1U, false, true)});
        check(machine.reg(0) == 0xffff'fffdU, "MULS d'un négatif");
        check(machine.flag(psr::negative), "MULS pose N");
    }
    {
        Machine machine;
        machine.reg(1) = 5U;
        machine.reg(2) = 4U;
        machine.reg(3) = 100U;
        machine.run({multiply(0U, 3U, 2U, 1U, true, false)});
        check(machine.reg(0) == 120U, "MLA accumule");
    }
    {   // Non signé : le produit occupe bien les soixante-quatre bits.
        Machine machine;
        machine.reg(2) = 0xffff'ffffU;
        machine.reg(3) = 0xffff'ffffU;
        machine.run({multiply_long(1U, 0U, 3U, 2U, false, false, true)});
        check(machine.reg(0) == 0x0000'0001U, "UMULL, mot bas");
        check(machine.reg(1) == 0xffff'fffeU, "UMULL, mot haut");
        check(machine.flag(psr::negative), "UMULLS regarde le bit 63");
    }
    {   // Signé : le même encodage numérique donne un tout autre produit.
        Machine machine;
        machine.reg(2) = 0xffff'ffffU;
        machine.reg(3) = 0xffff'ffffU;
        machine.run({multiply_long(1U, 0U, 3U, 2U, true, false, true)});
        check(machine.reg(0) == 1U, "SMULL de deux fois moins un");
        check(machine.reg(1) == 0U, "SMULL, mot haut");
        check(!machine.flag(psr::negative), "SMULLS ne pose pas N sur un produit positif");
        check(!machine.flag(psr::zero), "SMULLS ne pose pas Z");
    }
    {
        Machine machine;
        machine.reg(2) = 0x0000'0002U;
        machine.reg(3) = 0xffff'ffffU;
        machine.run({multiply_long(1U, 0U, 3U, 2U, true, false, true)});
        check(machine.reg(0) == 0xffff'fffeU, "SMULL négatif, mot bas");
        check(machine.reg(1) == 0xffff'ffffU, "SMULL négatif, mot haut");
        check(machine.flag(psr::negative), "SMULLS pose N sur un produit négatif");
    }
    {   // L'accumulateur est lu dans la paire de destination avant l'écriture.
        Machine machine;
        machine.reg(0) = 0x0000'0010U;
        machine.reg(1) = 0x0000'0001U;
        machine.reg(2) = 0x0000'0002U;
        machine.reg(3) = 0x0000'0003U;
        machine.run({multiply_long(1U, 0U, 3U, 2U, false, true, false)});
        check(machine.reg(0) == 0x0000'0016U, "UMLAL, mot bas");
        check(machine.reg(1) == 0x0000'0001U, "UMLAL, mot haut");
    }
    {
        Machine machine;
        machine.reg(0) = 0U;
        machine.reg(1) = 0U;
        machine.reg(2) = 0xffff'ffffU;
        machine.reg(3) = 0x0000'0001U;
        machine.run({multiply_long(1U, 0U, 3U, 2U, true, true, true)});
        check(machine.reg(0) == 0xffff'ffffU, "SMLAL, mot bas");
        check(machine.reg(1) == 0xffff'ffffU, "SMLAL, mot haut");
        check(machine.flag(psr::negative), "SMLALS pose N");
    }
    {   // Le produit nul long pose Z sur les soixante-quatre bits, pas sur trente-deux.
        Machine machine;
        machine.reg(2) = 0x0001'0000U;
        machine.reg(3) = 0x0001'0000U;
        machine.run({multiply_long(1U, 0U, 3U, 2U, false, false, true)});
        check(machine.reg(0) == 0U, "UMULLS, mot bas nul");
        check(machine.reg(1) == 1U, "UMULLS, mot haut non nul");
        check(!machine.flag(psr::zero), "Z regarde le produit entier, pas seulement le mot bas");
        check(!machine.flag(psr::negative), "et N regarde le bit 63, pas le bit 31");
    }
}

void les_transferts_simples_couvrent_mot_et_octet() {
    {   // Aller-retour simple.
        Machine machine;
        machine.reg(0) = data_base;
        machine.reg(1) = 0x1234'5678U;
        machine.run({
            single_transfer(0U, 1U, 0U, false, false, true, true, false),
            single_transfer(0U, 2U, 0U, true, false, true, true, false),
        });
        check(machine.bus.read32(data_base) == 0x1234'5678U, "STR écrit en mémoire");
        check(machine.reg(2) == 0x1234'5678U, "LDR relit ce qui a été écrit");
    }
    {   // Une lecture de mot désalignée ramène le mot aligné, tourné.
        Machine machine;
        machine.bus.write32(data_base, 0x1234'5678U);
        constexpr std::uint32_t expected[] = {
            0x1234'5678U, 0x7812'3456U, 0x5678'1234U, 0x3456'7812U
        };
        for (std::uint32_t offset = 0; offset < 4U; ++offset) {
            Machine local;
            local.bus.write32(data_base, 0x1234'5678U);
            local.reg(0) = data_base + offset;
            local.run({single_transfer(0U, 1U, 0U, true, false, true, true, false)});
            check(
                local.reg(1) == expected[offset],
                "LDR désaligné de " + std::to_string(offset) + " tourne le mot"
            );
        }
    }
    {   // Les accès octet ne tournent rien et n'étendent pas le signe.
        Machine machine;
        machine.bus.write32(data_base, 0x1234'56ffU);
        machine.reg(0) = data_base;
        machine.run({single_transfer(0U, 1U, 0U, true, true, true, true, false)});
        check(machine.reg(1) == 0x0000'00ffU, "LDRB complète par des zéros");
    }
    {
        Machine machine;
        machine.bus.write32(data_base, 0xffff'ffffU);
        machine.reg(0) = data_base + 1U;
        machine.reg(1) = 0x1234'5600U;
        machine.run({single_transfer(0U, 1U, 0U, false, true, true, true, false)});
        check(machine.bus.read8(data_base + 1U) == 0x00U, "STRB n'écrit que l'octet bas");
        check(machine.bus.read8(data_base) == 0xffU, "STRB ne déborde pas sur son voisin");
    }
    {   // Pré-indexé avec réécriture.
        Machine machine;
        machine.bus.write32(data_base + 8U, 0x0bad'cafeU);
        machine.reg(0) = data_base;
        machine.run({single_transfer(0U, 1U, 8U, true, false, true, true, true)});
        check(machine.reg(1) == 0x0bad'cafeU, "LDR pré-indexé lit à la base décalée");
        check(machine.reg(0) == data_base + 8U, "la réécriture déplace la base");
    }
    {   // Pré-indexé sans réécriture : la base ne bouge pas.
        Machine machine;
        machine.reg(0) = data_base;
        machine.run({single_transfer(0U, 1U, 8U, true, false, true, true, false)});
        check(machine.reg(0) == data_base, "sans réécriture la base reste");
    }
    {   // Post-indexé : l'accès se fait à la base, qui bouge ensuite.
        Machine machine;
        machine.bus.write32(data_base, 0x0000'0001U);
        machine.bus.write32(data_base + 4U, 0x0000'0002U);
        machine.reg(0) = data_base;
        machine.run({
            single_transfer(0U, 1U, 4U, true, false, false, true, false),
            single_transfer(0U, 2U, 4U, true, false, false, true, false),
        });
        check(machine.reg(1) == 1U, "le premier accès post-indexé lit la base");
        check(machine.reg(2) == 2U, "le second lit la base déplacée");
        check(machine.reg(0) == data_base + 8U, "la base a avancé deux fois");
    }
    {   // Décalage négatif.
        Machine machine;
        machine.bus.write32(data_base, 0x0000'0042U);
        machine.reg(0) = data_base + 16U;
        machine.run({single_transfer(0U, 1U, 16U, true, false, true, false, false)});
        check(machine.reg(1) == 0x42U, "un décalage descendant soustrait");
    }
    {   // Décalage porté par un registre, lui-même décalé.
        Machine machine;
        machine.bus.write32(data_base + 12U, 0x0000'0099U);
        machine.reg(0) = data_base;
        machine.reg(2) = 3U;
        machine.run({single_transfer(0U, 1U, shifted_operand(2U, lsl, 2U), true, false, true, true, false, true)});
        check(machine.reg(1) == 0x99U, "le décalage registre passe par le décaleur");
    }
    {   // Une lecture dont la destination est la base n'écrase pas la valeur lue.
        Machine machine;
        machine.bus.write32(data_base, 0x0000'0055U);
        machine.reg(0) = data_base;
        machine.run({single_transfer(0U, 0U, 4U, true, false, false, true, false)});
        check(machine.reg(0) == 0x55U, "la donnée lue l'emporte sur la réécriture de base");
    }
    {   // Rangé, R15 vaut l'instruction plus douze.
        Machine machine;
        machine.reg(0) = data_base;
        machine.run({single_transfer(0U, 15U, 0U, false, false, true, true, false)});
        check(machine.bus.read32(data_base) == program_base + 12U, "STR R15 range l'instruction plus douze");
    }
    {   // L'espace des instructions média est refusé plutôt qu'exécuté au hasard.
        Machine machine;
        machine.run({single_transfer(0U, 1U, 0U, true, false, true, true, false, true) | (1U << 4U)});
        check(machine.cpu.unimplemented_count() == 1U, "l'espace média est signalé comme non implémenté");
    }
}

void les_transferts_demi_mot_et_double_mot() {
    constexpr std::uint32_t kind_halfword = 0x1U;
    constexpr std::uint32_t kind_signed_byte = 0x2U;
    constexpr std::uint32_t kind_signed_halfword = 0x3U;

    {
        Machine machine;
        machine.bus.write32(data_base, 0xffff'ffffU);
        machine.reg(0) = data_base;
        machine.reg(1) = 0x1234'5678U;
        machine.run({halfword_transfer(0U, 1U, 0U, kind_halfword, false, true, true, false)});
        check(machine.bus.read16(data_base) == 0x5678U, "STRH n'écrit que le demi-mot bas");
        check(machine.bus.read16(data_base + 2U) == 0xffffU, "STRH ne déborde pas");
    }
    {
        Machine machine;
        machine.bus.write16(data_base, 0x8000U);
        machine.reg(0) = data_base;
        machine.run({
            halfword_transfer(0U, 1U, 0U, kind_halfword, true, true, true, false),
            halfword_transfer(0U, 2U, 0U, kind_signed_halfword, true, true, true, false),
        });
        check(machine.reg(1) == 0x0000'8000U, "LDRH complète par des zéros");
        check(machine.reg(2) == 0xffff'8000U, "LDRSH étend le signe");
    }
    {
        Machine machine;
        machine.bus.write8(data_base, 0xffU);
        machine.bus.write8(data_base + 1U, 0x7fU);
        machine.reg(0) = data_base;
        machine.run({
            halfword_transfer(0U, 1U, 0U, kind_signed_byte, true, true, true, false),
            halfword_transfer(0U, 2U, 1U, kind_signed_byte, true, true, true, false),
        });
        check(machine.reg(1) == 0xffff'ffffU, "LDRSB étend le signe");
        check(machine.reg(2) == 0x0000'007fU, "LDRSB d'un octet positif reste positif");
    }
    {   // Le décalage immédiat est reconstitué à partir de deux moitiés.
        Machine machine;
        machine.bus.write16(data_base + 0xabU - 1U, 0x1234U);
        machine.reg(0) = data_base;
        machine.run({halfword_transfer(0U, 1U, 0xabU, kind_halfword, true, true, true, false)});
        check(machine.reg(1) == 0x1234U, "le décalage immédiat joint ses deux moitiés");
    }
    {   // Décalage porté par un registre : le bit 22 retombe à zéro.
        Machine machine;
        machine.bus.write16(data_base + 6U, 0xbeefU);
        machine.reg(0) = data_base;
        machine.reg(2) = 6U;
        const auto opcode =
            (halfword_transfer(0U, 1U, 0U, kind_halfword, true, true, true, false) & ~(1U << 22U)) | 2U;
        machine.run({opcode});
        check(machine.reg(1) == 0xbeefU, "le décalage registre est lu dans Rm");
    }
    {   // Post-indexé descendant.
        Machine machine;
        machine.bus.write16(data_base, 0x4242U);
        machine.reg(0) = data_base;
        machine.run({halfword_transfer(0U, 1U, 2U, kind_halfword, true, false, false, false)});
        check(machine.reg(1) == 0x4242U, "l'accès post-indexé se fait à la base");
        check(machine.reg(0) == data_base - 2U, "la base descend ensuite");
    }
    {   // Double mot : deux registres consécutifs, deux mots consécutifs.
        Machine machine;
        machine.bus.write32(data_base, 0x1111'1111U);
        machine.bus.write32(data_base + 4U, 0x2222'2222U);
        machine.reg(0) = data_base;
        machine.run({halfword_transfer(0U, 2U, 0U, kind_signed_byte, false, true, true, false)});
        check(machine.reg(2) == 0x1111'1111U, "LDRD, premier registre");
        check(machine.reg(3) == 0x2222'2222U, "LDRD, second registre");
    }
    {
        Machine machine;
        machine.reg(0) = data_base;
        machine.reg(4) = 0xaaaa'aaaaU;
        machine.reg(5) = 0xbbbb'bbbbU;
        machine.run({halfword_transfer(0U, 4U, 0U, kind_signed_halfword, false, true, true, false)});
        check(machine.bus.read32(data_base) == 0xaaaa'aaaaU, "STRD, premier mot");
        check(machine.bus.read32(data_base + 4U) == 0xbbbb'bbbbU, "STRD, second mot");
    }
    {   // Une paire impossible est refusée, faute de second registre.
        Machine machine;
        machine.reg(0) = data_base;
        machine.run({halfword_transfer(0U, 3U, 0U, kind_signed_byte, false, true, true, false)});
        check(machine.cpu.unimplemented_count() == 1U, "un registre impair est refusé pour LDRD");
    }
    {
        Machine machine;
        machine.reg(0) = data_base;
        machine.run({halfword_transfer(0U, 14U, 0U, kind_signed_byte, false, true, true, false)});
        check(machine.cpu.unimplemented_count() == 1U, "R14 est refusé pour LDRD, faute de paire");
    }
}

void les_transferts_par_blocs_parcourent_les_registres() {
    {   // Croissant : le registre le plus bas occupe l'adresse la plus basse.
        Machine machine;
        machine.reg(0) = data_base;
        machine.reg(1) = 0x1111'1111U;
        machine.reg(2) = 0x2222'2222U;
        machine.reg(3) = 0x3333'3333U;
        machine.run({block_transfer(0U, 0b1110U, false, false, true, true)});
        check(machine.bus.read32(data_base) == 0x1111'1111U, "STMIA range R1 en premier");
        check(machine.bus.read32(data_base + 4U) == 0x2222'2222U, "puis R2");
        check(machine.bus.read32(data_base + 8U) == 0x3333'3333U, "puis R3");
        check(machine.reg(0) == data_base + 12U, "la base avance de trois mots");
    }
    {   // Pré-décrémenté : la pile descendante pleine, celle des appels ARM.
        Machine machine;
        machine.reg(13) = data_base + 0x100U;
        machine.reg(1) = 0x1111'1111U;
        machine.reg(2) = 0x2222'2222U;
        machine.run({
            block_transfer(13U, 0b0110U, false, true, false, true),
            data_op(op_mov, false, 0U, 1U, immediate_operand(0U, 0U), true),
            data_op(op_mov, false, 0U, 2U, immediate_operand(0U, 0U), true),
            block_transfer(13U, 0b0110U, true, false, true, true),
        });
        check(machine.reg(13) == data_base + 0x100U, "la pile revient à son niveau");
        check(machine.reg(1) == 0x1111'1111U, "R1 est restauré");
        check(machine.reg(2) == 0x2222'2222U, "R2 est restauré");
        check(machine.bus.read32(data_base + 0x100U - 8U) == 0x1111'1111U, "R1 occupe l'adresse la plus basse");
    }
    {   // Les quatre modes d'adressage écrivent au même endroit à un mot près.
        struct Mode {
            bool pre;
            bool up;
            std::uint32_t first_word_offset;
            const char* label;
        };
        constexpr Mode modes[] = {
            {false, true, 0x40U, "IA"},
            {true, true, 0x44U, "IB"},
            {false, false, 0x40U - 8U + 4U, "DA"},
            {true, false, 0x40U - 8U, "DB"},
        };
        for (const auto& mode : modes) {
            Machine machine;
            machine.reg(0) = data_base + 0x40U;
            machine.reg(1) = 0xaaaa'aaaaU;
            machine.reg(2) = 0xbbbb'bbbbU;
            machine.run({block_transfer(0U, 0b0110U, false, mode.pre, mode.up, false)});
            check(
                machine.bus.read32(data_base + mode.first_word_offset) == 0xaaaa'aaaaU,
                std::string{mode.label} + " : premier mot"
            );
            check(
                machine.bus.read32(data_base + mode.first_word_offset + 4U) == 0xbbbb'bbbbU,
                std::string{mode.label} + " : second mot"
            );
            check(machine.reg(0) == data_base + 0x40U, std::string{mode.label} + " : base inchangée");
        }
    }
    {   // Charger R15 branche, et le bit bas est écarté.
        Machine machine;
        machine.reg(0) = data_base;
        machine.bus.write32(data_base, data_base + 0x21U);
        machine.run({block_transfer(0U, 0x8000U, true, false, true, false)});
        check(machine.reg(15) == data_base + 0x20U, "LDM vers R15 branche à l'adresse chargée");
    }
    {   // Rangé, R15 vaut encore l'instruction plus douze.
        Machine machine;
        machine.reg(0) = data_base;
        machine.run({block_transfer(0U, 0x8000U, false, false, true, false)});
        check(machine.bus.read32(data_base) == program_base + 12U, "STM range R15 avec son avance");
    }
    {   // Une base présente dans une liste chargée n'est pas réécrite ensuite.
        Machine machine;
        machine.reg(0) = data_base;
        machine.bus.write32(data_base, 0x0bad'0000U);
        machine.bus.write32(data_base + 4U, 0x0000'0002U);
        machine.run({block_transfer(0U, 0b0011U, true, false, true, true)});
        check(machine.reg(0) == 0x0bad'0000U, "la valeur chargée dans la base l'emporte");
        check(machine.reg(1) == 0x0000'0002U, "les autres registres sont chargés normalement");
    }
    {   // Une liste vide n'a pas de comportement défini : elle est signalée.
        Machine machine;
        machine.reg(0) = data_base;
        machine.run({block_transfer(0U, 0U, true, false, true, true)});
        check(machine.cpu.unimplemented_count() == 1U, "une liste vide est signalée");
    }
    {   // Avec le bit S et sans R15, ce sont les registres utilisateur qui sortent.
        Machine machine;
        machine.cpu.state().switch_mode(CpuMode::irq);
        machine.reg(13) = 0xdead'0000U;               // R13 de la banque IRQ
        machine.cpu.state().user_r13_r14[0] = 0xf00d'0000U;
        machine.reg(0) = data_base;
        machine.run({block_transfer(0U, 1U << 13U, false, false, true, false, true)});
        check(machine.bus.read32(data_base) == 0xf00d'0000U, "le bit S range la banque utilisateur");
        check(machine.reg(13) == 0xdead'0000U, "la banque courante est retrouvée après coup");
        check(machine.cpu.state().mode() == CpuMode::irq, "le mode est restauré");
    }
    {   // Avec le bit S et R15, c'est le CPSR sauvegardé qui reprend la main.
        Machine machine;
        machine.cpu.state().switch_mode(CpuMode::irq);
        machine.cpu.state().irq_spsr =
            static_cast<std::uint32_t>(CpuMode::user) | psr::carry;
        machine.reg(13) = data_base;
        machine.bus.write32(data_base, data_base + 0x40U);
        machine.run({block_transfer(13U, 0x8000U, true, false, true, false, true)});
        check(machine.reg(15) == data_base + 0x40U, "le retour se fait à l'adresse chargée");
        check(machine.cpu.state().mode() == CpuMode::user, "le mode sauvegardé est restauré");
        check(machine.flag(psr::carry), "les indicateurs sauvegardés reviennent aussi");
    }
}

void les_branchements_enregistrent_le_retour() {
    {   // La cible se compte depuis l'instruction plus huit.
        Machine machine;
        machine.run({branch(2, false)});
        check(machine.reg(15) == program_base + 8U + 8U, "B saute de l'avance plus le décalage");
    }
    {   // Décalage négatif.
        Machine machine;
        machine.run({branch(-4, false)});
        check(machine.reg(15) == program_base + 8U - 16U, "B recule");
    }
    {
        Machine machine;
        machine.run({branch(0, true)});
        check(machine.reg(14) == program_base + 4U, "BL retient l'instruction suivante");
        check(machine.reg(15) == program_base + 8U, "BL saute");
    }
    {   // Un branchement dont la condition échoue n'écrit pas le lien.
        Machine machine;
        machine.cpu.state().set_flag(psr::zero, false);
        machine.reg(14) = 0xdead'beefU;
        machine.run({branch(4, true, 0x0U)});
        check(machine.reg(14) == 0xdead'beefU, "un BL non pris n'écrit pas R14");
        check(machine.reg(15) == program_base + 4U, "un BL non pris avance simplement");
    }
    {   // BX vers une adresse paire reste en ARM.
        Machine machine;
        machine.reg(1) = data_base;
        machine.run({branch_exchange(1U, false)});
        check(machine.reg(15) == data_base, "BX branche");
        check(!machine.cpu.state().thumb(), "une cible paire reste en ARM");
    }
    {   // Bit bas posé : la cible est du Thumb, et l'adresse est réalignée.
        Machine machine;
        machine.reg(1) = data_base + 1U;
        machine.run({branch_exchange(1U, false)});
        check(machine.reg(15) == data_base, "BX écarte le bit d'état de l'adresse");
        check(machine.cpu.state().thumb(), "une cible impaire bascule en Thumb");
    }
    {
        Machine machine;
        machine.reg(1) = data_base + 1U;
        machine.run({branch_exchange(1U, true)});
        check(machine.reg(14) == program_base + 4U, "BLX registre retient le retour");
        check(machine.cpu.state().thumb(), "BLX registre bascule aussi");
    }
    {   // BLX immédiat bascule toujours, et son bit H ajoute un demi-mot.
        Machine machine;
        machine.run({branch_link_exchange(0, false)});
        check(machine.reg(14) == program_base + 4U, "BLX immédiat retient le retour");
        check(machine.reg(15) == program_base + 8U, "BLX immédiat saute");
        check(machine.cpu.state().thumb(), "BLX immédiat bascule en Thumb");
    }
    {
        Machine machine;
        machine.run({branch_link_exchange(1, true)});
        check(machine.reg(15) == program_base + 8U + 4U + 2U, "le bit H ajoute un demi-mot");
    }
}

void les_transferts_d_etat_respectent_le_mode() {
    {
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system) | psr::carry;
        machine.run({move_from_psr(0U, false)});
        check(
            machine.reg(0) == (static_cast<std::uint32_t>(CpuMode::system) | psr::carry),
            "MRS publie le CPSR entier"
        );
    }
    {   // Écrire le champ des indicateurs ne touche pas le mode.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::supervisor);
        machine.run({move_to_psr(0b1000U, immediate_operand(0xfU, 4U), false, true)});
        check_flags(machine, true, true, true, true, "MSR du champ des indicateurs");
        check(machine.cpu.state().mode() == CpuMode::supervisor, "le mode n'a pas bougé");
    }
    {   // Écrire le champ de contrôle change le mode, donc la banque.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::supervisor);
        machine.reg(13) = 0x1111'1111U;
        machine.reg(1) = static_cast<std::uint32_t>(CpuMode::irq);
        machine.run({move_to_psr(0b0001U, 1U, false, false)});
        check(machine.cpu.state().mode() == CpuMode::irq, "MSR du champ de contrôle change le mode");
        check(machine.reg(13) != 0x1111'1111U, "la banque du nouveau mode remplace l'ancienne");
        check(
            machine.cpu.state().supervisor_r13_r14[0] == 0x1111'1111U,
            "la banque quittée conserve sa valeur"
        );
    }
    {   // En mode utilisateur, seul le champ des indicateurs est accessible.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::user);
        machine.reg(1) = static_cast<std::uint32_t>(CpuMode::system) | psr::negative;
        machine.run({move_to_psr(0b1001U, 1U, false, false)});
        check(machine.cpu.state().mode() == CpuMode::user, "un programme utilisateur ne s'octroie pas un mode");
        check(machine.flag(psr::negative), "il garde en revanche la main sur ses indicateurs");
    }
    {   // Le SPSR n'existe pas partout : sans lui, MRS retombe sur le CPSR.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system) | psr::zero;
        machine.run({move_from_psr(0U, true)});
        check(
            machine.reg(0) == (static_cast<std::uint32_t>(CpuMode::system) | psr::zero),
            "sans SPSR, MRS publie le CPSR"
        );
    }
    {   // Là où il existe, il se lit et s'écrit sans toucher au mode courant.
        Machine machine;
        machine.cpu.state().switch_mode(CpuMode::irq);
        machine.reg(1) = 0xf000'0000U | static_cast<std::uint32_t>(CpuMode::user);
        machine.run({
            move_to_psr(0b1001U, 1U, true, false),
            move_from_psr(2U, true),
        });
        check(machine.cpu.state().mode() == CpuMode::irq, "écrire le SPSR ne change pas de mode");
        check(machine.reg(2) == machine.reg(1), "le SPSR relu vaut ce qui y a été écrit");
    }
    {   // Écrire le SPSR sans en avoir un ne fait rien plutôt que de tout casser.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::user);
        machine.reg(1) = 0xffff'ffffU;
        machine.run({move_to_psr(0b1111U, 1U, true, false)});
        check(machine.cpu.state().mode() == CpuMode::user, "l'écriture est simplement ignorée");
    }
    {   // Les quatre champs sont indépendants, et chacun couvre son octet.
        constexpr std::uint32_t fields[] = {0b0001U, 0b0010U, 0b0100U, 0b1000U};
        constexpr std::uint32_t masks[] = {0x0000'00ffU, 0x0000'ff00U, 0x00ff'0000U, 0xff00'0000U};
        for (std::size_t index = 0; index < 4U; ++index) {
            Machine machine;
            machine.cpu.state().switch_mode(CpuMode::irq);
            machine.cpu.state().irq_spsr = 0U;
            machine.reg(1) = 0xffff'ffffU;
            machine.run({move_to_psr(fields[index], 1U, true, false)});
            check(
                machine.cpu.state().irq_spsr == masks[index],
                "le champ " + std::to_string(index) + " n'écrit que son octet"
            );
        }
    }
    {   // Et ils se combinent sans se recouvrir.
        Machine machine;
        machine.cpu.state().switch_mode(CpuMode::irq);
        machine.cpu.state().irq_spsr = 0U;
        machine.reg(1) = 0xffff'ffffU;
        machine.run({move_to_psr(0b1010U, 1U, true, false)});
        check(machine.cpu.state().irq_spsr == 0xff00'ff00U, "deux champs demandés, deux octets écrits");
    }
    {   // La banque FIQ va plus loin : elle couvre aussi R8 à R12.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system);
        machine.reg(8) = 0x8888'8888U;
        machine.reg(12) = 0xcccc'ccccU;
        machine.reg(7) = 0x7777'7777U;
        machine.cpu.state().switch_mode(CpuMode::fiq);
        check(machine.reg(8) != 0x8888'8888U, "R8 est bancarisé en FIQ");
        check(machine.reg(7) == 0x7777'7777U, "R7 ne l'est pas");
        machine.reg(8) = 0x0f0f'0f0fU;
        machine.cpu.state().switch_mode(CpuMode::system);
        check(machine.reg(8) == 0x8888'8888U, "la valeur d'origine revient");
        check(machine.reg(12) == 0xcccc'ccccU, "R12 aussi");
        machine.cpu.state().switch_mode(CpuMode::fiq);
        check(machine.reg(8) == 0x0f0f'0f0fU, "et la valeur FIQ est retrouvée");
    }
    {   // Un mode inconnu ne bloque pas la machine : il retombe sur la banque partagée.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system);
        machine.reg(13) = 0x1234'0000U;
        machine.cpu.state().switch_mode(static_cast<CpuMode>(0x15U));
        machine.cpu.state().switch_mode(CpuMode::system);
        check(machine.reg(13) == 0x1234'0000U, "un mode inconnu partage la banque utilisateur");
    }
}

void le_comptage_de_zeros_et_l_arithmetique_saturante() {
    {
        constexpr std::uint32_t inputs[] = {0U, 1U, 0x0000'8000U, 0x8000'0000U, 0xffff'ffffU};
        constexpr std::uint32_t expected[] = {32U, 31U, 16U, 0U, 0U};
        for (std::size_t index = 0; index < 5U; ++index) {
            Machine machine;
            machine.reg(1) = inputs[index];
            machine.run({count_leading_zeros(0U, 1U)});
            check(
                machine.reg(0) == expected[index],
                "CLZ de " + std::to_string(inputs[index])
            );
        }
    }
    {   // QADD sature vers le haut et pose l'indicateur collant.
        Machine machine;
        machine.reg(1) = 0x7fff'ffffU;                 // Rm
        machine.reg(2) = 0x0000'0001U;                 // Rn
        machine.run({saturating(0U, 2U, 0U, 1U)});
        check(machine.reg(0) == 0x7fff'ffffU, "QADD sature au maximum signé");
        check(machine.flag(psr::saturation), "la saturation est signalée");
    }
    {   // Sans saturation, l'indicateur reste tel qu'il était.
        Machine machine;
        machine.reg(1) = 2U;
        machine.reg(2) = 3U;
        machine.run({saturating(0U, 2U, 0U, 1U)});
        check(machine.reg(0) == 5U, "QADD ordinaire additionne");
        check(!machine.flag(psr::saturation), "sans saturation l'indicateur ne se pose pas");
    }
    {   // Il est collant : une opération saine ne l'efface pas.
        Machine machine;
        machine.reg(1) = 0x7fff'ffffU;
        machine.reg(2) = 0x0000'0001U;
        machine.reg(3) = 2U;
        machine.reg(4) = 3U;
        machine.run({
            saturating(0U, 2U, 0U, 1U),
            saturating(0U, 4U, 5U, 3U),
        });
        check(machine.reg(5) == 5U, "la seconde opération aboutit");
        check(machine.flag(psr::saturation), "l'indicateur de saturation ne s'efface pas tout seul");
    }
    {
        Machine machine;
        machine.reg(1) = 0x8000'0000U;
        machine.reg(2) = 0x0000'0001U;
        machine.run({saturating(1U, 2U, 0U, 1U)});
        check(machine.reg(0) == 0x8000'0000U, "QSUB sature au minimum signé");
        check(machine.flag(psr::saturation), "la saturation est signalée");
    }
    {   // Le doublement sature avant l'addition, et cette saturation compte.
        Machine machine;
        machine.reg(1) = 0U;                            // Rm
        machine.reg(2) = 0x4000'0000U;                  // Rn, dont le double déborde
        machine.run({saturating(2U, 2U, 0U, 1U)});
        check(machine.reg(0) == 0x7fff'ffffU, "QDADD sature le doublement");
        check(machine.flag(psr::saturation), "la saturation du doublement est signalée");
    }
    {
        Machine machine;
        machine.reg(1) = 100U;
        machine.reg(2) = 10U;
        machine.run({
            saturating(2U, 2U, 0U, 1U),
            saturating(3U, 2U, 3U, 1U),
        });
        check(machine.reg(0) == 120U, "QDADD double puis ajoute");
        check(machine.reg(3) == 80U, "QDSUB double puis retranche");
        check(!machine.flag(psr::saturation), "aucune de ces deux-là ne sature");
    }
}

void l_echange_atomique_lit_avant_d_ecrire() {
    {
        Machine machine;
        machine.bus.write32(data_base, 0x1234'5678U);
        machine.reg(0) = data_base;
        machine.reg(1) = 0xdead'beefU;
        machine.run({swap(0U, 2U, 1U, false)});
        check(machine.reg(2) == 0x1234'5678U, "SWP rend l'ancienne valeur");
        check(machine.bus.read32(data_base) == 0xdead'beefU, "SWP écrit la nouvelle");
    }
    {   // Destination et source confondues : l'ordre lecture puis écriture décide.
        Machine machine;
        machine.bus.write32(data_base, 0x0000'0001U);
        machine.reg(0) = data_base;
        machine.reg(1) = 0x0000'0002U;
        machine.run({swap(0U, 1U, 1U, false)});
        check(machine.reg(1) == 0x0000'0001U, "le registre reçoit l'ancienne valeur");
        check(machine.bus.read32(data_base) == 0x0000'0002U, "la mémoire reçoit l'ancienne valeur du registre");
    }
    {
        Machine machine;
        machine.bus.write32(data_base, 0xffff'ffffU);
        machine.reg(0) = data_base + 1U;
        machine.reg(1) = 0x0000'0042U;
        machine.run({swap(0U, 2U, 1U, true)});
        check(machine.reg(2) == 0x0000'00ffU, "SWPB ne rend qu'un octet");
        check(machine.bus.read8(data_base + 1U) == 0x42U, "SWPB n'écrit qu'un octet");
        check(machine.bus.read8(data_base) == 0xffU, "SWPB ne déborde pas");
    }
}

void les_exceptions_basculent_de_mode_et_de_vecteur() {
    {   // Appel superviseur.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::user) | psr::carry;
        machine.run({software_interrupt(0x123456U)});
        check(machine.reg(15) == Arm9::software_interrupt_vector, "SWI saute au vecteur superviseur");
        check(machine.cpu.state().mode() == CpuMode::supervisor, "SWI passe en mode superviseur");
        check(machine.reg(14) == program_base + 4U, "SWI retient l'instruction suivante");
        check(
            machine.cpu.state().supervisor_spsr ==
                (static_cast<std::uint32_t>(CpuMode::user) | psr::carry),
            "le CPSR d'avant est sauvegardé"
        );
        check(machine.flag(psr::irq_disable), "les interruptions sont masquées à l'entrée");
        check(!machine.flag(psr::fiq_disable), "mais pas les interruptions rapides");
    }
    {   // Retour d'exception : écrire R15 avec le bit S ramène le CPSR sauvegardé.
        // Un appel superviseur retient déjà l'instruction suivante, d'où `MOVS`
        // et non le `SUBS ..., #4` que demandent les vecteurs matériels.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::user) | psr::carry;
        machine.load(0x0000'0008U, {data_op(op_mov, true, 0U, 15U, shifted_operand(14U, lsl, 0U), false)});
        machine.load(program_base, {software_interrupt(0U)});
        machine.reg(15) = program_base;
        machine.cpu.step();                            // l'appel
        machine.cpu.step();                            // le retour
        check(machine.reg(15) == program_base + 4U, "le retour reprend après l'appel");
        check(machine.cpu.state().mode() == CpuMode::user, "et retrouve le mode d'avant");
        check(machine.flag(psr::carry), "avec ses indicateurs");
        check(!machine.flag(psr::irq_disable), "et son masque d'interruption");
    }
    {   // Le retour ne remet pas seulement les bits de mode : il rend aussi la
        // banque de registres, ce qu'aucun indicateur ne montre.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::user);
        machine.reg(13) = 0x1234'0000U;
        machine.load(0x0000'0008U, {
            mov_immediate(13U, 0x77U),
            data_op(op_mov, true, 0U, 15U, shifted_operand(14U, lsl, 0U), false),
        });
        machine.load(program_base, {software_interrupt(0U)});
        machine.reg(15) = program_base;
        machine.cpu.step();                            // l'appel
        machine.cpu.step();                            // la pile du gestionnaire
        machine.cpu.step();                            // le retour
        check(machine.reg(13) == 0x1234'0000U, "la banque utilisateur est retrouvée intacte");
        check(
            machine.cpu.state().supervisor_r13_r14[0] == 0x77U,
            "et celle du gestionnaire est rangée"
        );
    }
    {   // Instruction inconnue.
        Machine machine;
        machine.run({coprocessor_data_operation});
        check(machine.reg(15) == Arm9::undefined_vector, "une instruction inconnue saute au vecteur prévu");
        check(machine.cpu.state().mode() == CpuMode::undefined, "et bascule en mode indéfini");
        check(machine.reg(14) == program_base + 4U, "le retour pointe après l'instruction fautive");
        check(machine.cpu.unimplemented_count() == 1U, "elle est comptée");
        check(machine.cpu.first_unimplemented() == coprocessor_data_operation, "et retenue");
    }
    {   // Seule la première est retenue, toutes sont comptées.
        Machine machine;
        machine.load(Arm9::undefined_vector, {branch(-3, false)});
        machine.load(program_base, {coprocessor_data_operation | 0x11U});
        machine.reg(15) = program_base;
        machine.cpu.step();
        check(machine.cpu.first_unimplemented() == (coprocessor_data_operation | 0x11U), "la première est retenue");
        machine.reg(15) = program_base;
        machine.load(program_base, {coprocessor_data_operation | 0x22U});
        machine.cpu.step();
        check(machine.cpu.unimplemented_count() == 2U, "les suivantes sont comptées");
        check(machine.cpu.first_unimplemented() == (coprocessor_data_operation | 0x11U), "sans effacer la première");
    }
    {   // Le bit d'état gouverne la largeur du décodage. Le même mot en mémoire
        // n'est pas la même instruction selon l'état, et c'est ce que ce cas
        // vérifie : la suite Thumb couvre le jeu lui-même, celle-ci se contente
        // de constater que `step` change bien de décodeur.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system) | psr::thumb;
        machine.reg(15) = program_base;
        // `MOV r0, #0x42` en Thumb tient sur un demi-mot ; lu comme un mot ARM,
        // ce serait tout autre chose.
        machine.bus.write16(program_base, 0x2042U);
        machine.cpu.step();
        check(machine.reg(0) == 0x42U, "l'état Thumb fait décoder un demi-mot");
        check(machine.reg(15) == program_base + 2U, "et n'avance que de deux octets");
        check(machine.cpu.unimplemented_count() == 0U, "sans rien signaler d'inconnu");
    }
    {   // Interruption ordinaire, prise entre deux instructions.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system);
        machine.reg(15) = program_base;
        machine.cpu.set_irq_line(true);
        machine.cpu.step();
        check(machine.reg(15) == Arm9::irq_vector, "l'interruption saute à son vecteur");
        check(machine.cpu.state().mode() == CpuMode::irq, "et bascule en mode interruption");
        check(machine.reg(14) == program_base + 4U, "le retour pointe l'instruction non exécutée, plus quatre");
        check(machine.flag(psr::irq_disable), "l'entrée masque les interruptions");
        check(!machine.flag(psr::fiq_disable), "sans masquer les rapides");
    }
    {   // Le retour canonique d'une interruption retranche quatre, parce que
        // l'instruction interrompue n'a pas été exécutée.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::user) | psr::overflow;
        machine.load(Arm9::irq_vector, {data_op(op_sub, true, 14U, 15U, immediate_operand(4U, 0U), true)});
        machine.load(program_base, {mov_immediate(1U, 7U)});
        machine.reg(15) = program_base;
        machine.cpu.set_irq_line(true);
        machine.cpu.step();                            // l'interruption
        machine.cpu.set_irq_line(false);
        machine.cpu.step();                            // le retour
        check(machine.reg(15) == program_base, "le retour reprend l'instruction interrompue");
        check(machine.cpu.state().mode() == CpuMode::user, "et retrouve le mode d'avant");
        check(machine.flag(psr::overflow), "avec ses indicateurs");
        machine.cpu.step();
        check(machine.reg(1) == 7U, "l'instruction interrompue finit par s'exécuter");
    }
    {   // Masquée, elle attend.
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::irq_disable;
        machine.reg(1) = 0U;
        machine.cpu.set_irq_line(true);
        machine.run({mov_immediate(1U, 5U)});
        check(machine.reg(1) == 5U, "l'instruction s'exécute malgré la ligne posée");
        check(machine.cpu.state().mode() == CpuMode::system, "le mode ne change pas");
    }
    {   // L'interruption rapide passe devant, et masque les deux lignes.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system);
        machine.reg(15) = program_base;
        machine.cpu.set_irq_line(true);
        machine.cpu.set_fiq_line(true);
        machine.cpu.step();
        check(machine.reg(15) == Arm9::fiq_vector, "la ligne rapide l'emporte");
        check(machine.cpu.state().mode() == CpuMode::fiq, "et bascule en mode rapide");
        check(machine.flag(psr::fiq_disable), "elle masque les interruptions rapides");
        check(machine.flag(psr::irq_disable), "et les ordinaires");
    }
    {   // Une exception prise en Thumb ramène en ARM.
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb;
        machine.reg(15) = program_base;
        machine.cpu.set_irq_line(true);
        machine.cpu.step();
        check(!machine.cpu.state().thumb(), "le gestionnaire s'exécute en ARM");
    }
    {   // La remise à zéro efface l'ardoise.
        Machine machine;
        machine.run({coprocessor_data_operation});
        check(machine.cpu.unimplemented_count() == 1U, "le compteur a bougé");
        machine.cpu.reset();
        check(machine.cpu.unimplemented_count() == 0U, "la remise à zéro l'efface");
        check(machine.cpu.first_unimplemented() == 0U, "et oublie la première instruction");
        check(machine.cpu.state().registers[15] == Arm9::reset_vector, "le compteur repart du vecteur de remise à zéro");
        check(machine.cpu.state().mode() == CpuMode::supervisor, "en mode superviseur");
        check(machine.cpu.state().flag(psr::irq_disable), "avec les interruptions masquées");
        check(machine.cpu.state().flag(psr::fiq_disable), "les deux");
    }
}

/** Un fragment de programme réaliste, pour éprouver l'ensemble d'un bloc. */
void un_petit_programme_s_execute_de_bout_en_bout() {
    // Somme des entiers de 1 à 10 par une boucle à compteur décroissant, puis
    // rangement du résultat en mémoire. Rien d'exotique : c'est justement le
    // genre de suite que produit un compilateur, et qui doit marcher avant tout
    // le reste.
    Machine machine;
    machine.reg(2) = data_base;
    machine.load(program_base, {
        mov_immediate(0U, 0U),                                                    // total
        mov_immediate(1U, 10U),                                                   // compteur
        data_op(op_add, false, 0U, 0U, shifted_operand(1U, lsl, 0U), false),      // total += compteur
        data_op(op_sub, true, 1U, 1U, immediate_operand(1U, 0U), true),           // compteur--
        branch(-4, false, 0x1U),                                                  // BNE vers l'addition
        single_transfer(2U, 0U, 0U, false, false, true, true, false),             // range le total
    });
    machine.reg(15) = program_base;

    // Deux instructions de mise en place, trois par tour pendant dix tours, puis
    // le rangement : le compte est fixé d'avance pour qu'un branchement qui
    // partirait ailleurs ne soit pas rattrapé par des pas supplémentaires.
    constexpr int steps = 2 + 10 * 3 + 1;
    for (int remaining = 0; remaining < steps; ++remaining) machine.cpu.step();

    check(machine.reg(0) == 55U, "la boucle somme les dix premiers entiers");
    check(machine.reg(1) == 0U, "le compteur est épuisé");
    check(machine.bus.read32(data_base) == 55U, "le résultat est rangé");
    check(machine.cpu.unimplemented_count() == 0U, "aucune instruction du programme n'est inconnue");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_conditions_gouvernent_chaque_instruction();
    le_decaleur_produit_valeur_et_retenue();
    le_traitement_de_donnees_pose_les_indicateurs();
    les_multiplications_courtes_et_longues();
    les_transferts_simples_couvrent_mot_et_octet();
    les_transferts_demi_mot_et_double_mot();
    les_transferts_par_blocs_parcourent_les_registres();
    les_branchements_enregistrent_le_retour();
    les_transferts_d_etat_respectent_le_mode();
    le_comptage_de_zeros_et_l_arithmetique_saturante();
    l_echange_atomique_lit_avant_d_ecrire();
    les_exceptions_basculent_de_mode_et_de_vecteur();
    un_petit_programme_s_execute_de_bout_en_bout();
    return 0;
}
