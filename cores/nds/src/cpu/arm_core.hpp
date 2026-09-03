#pragma once

#include "cpu/bus.hpp"
#include "cpu/cp15.hpp"
#include "cpu/cpu_state.hpp"

#include <memory>

namespace ravenemu::nds {

/** Révision d'architecture, qui décide de ce que le cœur sait faire. */
enum class Architecture {
    /** ARM7TDMI : jeu ARMv4T, sans coprocesseur ni entrelacement au chargement. */
    v4t,
    /** ARM946E-S : jeu ARMv5TE, avec coprocesseur système. */
    v5te,
};

/**
 * Ce qui sert un appel logiciel à la place du programme d'amorçage.
 *
 * Sur console, `SWI` mène au vecteur d'appel superviseur, où le programme
 * d'amorçage décode le numéro et rend le service demandé. Ce programme n'est pas
 * fourni avec RavenEmu, et ne peut pas l'être : c'est du code de la console. Les
 * services sont donc rendus **hors du processeur**, par un organe qui reçoit le
 * numéro et agit sur les registres et la mémoire, puis rend la main à
 * l'instruction suivante — exactement comme le programme d'amorçage le fait en
 * revenant par `movs pc, lr`.
 *
 * Un appel que cet organe ne couvre pas n'est pas inventé : il redescend au
 * chemin du matériel, celui du vecteur, où le programme trouvera ce que la
 * mémoire contient.
 */
class SoftwareInterruptHandler {
public:
    SoftwareInterruptHandler() = default;
    SoftwareInterruptHandler(const SoftwareInterruptHandler&) = delete;
    SoftwareInterruptHandler& operator=(const SoftwareInterruptHandler&) = delete;
    SoftwareInterruptHandler(SoftwareInterruptHandler&&) = delete;
    SoftwareInterruptHandler& operator=(SoftwareInterruptHandler&&) = delete;
    virtual ~SoftwareInterruptHandler() = default;

    /** Rend faux pour un appel non couvert, que le cœur traite alors par le vecteur. */
    [[nodiscard]] virtual bool handle_software_interrupt(std::uint32_t number) = 0;
};

/**
 * Cœur ARM de la Nintendo DS, jeux d'instructions ARM et Thumb.
 *
 * La console porte deux processeurs de la même famille, et une seule
 * implémentation les sert. Ce n'est pas une économie de lignes : deux copies
 * dériveraient l'une de l'autre, et une correction apportée à l'une laisserait
 * l'autre avec l'ancienne faute. Ce qui les sépare tient dans une révision
 * d'architecture, nommée et consultée aux quelques endroits où elle compte —
 * ces endroits sont ainsi énumérables, ce qu'une duplication interdirait.
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
 * Le coprocesseur système CP15, par `MCR` et `MRC`. C'est lui qui décide où
 * répondent les mémoires locales, où se trouve la table des vecteurs, et quand
 * le processeur s'arrête pour attendre une interruption. Voir `cp15.hpp` pour
 * ce qu'il gouverne et ce qu'il laisse de côté.
 *
 * ### Ce qui ne l'est pas
 *
 * Les multiplications signées de la variante DSP (`SMLAxy` et sa famille), le
 * point d'arrêt matériel, et les autres coprocesseurs — le 946E-S n'en a pas
 * d'autre que le CP15, si bien qu'une instruction qui en désigne un est fautive
 * et non pas seulement non implémentée. Toutes sont décodées et signalées
 * plutôt que silencieusement ignorées : une instruction inconnue exécutée sans
 * bruit donne un jeu qui part à la dérive sans qu'on sache où.
 *
 * Aucune durée n'est comptée. Une instruction par `step()`, sans cache et sans
 * attente de bus : la justesse temporelle dépend en outre de la carte mémoire,
 * qui n'existe pas encore.
 *
 * ### Sur le compteur de programme
 *
 * Pendant l'exécution d'une instruction, `R15` vaut l'adresse de celle-ci plus
 * deux instructions — huit octets en ARM, quatre en Thumb — comme sur le
 * matériel où deux sont déjà engagées dans le pipeline. Cette avance n'est pas
 * une commodité de mise en œuvre : des programmes s'en servent pour calculer
 * des adresses relatives, et la retirer casserait leur arithmétique.
 */
class ArmCore {
public:
    ArmCore(Bus& bus, Architecture architecture);

