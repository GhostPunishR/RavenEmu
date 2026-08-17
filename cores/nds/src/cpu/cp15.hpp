#pragma once

#include <cstdint>
#include <vector>

namespace ravenemu::nds {

/**
 * Coprocesseur système CP15 de l'ARM946E-S.
 *
 * ### Ce qu'il gouverne
 *
 * Trois choses y sont observables depuis le processeur, et ce sont elles qui
 * font que ce lot n'est pas un simple banc de registres :
 *
 * - **Les mémoires locales.** L'ITCM et la DTCM ne sont pas sur le bus : elles
 *   sont dans le cœur, et répondent avant lui. Où elles répondent et sur quelle
 *   étendue se décide ici. Sans elles, la carte mémoire de la console ne peut
 *   pas être juste, parce que les mêmes adresses désignent autre chose selon
 *   qu'une mémoire locale les couvre ou non.
 * - **La base des vecteurs d'exception.** Basse à la mise sous tension, haute
 *   dès que le logiciel le demande. L'ARM9 de la console démarre en position
 *   basse et bascule ; un gestionnaire cherché au mauvais endroit exécute des
 *   octets au hasard.
 * - **L'attente d'interruption.** Elle arrête le processeur jusqu'à ce qu'une
 *   ligne se lève. Sans elle, une boucle d'inactivité tourne indéfiniment.
 *
 * ### Ce qu'il ne gouverne pas
 *
 * Les caches ne sont pas modélisés : le cœur n'a ni cache d'instructions ni
 * cache de données, donc les opérations qui les vident, les nettoient ou les
 * verrouillent sont acceptées sans effet. Les accepter plutôt que les refuser
 * est délibéré — un logiciel qui vide un cache absent ne fait rien de fautif.
 *
 * L'unité de protection est tenue mais pas appliquée. Ses registres sont écrits
 * et relus fidèlement, parce que le logiciel les relit ; en revanche aucun accès
 * n'est refusé, faute d'un chemin d'exception d'abandon et d'une carte mémoire
 * où l'abandon aurait un sens. Le jour où l'un existera, l'application viendra
 * avec.
 *
 * L'indicateur de grand-boutisme est rangé mais pas suivi : le cœur est
 * petit-boutiste, comme la console.
 *
 * ### Sur les registres inconnus
 *
 * Une lecture d'un registre non modélisé rend zéro, une écriture est absorbée,
 * et les deux sont comptées. Refuser l'accès ferait tomber un logiciel sur un
 * registre de mise au point que personne n'émule ; l'absorber en silence
 * laisserait ignorer ce qu'il a touché. Le compte tranche entre les deux.
 */
class Cp15 {
public:
    Cp15();

    // Registres d'identification, tels que les publie l'ARM9 de la console.
    /** Identifiant principal : ARM Ltd, architecture v5TE, pièce 946, révision 1. */
    static constexpr std::uint32_t main_id = 0x4105'9461;
    static constexpr std::uint32_t cache_type = 0x0f0d'2112;
    /** Tailles des mémoires locales, codées comme `512 << n`. */
    static constexpr std::uint32_t tcm_size = 0x0014'0180;

    static constexpr std::uint32_t itcm_bytes = 32U * 1024U;
    static constexpr std::uint32_t dtcm_bytes = 16U * 1024U;

    // Bits du registre de contrôle.
    static constexpr std::uint32_t protection_enable = 1U << 0U;
    static constexpr std::uint32_t data_cache_enable = 1U << 2U;
    static constexpr std::uint32_t big_endian = 1U << 7U;
    static constexpr std::uint32_t instruction_cache_enable = 1U << 12U;
    static constexpr std::uint32_t high_vectors = 1U << 13U;
    static constexpr std::uint32_t round_robin = 1U << 14U;
    static constexpr std::uint32_t legacy_thumb = 1U << 15U;
    static constexpr std::uint32_t dtcm_enable = 1U << 16U;
    static constexpr std::uint32_t dtcm_load_mode = 1U << 17U;
    static constexpr std::uint32_t itcm_enable = 1U << 18U;
    static constexpr std::uint32_t itcm_load_mode = 1U << 19U;

