#pragma once

#include <array>
#include <cstdint>

namespace ravenemu::nds {

/** Modes d'exécution, tels que codés dans les cinq bits bas du CPSR. */
enum class CpuMode : std::uint32_t {
    user = 0x10,
    fiq = 0x11,
    irq = 0x12,
    supervisor = 0x13,
    abort = 0x17,
    undefined = 0x1b,
    system = 0x1f,
};

/** Registre d'état : indicateurs, masques et mode. */
namespace psr {
inline constexpr std::uint32_t negative = 1U << 31U;
inline constexpr std::uint32_t zero = 1U << 30U;
inline constexpr std::uint32_t carry = 1U << 29U;
inline constexpr std::uint32_t overflow = 1U << 28U;
/** Saturation collante, posée par l'arithmétique saturante et jamais effacée par elle. */
inline constexpr std::uint32_t saturation = 1U << 27U;
inline constexpr std::uint32_t irq_disable = 1U << 7U;
inline constexpr std::uint32_t fiq_disable = 1U << 6U;
inline constexpr std::uint32_t thumb = 1U << 5U;
inline constexpr std::uint32_t mode_mask = 0x1fU;
} // namespace psr

/**
 * Banc de registres d'un cœur ARM.
 *
 * Les registres bancarisés ne sont pas rangés dans un tableau par mode : le
 * mode courant possède toujours `registers`, et le changement de mode échange
 * son contenu avec la banque quittée. Lire un registre reste ainsi un accès
 * direct, sans indirection par le mode, ce qui compte sur le chemin
 * d'exécution ; le coût est déplacé sur le changement de mode, bien plus rare.
 */
struct CpuState {
    /** R0 à R15. R15 est le compteur de programme. */
    std::array<std::uint32_t, 16> registers{};
    std::uint32_t cpsr{static_cast<std::uint32_t>(CpuMode::supervisor) | psr::irq_disable | psr::fiq_disable};

    /** R8 à R12 de la banque FIQ, et ceux de la banque partagée par les autres modes. */
    std::array<std::uint32_t, 5> fiq_r8_r12{};
    std::array<std::uint32_t, 5> user_r8_r12{};

    /** R13 et R14 par mode bancarisé, plus la paire partagée utilisateur et système. */
    std::array<std::uint32_t, 2> user_r13_r14{};
    std::array<std::uint32_t, 2> fiq_r13_r14{};
    std::array<std::uint32_t, 2> irq_r13_r14{};
    std::array<std::uint32_t, 2> supervisor_r13_r14{};
    std::array<std::uint32_t, 2> abort_r13_r14{};
    std::array<std::uint32_t, 2> undefined_r13_r14{};

    /** Sauvegardes du CPSR. Les modes utilisateur et système n'en ont pas. */
    std::uint32_t fiq_spsr{};
    std::uint32_t irq_spsr{};
    std::uint32_t supervisor_spsr{};
    std::uint32_t abort_spsr{};
    std::uint32_t undefined_spsr{};

    [[nodiscard]] CpuMode mode() const noexcept {
        return static_cast<CpuMode>(cpsr & psr::mode_mask);
    }

    [[nodiscard]] bool thumb() const noexcept { return (cpsr & psr::thumb) != 0U; }

    [[nodiscard]] bool flag(std::uint32_t bit) const noexcept { return (cpsr & bit) != 0U; }

    void set_flag(std::uint32_t bit, bool value) noexcept {
        cpsr = value ? (cpsr | bit) : (cpsr & ~bit);
    }

    /** Vrai si le mode courant possède un SPSR. */
    [[nodiscard]] static bool has_spsr(CpuMode value) noexcept {
        return value != CpuMode::user && value != CpuMode::system;
    }

    /**
     * SPSR du mode donné.
     *
     * Les modes utilisateur et système n'en ont pas : ils reçoivent un
     * emplacement de rebut, si bien qu'une écriture y est absorbée sans effet
     * plutôt que de demander une garde à chaque appelant. Une lecture qui doit
     * retomber sur le CPSR reste, elle, à la charge de l'appelant, car le repli
     * y est visible.
     */
    [[nodiscard]] std::uint32_t& spsr_for(CpuMode value) noexcept {
        switch (value) {
        case CpuMode::fiq: return fiq_spsr;
        case CpuMode::irq: return irq_spsr;
        case CpuMode::supervisor: return supervisor_spsr;
        case CpuMode::abort: return abort_spsr;
        case CpuMode::undefined: return undefined_spsr;
        default: return discarded_spsr_;
        }
    }

    /**
     * Bascule vers [target], en rangeant la banque courante et en sortant celle
     * du mode visé.
     *
     * Un mode inconnu est traité comme le mode système : la console ne peut pas
     * s'arrêter parce qu'un jeu a écrit une valeur invalide dans le CPSR, et le
     * mode système partage la banque utilisateur, ce qui est le repli le moins
     * surprenant.
     */
    void switch_mode(CpuMode target) noexcept {
        const auto current = mode();
        if (current == target) return;
        store_bank(current);
        load_bank(target);
        cpsr = (cpsr & ~psr::mode_mask) | static_cast<std::uint32_t>(target);
    }

private:
    [[nodiscard]] std::array<std::uint32_t, 2>& bank_r13_r14(CpuMode value) noexcept {
        switch (value) {
        case CpuMode::fiq: return fiq_r13_r14;
        case CpuMode::irq: return irq_r13_r14;
        case CpuMode::supervisor: return supervisor_r13_r14;
        case CpuMode::abort: return abort_r13_r14;
        case CpuMode::undefined: return undefined_r13_r14;
        default: return user_r13_r14;
        }
    }

    void store_bank(CpuMode value) noexcept {
        auto& pair = bank_r13_r14(value);
        pair[0] = registers[13];
        pair[1] = registers[14];
        auto& high = value == CpuMode::fiq ? fiq_r8_r12 : user_r8_r12;
        for (std::size_t index = 0; index < high.size(); ++index) high[index] = registers[8 + index];
    }

    void load_bank(CpuMode value) noexcept {
        const auto& pair = bank_r13_r14(value);
        registers[13] = pair[0];
        registers[14] = pair[1];
        const auto& high = value == CpuMode::fiq ? fiq_r8_r12 : user_r8_r12;
        for (std::size_t index = 0; index < high.size(); ++index) registers[8 + index] = high[index];
    }

    std::uint32_t discarded_spsr_{};
};

} // namespace ravenemu::nds
