#pragma once

#include "cpu/bus.hpp"
#include "cpu/cpu_state.hpp"

namespace ravenemu::nds {

/**
 * Cœur ARM946E-S de la Nintendo DS, jeu d'instructions ARM.
 *
 * ### Ce qui est couvert
 *
 * L'intégralité du jeu d'instructions ARM 32 bits d'ARMv5TE : traitement de
 * données avec toutes les formes du décaleur, multiplications courtes et
 * longues, transferts simples, demi-mots, octets signés et doubles mots,
 * transferts par blocs, branchements, échange atomique, transferts de registre
 * d'état, appel superviseur, et les ajouts d'ARMv5TE que sont `CLZ`, `BLX` sous
 * ses deux formes et l'arithmétique saturante.
 *
 * ### Ce qui ne l'est pas
 *
 * Le jeu Thumb, les multiplications signées de la variante DSP (`SMLAxy` et sa
 * famille) et les instructions de coprocesseur, qui supposent le CP15 et sa
 * gestion des caches, des mémoires locales et des régions de protection. Elles
 * sont décodées et signalées comme non implémentées plutôt que silencieusement
 * ignorées : une instruction inconnue exécutée sans bruit donne un jeu qui part
 * à la dérive sans qu'on sache où.
 *
 * ### Sur le compteur de programme
 *
 * Pendant l'exécution d'une instruction, `R15` vaut l'adresse de celle-ci plus
 * huit, comme sur le matériel où deux instructions sont déjà engagées dans le
 * pipeline. Cette avance n'est pas une commodité de mise en œuvre : des
 * programmes s'en servent pour calculer des adresses relatives, et la retirer
 * casserait leur arithmétique.
 */
class Arm9 {
public:
    explicit Arm9(Bus& bus) noexcept : bus_(bus) {}

    /** Remet le cœur dans l'état qui suit une mise sous tension. */
    void reset() noexcept;

    /** Exécute une instruction, en tenant compte d'une interruption en attente. */
    void step();

    [[nodiscard]] CpuState& state() noexcept { return state_; }
    [[nodiscard]] const CpuState& state() const noexcept { return state_; }

    /** Niveau de la ligne d'interruption, échantillonné entre deux instructions. */
    void set_irq_line(bool asserted) noexcept { irq_line_ = asserted; }
    void set_fiq_line(bool asserted) noexcept { fiq_line_ = asserted; }

    /** Nombre d'instructions non implémentées rencontrées depuis la remise à zéro. */
    [[nodiscard]] std::uint32_t unimplemented_count() const noexcept { return unimplemented_; }
    /** Première instruction non implémentée rencontrée, ou zéro. */
    [[nodiscard]] std::uint32_t first_unimplemented() const noexcept { return first_unimplemented_; }

    /** Vecteurs d'exception, à la base basse. */
    static constexpr std::uint32_t reset_vector = 0x0000'0000;
    static constexpr std::uint32_t undefined_vector = 0x0000'0004;
    static constexpr std::uint32_t software_interrupt_vector = 0x0000'0008;
    static constexpr std::uint32_t irq_vector = 0x0000'0018;
    static constexpr std::uint32_t fiq_vector = 0x0000'001c;

private:
    struct ShifterResult {
        std::uint32_t value;
        bool carry;
    };

    [[nodiscard]] bool condition_met(std::uint32_t opcode) const noexcept;
    [[nodiscard]] ShifterResult shift_operand(std::uint32_t opcode, bool register_form);
    [[nodiscard]] static ShifterResult apply_shift(
        std::uint32_t value,
        std::uint32_t type,
        std::uint32_t amount,
        bool immediate_form,
        bool carry_in
    ) noexcept;

    void execute(std::uint32_t opcode);
    void execute_data_processing(std::uint32_t opcode);
    void execute_multiply(std::uint32_t opcode);
    void execute_multiply_long(std::uint32_t opcode);
    void execute_swap(std::uint32_t opcode);
    void execute_halfword_transfer(std::uint32_t opcode);
    void execute_single_transfer(std::uint32_t opcode);
    void execute_block_transfer(std::uint32_t opcode);
    void execute_branch(std::uint32_t opcode);
    void execute_psr_transfer(std::uint32_t opcode);
    void execute_saturating(std::uint32_t opcode);
    void execute_clz(std::uint32_t opcode);
    void execute_branch_exchange(std::uint32_t opcode, bool link);

    void write_register(std::uint32_t index, std::uint32_t value) noexcept;
    [[nodiscard]] std::uint32_t read_register(std::uint32_t index) const noexcept {
        return state_.registers[index];
    }

    void enter_exception(CpuMode mode, std::uint32_t vector, std::uint32_t return_address, bool mask_fiq) noexcept;
    void raise_undefined(std::uint32_t opcode);
    void restore_cpsr_from_spsr() noexcept;

    void set_logical_flags(std::uint32_t result, bool carry) noexcept;
    void set_arithmetic_flags(std::uint32_t result, std::uint32_t left, std::uint32_t right, bool carry, bool subtract) noexcept;

    Bus& bus_;
    CpuState state_{};
    bool branched_{};
    bool irq_line_{};
    bool fiq_line_{};
    std::uint32_t unimplemented_{};
    std::uint32_t first_unimplemented_{};
};

} // namespace ravenemu::nds