    /** Bits câblés à un : ils se lisent posés et ne s'effacent pas. */
    static constexpr std::uint32_t control_read_as_one = 0x0000'0078;
    /** Seuls ces bits sont modifiables ; le reste du registre est figé. */
    static constexpr std::uint32_t control_writable = 0x000f'f085;

    /** Base haute des vecteurs, quand le logiciel la demande. */
    static constexpr std::uint32_t high_vector_base = 0xffff'0000;

    void reset() noexcept;

    /**
     * Lit un registre. Les quatre champs sont ceux de `MRC`, dans l'ordre du
     * manuel : opération primaire, registre, registre secondaire, opération
     * secondaire.
     */
    [[nodiscard]] std::uint32_t read(
        std::uint32_t operation,
        std::uint32_t primary,
        std::uint32_t secondary,
        std::uint32_t sub_operation
    ) noexcept;

    void write(
        std::uint32_t operation,
        std::uint32_t primary,
        std::uint32_t secondary,
        std::uint32_t sub_operation,
        std::uint32_t value
    ) noexcept;

    [[nodiscard]] std::uint32_t control() const noexcept { return control_; }

    /** Adresse où commence la table des vecteurs d'exception. */
    [[nodiscard]] std::uint32_t exception_base() const noexcept {
        return (control_ & high_vectors) != 0U ? high_vector_base : 0U;
    }

    /** Vrai tant que le processeur attend une interruption. */
    [[nodiscard]] bool halted() const noexcept { return halted_; }
    void wake() noexcept { halted_ = false; }

    // Accès aux mémoires locales. Chacun rend vrai si une mémoire a répondu,
    // auquel cas le bus ne doit pas être sollicité.

    /** Lecture d'instruction : seule l'ITCM répond. */
    [[nodiscard]] bool fetch(std::uint32_t address, std::uint32_t width, std::uint32_t& value) const noexcept;
    /** Lecture de donnée : la DTCM d'abord, l'ITCM ensuite. */
    [[nodiscard]] bool load(std::uint32_t address, std::uint32_t width, std::uint32_t& value) const noexcept;
    [[nodiscard]] bool store(std::uint32_t address, std::uint32_t width, std::uint32_t value) noexcept;

    /** Nombre d'accès à un registre non modélisé depuis la remise à zéro. */
    [[nodiscard]] std::uint32_t unknown_access_count() const noexcept { return unknown_accesses_; }
    /** Premier registre non modélisé touché, codé `(op, CRn, CRm, op2)`. */
    [[nodiscard]] std::uint32_t first_unknown_access() const noexcept { return first_unknown_; }

    /** Fenêtre d'adresses couverte par une mémoire locale. */
    struct Window {
        std::uint32_t base;
        std::uint64_t size;

        [[nodiscard]] bool contains(std::uint32_t address) const noexcept {
            return address >= base && static_cast<std::uint64_t>(address - base) < size;
        }
    };

    [[nodiscard]] Window itcm_window() const noexcept;
    [[nodiscard]] Window dtcm_window() const noexcept;

private:
    [[nodiscard]] bool itcm_covers(std::uint32_t address) const noexcept;
    [[nodiscard]] bool dtcm_covers(std::uint32_t address) const noexcept;

    void note_unknown(
        std::uint32_t operation,
        std::uint32_t primary,
        std::uint32_t secondary,
        std::uint32_t sub_operation
    ) noexcept;

    std::uint32_t control_{control_read_as_one};

    /** Un bit par région : mémorisable, tamponnable. */
    std::uint32_t data_cachable_{};
    std::uint32_t instruction_cachable_{};
    std::uint32_t bufferable_{};

    /** Quatre bits par région, forme étendue ; la forme courte en est une vue. */
    std::uint32_t data_permissions_{};
    std::uint32_t instruction_permissions_{};

    /** Huit régions de protection, unifiées entre données et instructions. */
    std::uint32_t regions_[8]{};

    std::uint32_t data_lockdown_{};
    std::uint32_t instruction_lockdown_{};

    std::uint32_t dtcm_region_{};
    std::uint32_t itcm_region_{};

    bool halted_{};

    std::uint32_t unknown_accesses_{};
    std::uint32_t first_unknown_{};

    std::vector<std::uint8_t> itcm_;
    std::vector<std::uint8_t> dtcm_;
};

} // namespace ravenemu::nds