    [[nodiscard]] Architecture architecture() const noexcept { return architecture_; }

    /** Remet le cœur dans l'état qui suit une mise sous tension. */
    void reset() noexcept;

    /** Exécute une instruction, en tenant compte d'une interruption en attente. */
    void step();

    [[nodiscard]] CpuState& state() noexcept { return state_; }
    [[nodiscard]] const CpuState& state() const noexcept { return state_; }

    /**
     * Coprocesseur système, ou rien du tout.
     *
     * L'ARM946E-S ne va pas sans lui : c'est par lui que passent les mémoires
     * locales, la base des vecteurs et l'attente d'interruption. L'ARM7TDMI n'en
     * a aucun, et ses instructions de coprocesseur lèvent l'exception prévue à
     * cet effet plutôt que d'être ignorées. La distinction est portée par un
     * pointeur nul plutôt que par un objet inerte : un coprocesseur qui répond
     * « rien » n'est pas la même chose qu'un coprocesseur absent.
     */
    [[nodiscard]] Cp15* coprocessor() noexcept { return coprocessor_.get(); }
    [[nodiscard]] const Cp15* coprocessor() const noexcept { return coprocessor_.get(); }

    /** Niveau de la ligne d'interruption, échantillonné entre deux instructions. */
    void set_irq_line(bool asserted) noexcept { irq_line_ = asserted; }
    void set_fiq_line(bool asserted) noexcept { fiq_line_ = asserted; }

    /**
     * Arrête le processeur jusqu'à ce qu'on le réveille.
     *
     * Les deux processeurs de la console s'arrêtent, et pas par le même chemin :
     * le principal par une opération de son coprocesseur, le secondaire par un
     * registre d'entrée-sortie. L'état d'arrêt est donc porté ici, où il est
     * commun, plutôt que dans un coprocesseur que l'un des deux n'a pas.
     *
     * **Ce qui réveille n'est pas décidé ici.** Le cœur ne voit qu'une ligne
     * d'interruption déjà filtrée par l'autorisation générale, et cette
     * autorisation ne conditionne pas la reprise : un programme qui la coupe
     * avant de s'arrêter doit repartir tout de même. Seul l'organe qui tient le
     * contrôleur d'interruptions sait cela, et c'est lui qui appelle `wake`.
     */
    /**
     * Installe l'organe qui sert les appels logiciels, ou en retire un.
     *
     * Sans organe, `SWI` prend le chemin du matériel : le vecteur d'appel
     * superviseur. C'est ce que fait un cœur monté seul, pour les vérifications
     * qui portent sur le processeur et non sur la console.
     */
    void set_software_interrupt_handler(SoftwareInterruptHandler* handler) noexcept {
        software_interrupts_ = handler;
    }

    /**
     * Fait reprendre l'exécution à [address], comme le ferait un branchement.
     *
     * Le compteur de programme ne suffit pas à lui seul : sans marquer le
     * branchement, le cœur le remplacerait par l'adresse suivante en fin de pas,
     * et le saut n'aurait pas lieu.
     */
    void branch_to(std::uint32_t address) noexcept {
        state_.registers[15] = address;
        branched_ = true;
    }

    void halt() noexcept { halted_ = true; }
    void wake() noexcept { halted_ = false; }
    [[nodiscard]] bool halted() const noexcept { return halted_; }

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
    void execute_coprocessor(std::uint32_t opcode);

