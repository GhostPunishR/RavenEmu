#include "cpu/arm_core.hpp"

#include "check.hpp"

#include <array>
#include <cstdint>
#include <initializer_list>
#include <string>

/**
 * Jeu Thumb du cœur ARM946E-S, et passage d'un jeu à l'autre.
 *
 * Comme pour le jeu ARM, les instructions sont encodées à la main, champ par
 * champ. Deux points sont surveillés de plus près que le reste, parce que ce
 * sont ceux où le jeu Thumb se distingue et donc ceux où une faute est
 * plausible : **quels indicateurs chaque forme écrit**, puisqu'il n'y a pas de
 * bit `S` pour le dire, et **le passage d'un état à l'autre**, qui se joue
 * chaque fois sur le bit bas d'une adresse.
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

// Encodeurs, un par format du manuel.

/** Format 1 : `000 op offset5 Rs Rd`. */
constexpr std::uint32_t shift_immediate(std::uint32_t op, std::uint32_t amount, std::uint32_t rs, std::uint32_t rd) noexcept {
    return (op << 11U) | (amount << 6U) | (rs << 3U) | rd;
}

/** Format 2 : `00011 I Op Rn Rs Rd`. */
constexpr std::uint32_t add_subtract(bool immediate, bool subtract, std::uint32_t operand, std::uint32_t rs, std::uint32_t rd) noexcept {
    return 0x1800U | (immediate ? (1U << 10U) : 0U) | (subtract ? (1U << 9U) : 0U) |
        (operand << 6U) | (rs << 3U) | rd;
}

/** Format 3 : `001 Op Rd offset8`. */
constexpr std::uint32_t immediate_operation(std::uint32_t op, std::uint32_t rd, std::uint32_t value) noexcept {
    return 0x2000U | (op << 11U) | (rd << 8U) | value;
}

/** Format 4 : `010000 Op Rs Rd`. */
constexpr std::uint32_t alu_operation(std::uint32_t op, std::uint32_t rs, std::uint32_t rd) noexcept {
    return 0x4000U | (op << 6U) | (rs << 3U) | rd;
}

/** Format 5 : `010001 Op H1 H2 Rs Rd`, les registres allant jusqu'à R15. */
constexpr std::uint32_t high_register(std::uint32_t op, std::uint32_t rs, std::uint32_t rd) noexcept {
    return 0x4400U | (op << 8U) | ((rd & 0x8U) != 0U ? (1U << 7U) : 0U) |
        ((rs & 0x8U) != 0U ? (1U << 6U) : 0U) | ((rs & 0x7U) << 3U) | (rd & 0x7U);
}

/** Format 5, cas du branchement par registre. */
constexpr std::uint32_t branch_exchange(std::uint32_t rm, bool link) noexcept {
    return 0x4700U | (link ? (1U << 7U) : 0U) | ((rm & 0xfU) << 3U);
}

/** Format 6 : `01001 Rd Word8`. */
constexpr std::uint32_t load_pc_relative(std::uint32_t rd, std::uint32_t words) noexcept {
    return 0x4800U | (rd << 8U) | words;
}

/** Format 7 : `0101 L B 0 Ro Rb Rd`. */
constexpr std::uint32_t transfer_register(bool load, bool byte_access, std::uint32_t ro, std::uint32_t rb, std::uint32_t rd) noexcept {
    return 0x5000U | (load ? (1U << 11U) : 0U) | (byte_access ? (1U << 10U) : 0U) |
        (ro << 6U) | (rb << 3U) | rd;
}

/** Format 8 : `0101 H S 1 Ro Rb Rd`. */
constexpr std::uint32_t transfer_signed(std::uint32_t kind, std::uint32_t ro, std::uint32_t rb, std::uint32_t rd) noexcept {
    return 0x5200U | (kind << 10U) | (ro << 6U) | (rb << 3U) | rd;
}

/** Format 9 : `011 B L Offset5 Rb Rd`. */
constexpr std::uint32_t transfer_immediate(bool load, bool byte_access, std::uint32_t offset, std::uint32_t rb, std::uint32_t rd) noexcept {
    return 0x6000U | (byte_access ? (1U << 12U) : 0U) | (load ? (1U << 11U) : 0U) |
        (offset << 6U) | (rb << 3U) | rd;
}

/** Format 10 : `1000 L Offset5 Rb Rd`. */
constexpr std::uint32_t transfer_halfword(bool load, std::uint32_t offset, std::uint32_t rb, std::uint32_t rd) noexcept {
    return 0x8000U | (load ? (1U << 11U) : 0U) | (offset << 6U) | (rb << 3U) | rd;
}

/** Format 11 : `1001 L Rd Word8`. */
constexpr std::uint32_t transfer_stack(bool load, std::uint32_t rd, std::uint32_t words) noexcept {
    return 0x9000U | (load ? (1U << 11U) : 0U) | (rd << 8U) | words;
}

/** Format 12 : `1010 SP Rd Word8`. */
constexpr std::uint32_t load_address(bool from_stack, std::uint32_t rd, std::uint32_t words) noexcept {
    return 0xa000U | (from_stack ? (1U << 11U) : 0U) | (rd << 8U) | words;
}

/** Format 13 : `10110000 S SWord7`. */
constexpr std::uint32_t adjust_stack(bool subtract, std::uint32_t words) noexcept {
    return 0xb000U | (subtract ? (1U << 7U) : 0U) | words;
}

/** Format 14 : `1011 L 10 R Rlist`. */
constexpr std::uint32_t push_pop(bool load, bool extra, std::uint32_t list) noexcept {
    return 0xb400U | (load ? (1U << 11U) : 0U) | (extra ? (1U << 8U) : 0U) | list;
}

/** Format 15 : `1100 L Rb Rlist`. */
constexpr std::uint32_t block_transfer(bool load, std::uint32_t rb, std::uint32_t list) noexcept {
    return 0xc000U | (load ? (1U << 11U) : 0U) | (rb << 8U) | list;
}

/** Format 16 : `1101 Cond Soffset8`. */
constexpr std::uint32_t conditional_branch(std::uint32_t cond, std::int32_t halfwords) noexcept {
    return 0xd000U | (cond << 8U) | (static_cast<std::uint32_t>(halfwords) & 0xffU);
}

