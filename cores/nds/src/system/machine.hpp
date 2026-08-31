#pragma once

#include "cpu/arm_core.hpp"
#include "memory/arm7_memory_map.hpp"
#include "memory/arm9_memory_map.hpp"
#include "memory/system_memory.hpp"
#include "system/inter_processor.hpp"
#include "system/interrupt_controller.hpp"
#include "video/video_system.hpp"

#include <cstdint>
#include <span>

namespace ravenemu::nds {

/**
 * La console assemblée, et ce qui la fait tourner.
 *
 * Aucun organe nouveau n'apparaît ici. Tout existait déjà — deux processeurs,
 * deux cartes mémoire, la mémoire partagée, les files entre processeurs, les
 * deux moteurs graphiques, le balayage — mais chacun attendait qu'on l'appelle,
 * et rien ne les faisait avancer ensemble. C'est ce que cet organe fait, et
 * c'est tout ce qu'il fait.
 *
 * ### Pourquoi l'entrelacement compte
 *
 * Faire tourner un processeur pendant toute une trame, puis l'autre, donnerait
 * exactement les mêmes registres à la fin et une console qui ne marche pas : les
 * deux se parlent en cours de route. Un processeur qui dépose un mot dans une
 * file et attend la réponse attendrait une trame entière, et un programme qui
 * scrute une case que l'autre écrit ne verrait jamais l'écriture arriver. Les
 * deux avancent donc par petits pas alternés, au plus fin que ce cœur sache
 * faire : une instruction.
 *
 * **Le rapport entre les deux est celui du matériel.** Le processeur principal
 * bat deux fois plus vite que le secondaire, et cela s'observe : un compteur
 * incrémenté des deux côtés pendant la même ligne monte deux fois plus haut d'un
 * côté que de l'autre. Ce rapport est réel ; le nombre d'instructions par ligne
 * ne l'est pas, et la section suivante dit ce qu'il vaut.
 *
 * ### Ce qui est convenu, et pourquoi c'est dit
 *
 * Aucune instruction de ce cœur ne dure. Rien ne compte les cycles, ni les
 * attentes de bus, ni les caches. Le budget accordé à chaque processeur pour une
 * ligne repose donc sur une **convention explicite : une instruction par cycle
 * de l'horloge maître**. Les 2130 cycles d'une ligne sont, eux, ceux du
 * matériel — 355 points à six cycles — et le jour où les instructions auront une
 * durée, c'est la convention qui disparaîtra, pas les constantes.
 *
 * Cette convention fait tourner la console trop vite par rapport à ce qu'une
 * vraie ferait de son temps. Ce qu'elle préserve, et qui compte davantage ici,
 * c'est le rapport entre les deux processeurs et la place du balayage : un
 * programme qui attend le retour vertical l'obtient au bon moment de la trame,
 * quel que soit le nombre d'instructions qu'il a exécutées d'ici là.
 *
 * ### Ce qui arrête un processeur, et ce qui le relance
 *
 * Les deux processeurs savent s'arrêter, et par deux chemins différents : le
 * principal par une opération de son coprocesseur, le secondaire par un registre
 * d'entrée-sortie. Cette asymétrie est celle du matériel. Ce qui les relance,
 * en revanche, est le même des deux côtés, et **ce n'est pas la condition qui
 * fait prendre l'interruption** : une source autorisée en attente suffit, sans
 * l'autorisation générale. Un programme de console coupe couramment cette
 * autorisation avant de s'arrêter, pour traiter la demande à la main plutôt que
 * par le vecteur ; la lui imposer pour repartir l'endormirait définitivement.
 *
 * ### Ce qui n'est pas là
 *
 * Rien ne charge de programme. Cet organe fait tourner ce qui se trouve en
 * mémoire, et ce qui l'y met — l'amorçage depuis la cartouche — n'existe pas
 * encore. C'est pourquoi `run_frame` du cœur public refuse toujours : la console
 * sait tourner, elle ne sait pas encore démarrer.
 *
 * Ni minuteries, ni transferts autonomes, ni son, ni entrées : les organes qui
 * poseraient les autres interruptions n'existent pas, et le balayage reste la
 * seule horloge de la console.
 */
class Machine {
public:
    Machine();

    /** Cycles de l'horloge maître par ligne balayée : 355 points à six cycles. */
    static constexpr std::uint32_t cycles_per_line =
        DisplayController::dots_per_line * DisplayController::cycles_per_dot;

    /** Le processeur principal bat deux fois plus vite que le secondaire. */
    static constexpr std::uint32_t main_clock_multiplier = 2;

    /**
     * Instructions accordées à chaque processeur pour une ligne.
     *
     * Une par cycle : c'est la convention décrite plus haut, et non une durée
     * mesurée.
     */
    static constexpr std::uint32_t secondary_steps_per_line = cycles_per_line;
    static constexpr std::uint32_t main_steps_per_line = cycles_per_line * main_clock_multiplier;

    void reset();

    /**
     * Fait avancer les deux processeurs d'une ligne, dessine cette ligne, puis
     * passe à la suivante.
     *
     * L'ordre compte. Une ligne se dessine **après** que les processeurs ont eu
     * leur temps, parce que le gestionnaire réveillé par le retour horizontal de
     * la ligne précédente s'exécute pendant celle-ci et prépare ce qu'elle doit
     * montrer. La dessiner d'abord ignorerait tout ce qu'il vient de faire.
     */
    void run_line(std::span<std::int32_t> framebuffer);

    /**
     * Balaie une trame entière.
     *
     * Le faisceau n'est pas ramené au début : 263 lignes le ramènent d'elles-
     * mêmes là où il était, et chaque ligne visible est dessinée exactement une
     * fois. Deux appels successifs ne se recouvrent donc pas, où que le balayage
     * en soit.
     */
    void run_frame(std::span<std::int32_t> framebuffer);

    [[nodiscard]] ArmCore& core(Processor side) noexcept;

    [[nodiscard]] Arm9MemoryMap& main_memory() noexcept { return main_map_; }
    [[nodiscard]] Arm7MemoryMap& secondary_memory() noexcept { return secondary_map_; }
    [[nodiscard]] SystemMemory& system_memory() noexcept { return system_; }
    [[nodiscard]] VideoSystem& video() noexcept { return video_; }
    [[nodiscard]] InterProcessor& link() noexcept { return link_; }
    [[nodiscard]] InterruptController& interrupts(Processor side) noexcept;

    /** Le coprocesseur système, que seul le processeur principal possède. */
    [[nodiscard]] Cp15& cp15() noexcept { return main_core_.cp15(); }
    /** Le balayage, que les deux processeurs consultent. */
    [[nodiscard]] DisplayController& display() noexcept { return video_.display(); }

private:
    /** Fait avancer un processeur d'une instruction, réveils compris. */
    void step(Processor side);

    // L'ordre de déclaration est celui des dépendances : ce que les deux
    // processeurs partagent d'abord, les cartes ensuite, les cœurs en dernier.
    SystemMemory system_{};
    InterruptController main_interrupts_{};
    InterruptController secondary_interrupts_{};
    InterProcessor link_;
    VideoSystem video_;
    Arm9MemoryMap main_map_;
    Arm7MemoryMap secondary_map_;
    Arm9 main_core_;
    Arm7 secondary_core_;
};

} // namespace ravenemu::nds