    /**
     * Écrit le compteur de programme depuis une valeur chargée en mémoire.
     *
     * C'est ici que les deux architectures divergent le plus visiblement :
     * ARMv5 y lit le bit bas comme un changement d'état, ARMv4T l'ignore et
     * reste où elle est. Un jeu écrit pour l'une part à la dérive sur l'autre.
     */
    void write_loaded_pc(std::uint32_t value) noexcept;
    /** Même règle, pour une adresse dépilée depuis l'état Thumb. */
    void write_popped_pc(std::uint32_t value) noexcept;

    [[nodiscard]] bool has_v5_extensions() const noexcept {
        return architecture_ == Architecture::v5te;
    }

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

    // Toute la mémoire passe par ici : les mémoires locales sont dans le cœur,
    // pas sur le bus, et doivent donc répondre avant lui. Aucun chemin
    // d'exécution ne doit s'adresser au bus directement.
    [[nodiscard]] std::uint32_t fetch32(std::uint32_t address);
    [[nodiscard]] std::uint32_t fetch16(std::uint32_t address);
    [[nodiscard]] std::uint32_t load32(std::uint32_t address);
    [[nodiscard]] std::uint32_t load16(std::uint32_t address);
    [[nodiscard]] std::uint32_t load8(std::uint32_t address);
    void store32(std::uint32_t address, std::uint32_t value);
    void store16(std::uint32_t address, std::uint32_t value);
    void store8(std::uint32_t address, std::uint32_t value);

    void write_register(std::uint32_t index, std::uint32_t value) noexcept;
    [[nodiscard]] std::uint32_t read_register(std::uint32_t index) const noexcept {
        return state_.registers[index];
    }

    void enter_exception(CpuMode mode, std::uint32_t vector, std::uint32_t return_address, bool mask_fiq) noexcept;

    /**
     * Prend un appel logiciel, servi hors du processeur si quelqu'un s'en charge.
     *
     * @param number numéro de l'appel, tel que l'instruction le porte
     * @param width  largeur de l'instruction, qui donne l'adresse de reprise
     */
    void take_software_interrupt(std::uint32_t number, std::uint32_t width);
    void raise_undefined(std::uint32_t opcode);
    void restore_cpsr_from_spsr() noexcept;

    void set_logical_flags(std::uint32_t result, bool carry) noexcept;
    void set_arithmetic_flags(std::uint32_t result, std::uint32_t left, std::uint32_t right, bool carry, bool subtract) noexcept;

    Bus& bus_;
    Architecture architecture_;
    std::unique_ptr<Cp15> coprocessor_;
    CpuState state_{};
    bool branched_{};
    bool halted_{};

    /** Qui sert les appels logiciels, ou rien : le vecteur s'en charge alors. */
    SoftwareInterruptHandler* software_interrupts_{};
    bool irq_line_{};
    bool fiq_line_{};
    std::uint32_t unimplemented_{};
    std::uint32_t first_unimplemented_{};
};

/** Processeur principal : ARM946E-S, jeu ARMv5TE, coprocesseur système. */
class Arm9 final : public ArmCore {
public:
    explicit Arm9(Bus& bus) : ArmCore(bus, Architecture::v5te) {}

    /** Le coprocesseur existe toujours sur ce processeur. */
    [[nodiscard]] Cp15& cp15() noexcept { return *coprocessor(); }
    [[nodiscard]] const Cp15& cp15() const noexcept { return *coprocessor(); }
};

/**
 * Processeur secondaire : ARM7TDMI, jeu ARMv4T.
 *
 * Il tient l'amorçage de la console, le son, l'écran tactile et la liaison sans
 * fil. Son jeu d'instructions est plus étroit que celui du processeur
 * principal — ni `BLX`, ni `CLZ`, ni arithmétique saturante, ni doubles mots, ni
 * coprocesseur — et il ne change pas d'état sur un chargement du compteur de
 * programme. Ces absences ne sont pas des lacunes de l'émulateur : ce sont
 * celles du matériel, et les instructions correspondantes lèvent l'exception
 * d'instruction indéfinie comme sur console.
 */
class Arm7 final : public ArmCore {
public:
    explicit Arm7(Bus& bus) : ArmCore(bus, Architecture::v4t) {}
};

} // namespace ravenemu::nds