constexpr std::uint32_t software_interrupt(std::uint32_t comment) noexcept {
    return 0xdf00U | (comment & 0xffU);
}

/** Format 18 : `11100 Offset11`. */
constexpr std::uint32_t branch(std::int32_t halfwords) noexcept {
    return 0xe000U | (static_cast<std::uint32_t>(halfwords) & 0x7ffU);
}

/** Format 19, premier demi-mot : dépose la moitié haute de la cible. */
constexpr std::uint32_t long_branch_prefix(std::int32_t high) noexcept {
    return 0xf000U | (static_cast<std::uint32_t>(high) & 0x7ffU);
}

/** Format 19, second demi-mot, restant en Thumb. */
constexpr std::uint32_t long_branch_suffix(std::uint32_t low) noexcept {
    return 0xf800U | (low & 0x7ffU);
}

/** Format 19, second demi-mot, basculant en ARM. */
constexpr std::uint32_t long_branch_exchange_suffix(std::uint32_t low) noexcept {
    return 0xe800U | (low & 0x7ffU);
}

// Opérations du format 4, dans l'ordre du manuel.
constexpr std::uint32_t alu_and = 0x0;
constexpr std::uint32_t alu_eor = 0x1;
constexpr std::uint32_t alu_lsl = 0x2;
constexpr std::uint32_t alu_lsr = 0x3;
constexpr std::uint32_t alu_asr = 0x4;
constexpr std::uint32_t alu_adc = 0x5;
constexpr std::uint32_t alu_sbc = 0x6;
constexpr std::uint32_t alu_ror = 0x7;
constexpr std::uint32_t alu_tst = 0x8;
constexpr std::uint32_t alu_neg = 0x9;
constexpr std::uint32_t alu_cmp = 0xa;
constexpr std::uint32_t alu_cmn = 0xb;
constexpr std::uint32_t alu_orr = 0xc;
constexpr std::uint32_t alu_mul = 0xd;
constexpr std::uint32_t alu_bic = 0xe;
constexpr std::uint32_t alu_mvn = 0xf;

/** Un processeur en état Thumb, sa mémoire, et de quoi y déposer un programme. */
struct Machine {
    TestBus bus{};
    Arm9 cpu{bus};

