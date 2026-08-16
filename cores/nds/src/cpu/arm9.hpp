#pragma once

#include "cpu/bus.hpp"
#include "cpu/cpu_state.hpp"

namespace ravenemu::nds {

/**
 * Cœur ARM946E-S de la Nintendo DS, jeux d'instructions ARM et Thumb.
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
 * L'intégralité du jeu Thumb 16 bits qui l'accompagne, et le passage d'un jeu à
 * l'autre dans les deux sens — par `BX`, par `BLX`, par un retour d'exception,
 * par une adresse dépilée. Un jeu de la console alterne sans cesse entre les
 * deux : le code compact est en Thumb, les gestionnaires d'interruption et le
 * code sensible en ARM.
 *
 * ### Ce qui ne l'est pas
 *
 * Les multiplications signées de la variante DSP (`SMLAxy` et sa famille), le
 * point d'arrêt matériel et les instructions de coprocesseur, qui supposent le
 * CP15 et sa gestion des caches, des mémoires locales et des régions de
 * protection. Elles sont décodées et signalées comme non implémentées plutôt
 * que silencieusement ignorées : une instruction inconnue exécutée sans bruit
 * donne un jeu qui part à la dérive sans qu'on sache où.
 *
 * Aucune durée n'est comptée non plus. Une instruction par `step()`, sans
 * cache, sans mémoire locale et sans attente de bus : la justesse temporelle
 * dépend du CP15 et de la carte mémoire, qui n'existent pas encore.
 *
 * ### Sur le compteur de programme
 *
 * Pendant l'exécution d'une instruction, `R15` vaut l'adresse de celle-ci plus
 * deux instructions — huit octets en ARM, quatre en Thumb — comme sur le
 * matériel où deux sont déjà engagées dans le pipeline. Cette avance n'est pas
 * une commodité de mise en œuvre : des programmes s'en servent pour calculer
 * des adresses relatives, et la retirer casserait leur arithmétique.
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

    void execute_thumb(std::uint32_t opcode);
    void thumb_shift_immediate(std::uint32_t opcode);
    void thumb_add_subtract(std::uint32_t opcode);
    void thumb_immediate_operation(std::uint32_t opcode);
    void thumb_alu_operation(std::uint32_t opcode);
    void thumb_high_register(std::uint32_t opcode);
    void thumb_load_pc_relative(std::uint32_t opcode);
    void thumb_transfer_register_offset(std::uint32_t opcode);
    void thumb_transfer_immediate_offset(std::uint32_t opcode);
    void thumb_transfer_halfword(std::uint32_t opcode);
    void thumb_transfer_stack(std::uint32_t opcode);
    void thumb_load_address(std::uint32_t opcode);
    void thumb_adjust_stack(std::uint32_t opcode);
    void thumb_push_pop(std::uint32_t opcode);
    void thumb_block_transfer(std::uint32_t opcode);
    void thumb_conditional_branch(std::uint32_t opcode);
    void thumb_branch(std::uint32_t opcode);
    void thumb_long_branch(std::uint32_t opcode);

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
