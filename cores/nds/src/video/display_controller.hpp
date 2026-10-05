#pragma once

#include "system/interrupt_controller.hpp"
#include "system/inter_processor.hpp"
#include "video/engine2d.hpp"

#include <array>
#include <cstdint>
#include <span>

namespace ravenemu::nds {

/**
 * Contrôleur d'affichage : le balayage de l'écran, et ce qu'il déclenche.
 *
 * ### Pourquoi il compte
 *
 * Les deux moteurs savent dessiner une ligne, mais rien ne leur disait laquelle,
 * ni quand. C'est ce compteur qui donne son rythme à toute la console : un jeu
 * n'attend pas le temps qui passe, il attend le retour du balayage. Sans lui, un
 * moteur graphique est une fonction que personne n'appelle.
 *
 * ### Un seul compteur, deux registres d'état
 *
 * Le compteur de lignes est unique et partagé : c'est le même faisceau. Mais
 * chaque processeur a son propre registre d'état, avec ses propres autorisations
 * d'interruption — l'un peut demander à être réveillé au retour vertical sans
 * que l'autre le soit. Les trois indicateurs, eux, se lisent pareil des deux
 * côtés puisqu'ils décrivent le même balayage.
 *
 * ### Le nombre de lignes n'est pas celui de l'écran
 *
 * L'écran fait 192 lignes, le balayage en compte 263. Les 71 lignes de
 * différence ne s'affichent pas : c'est pendant elles qu'un jeu prépare la trame
 * suivante, et c'est pour cela que l'interruption de retour vertical est la plus
 * utilisée de la console. **La dernière ligne n'est pas comptée comme retour
 * vertical**, ce qui surprend et compte : un logiciel qui scrute cet indicateur
 * y voit le balayage repartir une ligne avant la fin.
 *
 * ### Ce qui est approché, et dit
 *
 * Le balayage avance ici ligne par ligne, non point par point. Le retour
 * horizontal est donc posé une fois par ligne, à la fin de sa partie visible,
 * plutôt qu'à un instant précis dans la ligne. C'est suffisant pour tout ce qui
 * s'accroche au retour vertical ou à une ligne donnée ; ce ne le serait pas pour
 * un effet qui change un registre au milieu d'une ligne, et ce genre d'effet
 * demandera un modèle de durée que rien ne consomme encore.
 */
class DisplayController {
public:
    DisplayController(
        Engine2d& main,
        Engine2d& secondary,
        InterruptController& main_interrupts,
        InterruptController& secondary_interrupts
    ) noexcept;

    /** Lignes réellement affichées. */
    static constexpr std::uint32_t visible_lines = 192;
    /** Lignes balayées, affichées ou non. */
    static constexpr std::uint32_t total_lines = 263;
    /** Points balayés par ligne, visibles ou non. */
    static constexpr std::uint32_t dots_per_line = 355;
    /** Points réellement affichés par ligne. */
    static constexpr std::uint32_t visible_dots = 256;
    /** Cycles d'horloge maître par point. */
    static constexpr std::uint32_t cycles_per_dot = 6;

    // Bits du registre d'état du balayage.
    static constexpr std::uint16_t vertical_blank_flag = 1U << 0U;
    static constexpr std::uint16_t horizontal_blank_flag = 1U << 1U;
    static constexpr std::uint16_t line_match_flag = 1U << 2U;
    static constexpr std::uint16_t vertical_blank_interrupt = 1U << 3U;
    static constexpr std::uint16_t horizontal_blank_interrupt = 1U << 4U;
    static constexpr std::uint16_t line_match_interrupt = 1U << 5U;
    /** Neuvième bit de la ligne guettée, logé à part des huit autres. */
    static constexpr std::uint16_t line_match_high_bit = 1U << 7U;

    void reset() noexcept;

    /** Ligne balayée, celle que lit le compteur de lignes. */
    [[nodiscard]] std::uint32_t line() const noexcept { return line_; }

    [[nodiscard]] std::uint16_t status(Processor side) const noexcept;
    void set_status(Processor side, std::uint16_t value) noexcept;

    /** Ligne que ce processeur guette, sur neuf bits. */
    [[nodiscard]] std::uint32_t watched_line(Processor side) const noexcept;

    /**
     * Termine la ligne courante et passe à la suivante.
     *
     * L'ordre compte : le retour horizontal appartient à la ligne qui s'achève,
     * le retour vertical et la ligne guettée à celle qui commence.
     */
    void advance_line() noexcept;

    /**
     * Dessine la ligne que le faisceau balaie, si elle est visible.
     *
     * C'est la forme qui compte dès qu'un processeur tourne : une ligne se
     * dessine avec les registres en vigueur au moment où le faisceau la
     * traverse, et un programme qui change un décor en cours de trame n'agit que
     * sur les lignes qui suivent. Dessiner la trame entière à la fin
     * effacerait cette distinction sans rien dire.
     */
    void render_current_line(std::span<std::int32_t> framebuffer) noexcept;

    /**
     * Dessine une trame entière, registres figés.
     *
     * Cette forme ne décrit une trame juste que si rien ne bouge pendant
     * qu'elle se dessine. Elle reste parce qu'elle dit exactement cela : ce que
     * montrerait l'écran si les registres restaient tels quels.
     */
    void render_frame(std::span<std::int32_t> framebuffer) noexcept;

    /**
     * Échange les deux écrans.
     *
     * Le matériel le décide par un bit du registre d'alimentation, et non par
     * une convention : sans lui, savoir quel moteur alimente l'écran du haut
     * serait un choix arbitraire du code.
     */
    void set_swapped(bool swapped) noexcept { swapped_ = swapped; }
    [[nodiscard]] bool swapped() const noexcept { return swapped_; }

private:
    /** Ce qu'un processeur possède en propre du registre d'état. */
    struct Side {
        /** Autorisations et ligne guettée ; les indicateurs n'en sont pas. */
        std::uint16_t written{};
    };

    [[nodiscard]] Side& side_of(Processor side) noexcept;
    [[nodiscard]] const Side& side_of(Processor side) const noexcept;
    [[nodiscard]] InterruptController& interrupts_of(Processor side) noexcept;

    void raise_for_both(std::uint16_t enable, std::uint32_t source) noexcept;

    /**
     * Dessine une ligne visible dans les deux écrans.
     *
     * La ligne est supposée visible : les deux appelants s'en assurent, et un
     * refus ici doublerait un contrôle déjà fait.
     */
    void render_row_into(std::uint32_t row, std::span<std::int32_t> framebuffer) noexcept;

    Engine2d& main_engine_;
    Engine2d& secondary_engine_;
    InterruptController& main_interrupts_;
    InterruptController& secondary_interrupts_;

    std::uint32_t line_{};
    bool swapped_{};
    Side main_{};
    Side secondary_{};
};

} // namespace ravenemu::nds