    Machine() {
        cpu.reset();
        cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system) | psr::thumb;
    }

    void load(std::uint32_t address, std::initializer_list<std::uint32_t> program) {
        auto cursor = address;
        for (const auto halfword : program) {
            bus.write16(cursor, static_cast<std::uint16_t>(halfword));
            cursor += 2U;
        }
    }

    void load_arm(std::uint32_t address, std::initializer_list<std::uint32_t> program) {
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

// --------------------------------------------------------------------------

void le_decalage_immediat_ecrit_valeur_et_retenue() {
    struct Case {
        std::uint32_t op;
        std::uint32_t amount;
        std::uint32_t input;
        bool carry_in;
        std::uint32_t result;
        bool carry_out;
        const char* label;
    };

    // Forme immédiate : un décalage nul y code 32 pour LSR et ASR, et n'est
    // neutre que pour LSL.
    constexpr Case cases[] = {
        {0x0, 0U, 0x1234'5678U, true, 0x1234'5678U, true, "LSL #0 est neutre, retenue comprise"},
        {0x0, 4U, 0x1234'5678U, false, 0x2345'6780U, true, "LSL #4 sort le bit 28"},
        {0x0, 1U, 0x8000'0000U, false, 0x0000'0000U, true, "LSL #1 sort le bit haut"},
        {0x1, 0U, 0x8000'0000U, false, 0x0000'0000U, true, "LSR #0 vaut LSR #32"},
        {0x1, 4U, 0x0000'00f0U, false, 0x0000'000fU, false, "LSR #4"},
        {0x1, 20U, 0x00f0'0000U, false, 0x0000'000fU, false, "LSR #20 exerce le cinquième bit du champ"},
        {0x0, 20U, 0x0000'000fU, false, 0x00f0'0000U, false, "LSL #20"},
        {0x2, 0U, 0x8000'0000U, false, 0xffff'ffffU, true, "ASR #0 vaut ASR #32"},
        {0x2, 4U, 0x8000'0000U, false, 0xf800'0000U, false, "ASR #4 recopie le signe"},
    };

    for (const auto& scenario : cases) {
        Machine machine;
        machine.cpu.state().set_flag(psr::carry, scenario.carry_in);
        machine.reg(1) = scenario.input;
        machine.run({shift_immediate(scenario.op, scenario.amount, 1U, 0U)});
        check(machine.reg(0) == scenario.result, std::string{scenario.label} + " : valeur");
        check(machine.flag(psr::carry) == scenario.carry_out, std::string{scenario.label} + " : retenue");
    }

    // Le décalage écrit N et Z, et laisse V intact.
    Machine machine;
    machine.cpu.state().set_flag(psr::overflow, true);
    machine.reg(1) = 0U;
    machine.run({shift_immediate(0x0, 3U, 1U, 0U)});
    check(machine.flag(psr::zero), "un résultat nul pose Z");
    check(machine.flag(psr::overflow), "un décalage ne touche pas V");
}

void l_addition_a_trois_operandes_pose_tous_les_indicateurs() {
    {
        Machine machine;
        machine.reg(1) = 10U;
        machine.reg(2) = 3U;
        machine.run({add_subtract(false, false, 2U, 1U, 0U)});
        check(machine.reg(0) == 13U, "ADD à trois registres");
        check_flags(machine, false, false, false, false, "ADD ordinaire");
    }
    {
        Machine machine;
        machine.reg(1) = 10U;
        machine.run({add_subtract(true, false, 3U, 1U, 0U)});
        check(machine.reg(0) == 13U, "ADD avec une immédiate de trois bits");
    }
    {
        Machine machine;
        machine.reg(1) = 10U;
        machine.reg(2) = 3U;
        machine.run({add_subtract(false, true, 2U, 1U, 0U)});
        check(machine.reg(0) == 7U, "SUB à trois registres");
        check_flags(machine, false, false, true, false, "SUB sans emprunt");
    }
    {
        Machine machine;
        machine.reg(1) = 0x7fff'ffffU;
        machine.run({add_subtract(true, false, 1U, 1U, 0U)});
        check(machine.reg(0) == 0x8000'0000U, "ADD qui déborde");
        check_flags(machine, true, false, false, true, "ADD qui déborde");
    }
    {
        Machine machine;
        machine.reg(1) = 0U;
        machine.run({add_subtract(true, true, 1U, 1U, 0U)});
        check(machine.reg(0) == 0xffff'ffffU, "SUB qui emprunte");
        check_flags(machine, true, false, false, false, "SUB qui emprunte");
    }
    {   // Une soustraction exacte ne laisse pas d'emprunt.
        Machine machine;
        machine.reg(1) = 9U;
        machine.reg(2) = 9U;
        machine.run({add_subtract(false, true, 2U, 1U, 0U)});
        check(machine.reg(0) == 0U, "SUB d'égaux");
        check_flags(machine, false, true, true, false, "SUB d'égaux");
    }
    {   // Une addition qui boucle pose la retenue.
        Machine machine;
        machine.reg(1) = 0xffff'ffffU;
        machine.run({add_subtract(true, false, 1U, 1U, 0U)});
        check(machine.reg(0) == 0U, "ADD qui boucle");
        check_flags(machine, false, true, true, false, "ADD qui boucle");
    }
    {   // Une immédiate nulle reste une addition, pas une copie muette.
        Machine machine;
        machine.reg(1) = 0U;
        machine.run({add_subtract(true, false, 0U, 1U, 0U)});
        check_flags(machine, false, true, false, false, "ADD #0 d'un registre nul");
    }
}

void les_operations_immediates_ecrivent_ce_qu_il_faut() {
    {   // MOV n'a pas de rotation, donc rien à dire sur la retenue.
        Machine machine;
        machine.cpu.state().set_flag(psr::carry, true);
        machine.cpu.state().set_flag(psr::overflow, true);
        machine.run({immediate_operation(0x0, 3U, 0x42U)});
        check(machine.reg(3) == 0x42U, "MOV immédiat");
        check_flags(machine, false, false, true, true, "MOV immédiat ne touche ni C ni V");
    }
    {
        Machine machine;
        machine.run({immediate_operation(0x0, 3U, 0x00U)});
        check(machine.flag(psr::zero), "MOV #0 pose Z");
    }
    {   // CMP n'écrit pas sa destination.
        Machine machine;
        machine.reg(4) = 0x20U;
        machine.run({immediate_operation(0x1, 4U, 0x20U)});
        check(machine.reg(4) == 0x20U, "CMP immédiat n'écrit pas");
        check_flags(machine, false, true, true, false, "CMP d'égaux");
    }
    {
        Machine machine;
        machine.reg(4) = 0x10U;
        machine.run({immediate_operation(0x1, 4U, 0x20U)});
        check_flags(machine, true, false, false, false, "CMP d'un plus petit");
    }
    {
        Machine machine;
        machine.reg(5) = 0xffff'fff0U;
        machine.run({immediate_operation(0x2, 5U, 0x10U)});
        check(machine.reg(5) == 0U, "ADD immédiat");
        check_flags(machine, false, true, true, false, "ADD immédiat qui boucle");
    }
    {
        Machine machine;
        machine.reg(6) = 0x100U;
        machine.run({immediate_operation(0x3, 6U, 0xffU)});
        check(machine.reg(6) == 1U, "SUB immédiat");
        check_flags(machine, false, false, true, false, "SUB immédiat");
    }
    {   // Retranchée d'elle-même, la valeur ne laisse pas d'emprunt.
        Machine machine;
        machine.reg(6) = 0x33U;
        machine.run({immediate_operation(0x3, 6U, 0x33U)});
        check(machine.reg(6) == 0U, "SUB immédiat d'égaux");
        check_flags(machine, false, true, true, false, "SUB immédiat d'égaux");
    }
    {   // Et une soustraction qui traverse zéro l'efface.
        Machine machine;
        machine.reg(6) = 0x10U;
        machine.run({immediate_operation(0x3, 6U, 0x20U)});
        check(machine.reg(6) == 0xffff'fff0U, "SUB immédiat qui emprunte");
        check_flags(machine, true, false, false, false, "SUB immédiat qui emprunte");
    }
}

void les_operations_de_l_unite_arithmetique() {
    {   // Les opérations logiques écrivent N et Z, et ne touchent ni C ni V.
        struct Case {
            std::uint32_t op;
            std::uint32_t left;
            std::uint32_t right;
            std::uint32_t result;
            const char* label;
        };
        constexpr Case cases[] = {
            {alu_and, 0xf0f0'f0f0U, 0x00ff'00ffU, 0x00f0'00f0U, "AND"},
            {alu_eor, 0xf0f0'f0f0U, 0x00ff'00ffU, 0xf00f'f00fU, "EOR"},
            {alu_orr, 0xf0f0'f0f0U, 0x00ff'00ffU, 0xf0ff'f0ffU, "ORR"},
            {alu_bic, 0xf0f0'f0f0U, 0x00ff'00ffU, 0xf000'f000U, "BIC"},
            {alu_mvn, 0U, 0x00ff'00ffU, 0xff00'ff00U, "MVN"},
            {alu_mul, 7U, 6U, 42U, "MUL"},
        };
        for (const auto& scenario : cases) {
            Machine machine;
            machine.cpu.state().set_flag(psr::carry, true);
            machine.cpu.state().set_flag(psr::overflow, true);
            machine.reg(0) = scenario.left;
            machine.reg(1) = scenario.right;
            machine.run({alu_operation(scenario.op, 1U, 0U)});
            check(machine.reg(0) == scenario.result, std::string{scenario.label} + " : valeur");
            check(machine.flag(psr::carry), std::string{scenario.label} + " ne touche pas C");
            check(machine.flag(psr::overflow), std::string{scenario.label} + " ne touche pas V");
        }
    }
    {   // MUL tronque à trente-deux bits et pose N sur le bit haut.
        Machine machine;
        machine.reg(0) = 0xffff'ffffU;
        machine.reg(1) = 3U;
        machine.run({alu_operation(alu_mul, 1U, 0U)});
        check(machine.reg(0) == 0xffff'fffdU, "MUL d'un négatif");
        check(machine.flag(psr::negative), "MUL pose N");
    }
    {   // Décalages par registre : un décalage nul y est neutre, retenue comprise.
        struct Case {
            std::uint32_t op;
            std::uint32_t input;
            std::uint32_t amount;
            bool carry_in;
            std::uint32_t result;
            bool carry_out;
            const char* label;
        };
        constexpr Case cases[] = {
            {alu_lsl, 0x1234'5678U, 0U, true, 0x1234'5678U, true, "LSL Rs=0 est neutre"},
            {alu_lsl, 0x0000'0003U, 32U, false, 0U, true, "LSL Rs=32 sort le bit 0"},
            {alu_lsl, 0x0000'0003U, 33U, true, 0U, false, "LSL Rs=33 vide tout"},
            {alu_lsr, 0x8000'0000U, 32U, false, 0U, true, "LSR Rs=32 sort le bit 31"},
            {alu_lsr, 0x0000'0010U, 4U, true, 1U, false, "LSR Rs=4"},
            {alu_asr, 0x8000'0000U, 40U, false, 0xffff'ffffU, true, "ASR Rs>32 sature au signe"},
            {alu_ror, 0x1234'5678U, 4U, false, 0x8123'4567U, true, "ROR Rs=4"},
            {alu_ror, 0x1234'5678U, 32U, false, 0x1234'5678U, false, "ROR Rs=32 est neutre"},
            {alu_ror, 0x1234'5678U, 0U, true, 0x1234'5678U, true, "ROR Rs=0 est neutre et n'est pas RRX"},
            {alu_lsl, 0x1234'5678U, 0x100U, true, 0x1234'5678U, true, "LSL Rs=256 ne lit que huit bits, donc zéro"},
            {alu_lsr, 0x1234'5678U, 0x101U, false, 0x091a'2b3cU, false, "LSR Rs=257 ne lit que huit bits, donc un"},
        };
        for (const auto& scenario : cases) {
            Machine machine;
            machine.cpu.state().set_flag(psr::carry, scenario.carry_in);
            machine.reg(0) = scenario.input;
            machine.reg(1) = scenario.amount;
            machine.run({alu_operation(scenario.op, 1U, 0U)});
            check(machine.reg(0) == scenario.result, std::string{scenario.label} + " : valeur");
            check(machine.flag(psr::carry) == scenario.carry_out, std::string{scenario.label} + " : retenue");
        }
    }
    {
        Machine machine;
        machine.cpu.state().set_flag(psr::carry, true);
        machine.reg(0) = 1U;
        machine.reg(1) = 1U;
        machine.run({alu_operation(alu_adc, 1U, 0U)});
        check(machine.reg(0) == 3U, "ADC ajoute la retenue");
    }
    {
        Machine machine;
        machine.cpu.state().set_flag(psr::carry, false);
        machine.reg(0) = 5U;
        machine.reg(1) = 1U;
        machine.run({alu_operation(alu_sbc, 1U, 0U)});
        check(machine.reg(0) == 3U, "SBC retranche l'emprunt");
        check(machine.flag(psr::carry), "SBC sans nouvel emprunt garde C");
    }
    {   // NEG est une soustraction inversée, avec les indicateurs qui vont avec.
        Machine machine;
        machine.reg(1) = 5U;
        machine.run({alu_operation(alu_neg, 1U, 0U)});
        check(machine.reg(0) == 0xffff'fffbU, "NEG");
        check_flags(machine, true, false, false, false, "NEG d'un positif");
    }
    {
        Machine machine;
        machine.reg(1) = 0U;
        machine.run({alu_operation(alu_neg, 1U, 0U)});
        check(machine.reg(0) == 0U, "NEG de zéro");
        check_flags(machine, false, true, true, false, "NEG de zéro n'emprunte pas");
    }
    {
        Machine machine;
        machine.reg(1) = 0x8000'0000U;
        machine.run({alu_operation(alu_neg, 1U, 0U)});
        check(machine.reg(0) == 0x8000'0000U, "NEG du minimum signé");
        check(machine.flag(psr::overflow), "et déborde");
    }
    {   // Les comparaisons n'écrivent rien.
        Machine machine;
        machine.reg(0) = 0x0000'00f0U;
        machine.reg(1) = 0x0000'000fU;
        machine.run({alu_operation(alu_tst, 1U, 0U)});
        check(machine.reg(0) == 0x0000'00f0U, "TST n'écrit pas");
        check(machine.flag(psr::zero), "TST sans bit commun pose Z");
    }
    {
        Machine machine;
        machine.reg(0) = 7U;
        machine.reg(1) = 7U;
        machine.run({alu_operation(alu_cmp, 1U, 0U)});
        check(machine.reg(0) == 7U, "CMP n'écrit pas");
        check_flags(machine, false, true, true, false, "CMP d'égaux");
    }
    {
        Machine machine;
        machine.reg(0) = 0x7fff'ffffU;
        machine.reg(1) = 1U;
        machine.run({alu_operation(alu_cmn, 1U, 0U)});
        check(machine.reg(0) == 0x7fff'ffffU, "CMN n'écrit pas");
        check_flags(machine, true, false, false, true, "CMN qui déborde");
    }
}

void les_registres_hauts_sont_atteignables() {
    {   // L'addition sur registres hauts n'écrit aucun indicateur : c'est ce qui
        // la rend utilisable entre une comparaison et son branchement.
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb | psr::zero | psr::carry;
        machine.reg(8) = 100U;
        machine.reg(1) = 5U;
        machine.run({high_register(0x0, 1U, 8U)});
        check(machine.reg(8) == 105U, "ADD vers un registre haut");
        check(machine.flag(psr::zero), "et n'écrase pas les indicateurs");
        check(machine.flag(psr::carry), "aucun d'entre eux");
    }
    {
        Machine machine;
        machine.reg(9) = 0x1234U;
        machine.run({high_register(0x2, 9U, 2U)});
        check(machine.reg(2) == 0x1234U, "MOV depuis un registre haut");
    }
    {   // La comparaison, elle, écrit bien les indicateurs.
        Machine machine;
        machine.reg(10) = 7U;
        machine.reg(3) = 7U;
        machine.run({high_register(0x1, 10U, 3U)});
        check_flags(machine, false, true, true, false, "CMP sur registre haut");
    }
    {   // Écrire R15 branche, et le bit bas de la cible est écarté.
        Machine machine;
        machine.reg(1) = data_base + 1U;
        machine.run({high_register(0x2, 1U, 15U)});
        check(machine.reg(15) == data_base, "MOV vers R15 branche en alignant");
        check(machine.cpu.state().thumb(), "et ne change pas d'état");
    }
    {
        Machine machine;
        machine.reg(1) = 0x10U;
        machine.run({high_register(0x0, 1U, 15U)});
        check(machine.reg(15) == program_base + 4U + 0x10U, "ADD vers R15 branche depuis l'avance");
    }
    {   // R15 lu vaut l'instruction plus quatre.
        Machine machine;
        machine.run({high_register(0x2, 15U, 0U)});
        check(machine.reg(0) == program_base + 4U, "R15 lu en Thumb vaut l'instruction plus quatre");
    }
}

void le_chargement_relatif_au_compteur_aligne_sa_base() {
    {   // Le compteur est aligné sur le mot avant l'addition : une instruction
        // peut être à un demi-mot impair, pas la table qu'elle vise.
        Machine machine;
        machine.bus.write32(program_base + 4U, 0xcafe'0000U);
        machine.run({load_pc_relative(0U, 0U)});
        check(machine.reg(0) == 0xcafe'0000U, "chargement relatif depuis une instruction alignée");
    }
    {
        Machine machine;
        machine.bus.write32(program_base + 4U, 0xdead'0000U);
        // Depuis un demi-mot impair, R15 vaut program_base + 6 : sans
        // alignement, la lecture porterait sur deux mots à cheval.
        machine.bus.write32(program_base + 8U, 0x0bad'0badU);
        machine.load(program_base + 2U, {load_pc_relative(1U, 0U)});
        machine.reg(15) = program_base + 2U;
        machine.cpu.step();
        check(machine.reg(1) == 0xdead'0000U, "et depuis une instruction à un demi-mot impair");
    }
    {
        Machine machine;
        machine.bus.write32(program_base + 4U + 0x20U, 0x0000'0042U);
        machine.run({load_pc_relative(2U, 8U)});
        check(machine.reg(2) == 0x42U, "le décalage compte en mots");
    }
}

void les_transferts_couvrent_les_six_largeurs() {
    {   // Décalage par registre, quatre formes non signées.
        Machine machine;
        machine.reg(1) = data_base;
        machine.reg(2) = 4U;
        machine.reg(0) = 0x1234'5678U;
        machine.run({
            transfer_register(false, false, 2U, 1U, 0U),
            transfer_register(true, false, 2U, 1U, 3U),
            transfer_register(false, true, 2U, 1U, 0U),
            transfer_register(true, true, 2U, 1U, 4U),
        });
        check(machine.reg(3) == 0x1234'5678U, "STR puis LDR");
        check(machine.reg(4) == 0x78U, "STRB puis LDRB");
        check(machine.bus.read8(data_base + 5U) == 0x56U, "STRB n'a écrit qu'un octet");
    }
    {   // Une lecture de mot désalignée ramène le mot aligné, tourné.
        Machine machine;
        machine.bus.write32(data_base, 0x1234'5678U);
        machine.reg(1) = data_base;
        machine.reg(2) = 1U;
        machine.run({transfer_register(true, false, 2U, 1U, 0U)});
        check(machine.reg(0) == 0x7812'3456U, "LDR désaligné tourne le mot");
    }
    {   // Formes signées et demi-mot.
        Machine machine;
        machine.reg(1) = data_base;
        machine.reg(2) = 0U;
        machine.reg(0) = 0x1234'8765U;
        machine.run({
            transfer_signed(0x0, 2U, 1U, 0U),
            transfer_signed(0x2, 2U, 1U, 3U),
            transfer_signed(0x3, 2U, 1U, 4U),
            transfer_signed(0x1, 2U, 1U, 5U),
        });
        check(machine.bus.read16(data_base) == 0x8765U, "STRH n'écrit qu'un demi-mot");
        check(machine.reg(3) == 0x0000'8765U, "LDRH complète par des zéros");
        check(machine.reg(4) == 0xffff'8765U, "LDRSH étend le signe");
        check(machine.reg(5) == 0x0000'0065U, "LDRSB d'un octet positif");
    }
    {
        Machine machine;
        machine.bus.write8(data_base, 0xffU);
        machine.reg(1) = data_base;
        machine.reg(2) = 0U;
        machine.run({transfer_signed(0x1, 2U, 1U, 0U)});
        check(machine.reg(0) == 0xffff'ffffU, "LDRSB étend le signe d'un octet négatif");
    }
    {   // Décalage immédiat : il compte en unités d'accès, pas en octets.
        Machine machine;
        machine.reg(1) = data_base;
        machine.reg(0) = 0xaabb'ccddU;
        machine.run({
            transfer_immediate(false, false, 3U, 1U, 0U),
            transfer_immediate(true, false, 3U, 1U, 2U),
            transfer_immediate(true, true, 12U, 1U, 3U),
        });
        check(machine.bus.read32(data_base + 12U) == 0xaabb'ccddU, "STR immédiat multiplie par quatre");
        check(machine.reg(2) == 0xaabb'ccddU, "LDR immédiat relit");
        check(machine.reg(3) == 0xddU, "LDRB immédiat compte en octets");
    }
    {
        Machine machine;
        machine.reg(1) = data_base;
        machine.reg(0) = 0x1234'5678U;
        machine.run({
            transfer_halfword(false, 2U, 1U, 0U),
            transfer_halfword(true, 2U, 1U, 3U),
        });
        check(machine.bus.read16(data_base + 4U) == 0x5678U, "STRH immédiat multiplie par deux");
        check(machine.reg(3) == 0x5678U, "LDRH immédiat relit");
    }
    {   // Relatif à la pile.
        Machine machine;
        machine.reg(13) = data_base;
        machine.reg(0) = 0x0bad'cafeU;
        machine.run({
            transfer_stack(false, 0U, 2U),
            transfer_stack(true, 1U, 2U),
        });
        check(machine.bus.read32(data_base + 8U) == 0x0bad'cafeU, "rangement relatif à la pile");
        check(machine.reg(1) == 0x0bad'cafeU, "et relecture");
    }
}

void le_calcul_d_adresse_n_ecrit_aucun_indicateur() {
    {
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb | psr::zero;
        machine.reg(13) = data_base;
        machine.run({load_address(true, 0U, 4U)});
        check(machine.reg(0) == data_base + 16U, "adresse relative à la pile");
        check(machine.flag(psr::zero), "et aucun indicateur écrasé");
    }
    {
        Machine machine;
        machine.run({load_address(false, 1U, 2U)});
        check(machine.reg(1) == program_base + 4U + 8U, "adresse relative au compteur");
    }
    {   // Le compteur est aligné sur le mot, comme pour le chargement.
        Machine machine;
        machine.load(program_base + 2U, {load_address(false, 1U, 0U)});
        machine.reg(15) = program_base + 2U;
        machine.cpu.step();
        check(machine.reg(1) == program_base + 4U, "la base est alignée sur le mot");
    }
    {
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::thumb | psr::negative;
        machine.reg(13) = data_base;
        machine.run({adjust_stack(true, 4U), adjust_stack(false, 2U)});
        check(machine.reg(13) == data_base - 16U + 8U, "la pile monte puis redescend");
        check(machine.flag(psr::negative), "sans écrire d'indicateur");
    }
}

void l_empilement_et_le_depilement() {
    {   // Le registre le plus bas occupe l'adresse la plus basse, des deux côtés.
        Machine machine;
        machine.reg(13) = data_base + 0x100U;
        machine.reg(0) = 0x1111'1111U;
        machine.reg(1) = 0x2222'2222U;
        machine.reg(4) = 0x4444'4444U;
        machine.run({push_pop(false, false, 0b0001'0011U)});
        check(machine.reg(13) == data_base + 0x100U - 12U, "la pile descend de trois mots");
        check(machine.bus.read32(data_base + 0x100U - 12U) == 0x1111'1111U, "R0 à l'adresse la plus basse");
        check(machine.bus.read32(data_base + 0x100U - 8U) == 0x2222'2222U, "puis R1");
        check(machine.bus.read32(data_base + 0x100U - 4U) == 0x4444'4444U, "puis R4");
    }
    {   // Aller-retour complet, avec le registre de lien.
        Machine machine;
        machine.reg(13) = data_base + 0x100U;
        machine.reg(0) = 0xaaaa'aaaaU;
        machine.reg(14) = 0xeeee'eeeeU;
        machine.run({
            push_pop(false, true, 0b0000'0001U),
            immediate_operation(0x0, 0U, 0U),
            push_pop(true, false, 0b0000'0001U),
        });
        check(machine.reg(0) == 0xaaaa'aaaaU, "R0 est retrouvé");
        check(
            machine.bus.read32(data_base + 0x100U - 4U) == 0xeeee'eeeeU,
            "le registre de lien occupe l'adresse la plus haute"
        );
    }
    {   // Dépiler le compteur peut ramener en ARM : le bit bas décide.
        Machine machine;
        machine.reg(13) = data_base;
        machine.bus.write32(data_base, data_base + 0x40U);
        machine.run({push_pop(true, true, 0U)});
        check(machine.reg(15) == data_base + 0x40U, "le dépilement du compteur branche");
        check(!machine.cpu.state().thumb(), "une cible paire ramène en ARM");
        check(machine.reg(13) == data_base + 4U, "et la pile remonte");
    }
    {
        Machine machine;
        machine.reg(13) = data_base;
        machine.bus.write32(data_base, data_base + 0x41U);
        machine.run({push_pop(true, true, 0U)});
        check(machine.reg(15) == data_base + 0x40U, "le bit d'état est écarté de l'adresse");
        check(machine.cpu.state().thumb(), "une cible impaire reste en Thumb");
    }
    {   // Une liste vide n'a pas de comportement défini : elle est signalée.
        Machine machine;
        machine.reg(13) = data_base;
        machine.run({push_pop(false, false, 0U)});
        check(machine.cpu.unimplemented_count() == 1U, "une pile vide est signalée");
        // L'exception a basculé de mode, donc de banque : la pile à vérifier est
        // celle qui a été rangée en quittant le mode système.
        check(
            machine.cpu.state().user_r13_r14[0] == data_base,
            "et la pile du mode d'origine n'a pas bougé"
        );
    }
}

void les_transferts_par_blocs_deplacent_la_base() {
    {
        Machine machine;
        machine.reg(3) = data_base;
        machine.reg(0) = 0x1111'1111U;
        machine.reg(1) = 0x2222'2222U;
        machine.run({block_transfer(false, 3U, 0b0000'0011U)});
        check(machine.bus.read32(data_base) == 0x1111'1111U, "R0 en premier");
        check(machine.bus.read32(data_base + 4U) == 0x2222'2222U, "puis R1");
        check(machine.reg(3) == data_base + 8U, "la base avance de deux mots");
    }
    {
        Machine machine;
        machine.reg(3) = data_base;
        machine.bus.write32(data_base, 0x0000'0007U);
        machine.bus.write32(data_base + 4U, 0x0000'0008U);
        machine.run({block_transfer(true, 3U, 0b0000'0011U)});
        check(machine.reg(0) == 7U, "R0 est chargé");
        check(machine.reg(1) == 8U, "R1 aussi");
        check(machine.reg(3) == data_base + 8U, "et la base avance");
    }
    {   // Une base rechargée par la liste garde la valeur lue.
        Machine machine;
        machine.reg(1) = data_base;
        machine.bus.write32(data_base, 0x0000'0005U);
        machine.bus.write32(data_base + 4U, 0x0bad'0000U);
        machine.run({block_transfer(true, 1U, 0b0000'0011U)});
        check(machine.reg(0) == 5U, "R0 est chargé");
        check(machine.reg(1) == 0x0bad'0000U, "la valeur chargée dans la base l'emporte");
    }
    {
        Machine machine;
        machine.reg(3) = data_base;
        machine.run({block_transfer(true, 3U, 0U)});
        check(machine.cpu.unimplemented_count() == 1U, "une liste vide est signalée");
    }
}

void les_branchements_et_le_passage_d_un_jeu_a_l_autre() {
    {   // Branchement conditionnel, pris et non pris.
        Machine machine;
        machine.cpu.state().set_flag(psr::zero, true);
        machine.run({conditional_branch(0x0, 4)});
        check(machine.reg(15) == program_base + 4U + 8U, "un branchement pris saute depuis l'avance");
    }
    {
        Machine machine;
        machine.cpu.state().set_flag(psr::zero, false);
        machine.run({conditional_branch(0x0, 4)});
        check(machine.reg(15) == program_base + 2U, "un branchement non pris avance d'un demi-mot");
    }
    {
        Machine machine;
        machine.cpu.state().set_flag(psr::zero, false);
        machine.run({conditional_branch(0x1, -8)});
        check(machine.reg(15) == program_base + 4U - 16U, "un branchement conditionnel recule");
    }
    {   // La condition 0xE n'existe pas ici : ARM y a rangé un trou.
        Machine machine;
        machine.run({conditional_branch(0xe, 0)});
        check(machine.cpu.unimplemented_count() == 1U, "la condition sans emploi est signalée");
    }
    {
        Machine machine;
        machine.run({branch(6)});
        check(machine.reg(15) == program_base + 4U + 12U, "branchement inconditionnel");
    }
    {
        Machine machine;
        machine.run({branch(-4)});
        check(machine.reg(15) == program_base + 4U - 8U, "et vers l'arrière");
    }
    {   // Appel long : deux demi-mots, dont le premier ne branche pas.
        Machine machine;
        machine.run({long_branch_prefix(0), long_branch_suffix(4)});
        check(machine.reg(15) == program_base + 4U + 8U, "l'appel long saute");
        check(machine.reg(14) == ((program_base + 4U) | 1U), "et retient l'instruction suivante, marquée Thumb");
    }
    {   // Le premier demi-mot dépose seulement la moitié haute de la cible.
        Machine machine;
        machine.run({long_branch_prefix(0)});
        check(machine.reg(14) == program_base + 4U, "le préfixe ne fait que préparer le lien");
        check(machine.reg(15) == program_base + 2U, "et ne branche pas");
    }
    {   // Portée longue, en arrière.
        Machine machine;
        machine.reg(15) = program_base;
        machine.load(program_base, {long_branch_prefix(-1), long_branch_suffix(0x7feU)});
        machine.cpu.step();
        machine.cpu.step();
        check(
            machine.reg(15) == program_base + 4U - 0x1000U + 0xffcU,
            "les deux moitiés se composent, signe compris"
        );
    }
    {   // Le suffixe d'échange bascule en ARM et aligne la cible sur un mot.
        Machine machine;
        machine.run({long_branch_prefix(0), long_branch_exchange_suffix(4)});
        check(!machine.cpu.state().thumb(), "l'appel long d'échange bascule en ARM");
        check(machine.reg(15) == ((program_base + 4U + 8U) & ~3U), "et aligne la cible");
        check(machine.reg(14) == ((program_base + 4U) | 1U), "le retour reste marqué Thumb");
    }
    {   // La cible de l'échange est alignée sur un mot, pas sur un demi-mot :
        // c'est ce qui distingue le suffixe d'échange du suffixe ordinaire.
        Machine machine;
        machine.run({long_branch_prefix(0), long_branch_exchange_suffix(1)});
        check(machine.reg(15) == program_base + 4U, "la cible impaire est ramenée au mot");
        check(!machine.cpu.state().thumb(), "et l'état bascule en ARM");
    }
    {   // Le suffixe ordinaire, lui, n'aligne que sur le demi-mot.
        Machine machine;
        machine.run({long_branch_prefix(0), long_branch_suffix(1)});
        check(machine.reg(15) == program_base + 4U + 2U, "le suffixe ordinaire garde le demi-mot");
        check(machine.cpu.state().thumb(), "et reste en Thumb");
    }
    {   // BX vers une cible paire ramène en ARM.
        Machine machine;
        machine.reg(1) = data_base;
        machine.run({branch_exchange(1U, false)});
        check(machine.reg(15) == data_base, "BX branche");
        check(!machine.cpu.state().thumb(), "une cible paire ramène en ARM");
    }
    {
        Machine machine;
        machine.reg(1) = data_base + 1U;
        machine.run({branch_exchange(1U, false)});
        check(machine.reg(15) == data_base, "le bit d'état est écarté de l'adresse");
        check(machine.cpu.state().thumb(), "une cible impaire reste en Thumb");
    }
    {
        Machine machine;
        machine.reg(9) = data_base;
        machine.run({branch_exchange(9U, true)});
        check(machine.reg(14) == ((program_base + 2U) | 1U), "BLX retient le retour, marqué Thumb");
        check(!machine.cpu.state().thumb(), "et bascule en ARM");
    }
    {   // Aller-retour complet : Thumb appelle de l'ARM, qui revient en Thumb.
        Machine machine;
        machine.reg(1) = data_base | 0U;                    // cible ARM
        machine.load(program_base, {
            branch_exchange(1U, true),
            immediate_operation(0x0, 0U, 0x99U),            // exécutée au retour
        });
        // `BX LR` en ARM, dont le bit bas du lien ramène en Thumb.
        machine.load_arm(data_base, {0xe12f'ff1eU});
        machine.reg(15) = program_base;
        machine.cpu.step();                                  // BLX vers l'ARM
        check(!machine.cpu.state().thumb(), "le sous-programme s'exécute en ARM");
        machine.cpu.step();                                  // BX LR
        check(machine.cpu.state().thumb(), "et le retour rebascule en Thumb");
        check(machine.reg(15) == program_base + 2U, "à l'instruction suivante");
        machine.cpu.step();
        check(machine.reg(0) == 0x99U, "qui s'exécute bien");
    }
}

void les_exceptions_depuis_l_etat_thumb() {
    {   // Appel superviseur : le retour pointe deux octets plus loin, pas quatre.
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::user) | psr::thumb | psr::carry;
        machine.run({software_interrupt(0x42U)});
        check(machine.reg(15) == ArmCore::software_interrupt_vector, "SWI saute au vecteur");
        check(machine.cpu.state().mode() == CpuMode::supervisor, "et passe superviseur");
        check(machine.reg(14) == program_base + 2U, "le retour tient compte de la largeur Thumb");
        check(!machine.cpu.state().thumb(), "le gestionnaire s'exécute en ARM");
        check(
            machine.cpu.state().supervisor_spsr ==
                (static_cast<std::uint32_t>(CpuMode::user) | psr::thumb | psr::carry),
            "et l'état Thumb est sauvegardé pour le retour"
        );
    }
    {   // Retour du gestionnaire : le bit d'état revient avec le CPSR sauvegardé.
        Machine machine;
        machine.cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::user) | psr::thumb;
        machine.load(program_base, {software_interrupt(0U), immediate_operation(0x0, 0U, 0x55U)});
        // `MOVS pc, lr` en ARM.
        machine.load_arm(ArmCore::software_interrupt_vector, {0xe1b0'f00eU});
        machine.reg(15) = program_base;
        machine.cpu.step();                                  // l'appel
        machine.cpu.step();                                  // le retour
        check(machine.cpu.state().thumb(), "le retour rebascule en Thumb");
        check(machine.reg(15) == program_base + 2U, "à l'instruction qui suit l'appel");
        machine.cpu.step();
        check(machine.reg(0) == 0x55U, "qui s'exécute bien");
    }
    {   // Instruction inconnue : même règle de largeur pour l'adresse de reprise.
        Machine machine;
        // Le demi-mot suivant est délibérément non nul : ce qui doit être retenu
        // est l'instruction de seize bits, pas le mot de trente-deux qui la
        // contient.
        machine.load(program_base, {0xbe00U, 0x1234U});
        machine.reg(15) = program_base;
        machine.cpu.step();
        check(machine.reg(15) == ArmCore::undefined_vector, "le point d'arrêt saute au vecteur indéfini");
        check(machine.reg(14) == program_base + 2U, "le retour tient compte de la largeur Thumb");
        check(machine.cpu.unimplemented_count() == 1U, "et il est compté");
        check(machine.cpu.first_unimplemented() == 0xbe00U, "et retenu");
    }
    {   // Une interruption prise en Thumb passe le gestionnaire en ARM.
        Machine machine;
        machine.reg(15) = program_base;
        machine.cpu.set_irq_line(true);
        machine.cpu.step();
        check(machine.reg(15) == ArmCore::irq_vector, "l'interruption saute à son vecteur");
        check(!machine.cpu.state().thumb(), "le gestionnaire s'exécute en ARM");
        check(machine.reg(14) == program_base + 4U, "le retour se compte comme en ARM");
        check(machine.cpu.state().irq_spsr & psr::thumb, "et l'état Thumb est sauvegardé");
    }
    {   // Le retour canonique d'interruption rebascule en Thumb.
        Machine machine;
        machine.load(program_base, {immediate_operation(0x0, 1U, 7U)});
        // `SUBS pc, lr, #4` en ARM.
        machine.load_arm(ArmCore::irq_vector, {0xe25e'f004U});
        machine.reg(15) = program_base;
        machine.cpu.set_irq_line(true);
        machine.cpu.step();
        machine.cpu.set_irq_line(false);
        machine.cpu.step();
        check(machine.cpu.state().thumb(), "le retour rebascule en Thumb");
        check(machine.reg(15) == program_base, "sur l'instruction interrompue");
        machine.cpu.step();
        check(machine.reg(1) == 7U, "qui finit par s'exécuter");
    }
}

/** Un fragment de programme réaliste, pour éprouver l'ensemble d'un bloc. */
void un_petit_programme_s_execute_de_bout_en_bout() {
    // Somme des entiers de 1 à 10, écrite comme un compilateur l'écrirait en
    // Thumb : compteur décroissant, comparaison implicite par le SUB, puis
    // rangement relatif à la pile.
    Machine machine;
    machine.reg(13) = data_base;
    machine.load(program_base, {
        immediate_operation(0x0, 0U, 0U),                    // total = 0
        immediate_operation(0x0, 1U, 10U),                   // compteur = 10
        alu_operation(alu_adc, 1U, 0U),                      // remplacé plus bas
        immediate_operation(0x3, 1U, 1U),                    // compteur -= 1
        conditional_branch(0x1, -4),                         // BNE vers l'addition
        transfer_stack(false, 0U, 0U),                       // range le total
    });
    // L'addition à trois opérandes, écrite après coup pour garder la liste
    // ci-dessus lisible dans l'ordre du programme.
    machine.bus.write16(
        program_base + 4U,
        static_cast<std::uint16_t>(add_subtract(false, false, 1U, 0U, 0U))
    );
    machine.reg(15) = program_base;

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
    le_decalage_immediat_ecrit_valeur_et_retenue();
    l_addition_a_trois_operandes_pose_tous_les_indicateurs();
    les_operations_immediates_ecrivent_ce_qu_il_faut();
    les_operations_de_l_unite_arithmetique();
    les_registres_hauts_sont_atteignables();
    le_chargement_relatif_au_compteur_aligne_sa_base();
    les_transferts_couvrent_les_six_largeurs();
    le_calcul_d_adresse_n_ecrit_aucun_indicateur();
    l_empilement_et_le_depilement();
    les_transferts_par_blocs_deplacent_la_base();
    les_branchements_et_le_passage_d_un_jeu_a_l_autre();
    les_exceptions_depuis_l_etat_thumb();
    un_petit_programme_s_execute_de_bout_en_bout();
    return 0;
}
