#pragma once

#include "cpu/arm_core.hpp"
#include "memory/arm7_memory_map.hpp"
#include "memory/arm9_memory_map.hpp"
#include "memory/system_memory.hpp"
#include "system/inter_processor.hpp"
#include "system/interrupt_controller.hpp"
#include "video/video_system.hpp"

#include <ravenemu/nds/cartridge_header.hpp>

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
 * ### L'amorçage, et ce qu'il ne fait pas
 *
 * `boot` fait ce que l'en-tête de cartouche décrit, et rien de plus : les deux
 * binaires sont copiés à leurs adresses de chargement et les deux processeurs
 * pointés sur leurs points d'entrée. C'est assez pour qu'une cartouche tourne.
 *
 * **Ce n'est pas tout ce que le matériel fait.** Sur console, un programme
 * d'amorçage tourne avant la cartouche et laisse derrière lui un état que
 * l'en-tête ne décrit pas : piles des différents modes, mémoires locales du
 * processeur principal configurées, registres d'entrée-sortie initialisés. Cet
 * état n'est pas modélisé, parce que ses valeurs ne sont affirmées nulle part
 * dans ce dépôt et que les inventer serait une affirmation que rien ne vérifie.
 * Les deux processeurs partent donc de leur état de mise sous tension : mode
 * superviseur, interruptions masquées, piles à zéro. Un programme qui monte sa
 * propre pile et démasque lui-même ses interruptions démarre ; un programme qui
 * compte sur l'amorceur ne démarre pas, et c'est une limite connue plutôt qu'un
 * comportement approché.
 *
 * Une conséquence en découle, qui compte pour la suite. Le chargement passe par
 * la carte mémoire et non par le chemin d'écriture du processeur. Les deux
 * coïncident tant que les mémoires locales sont éteintes, ce qui est le cas
 * faute d'un amorceur pour les allumer ; le jour où cet état sera modélisé, le
 * chargement devra passer par le processeur, sinon un binaire destiné à une
 * mémoire locale atterrirait à côté.
 *
 * ### Ce qui n'est pas là
 *
 * Ni minuteries, ni transferts autonomes, ni son, ni entrées, ni bus de
 * cartouche : les organes qui poseraient les autres interruptions n'existent
 * pas, et le balayage reste la seule horloge de la console.
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
     * Charge une cartouche et pointe les deux processeurs sur leurs points
     * d'entrée.
     *
     * Remet d'abord la console à zéro : amorcer par-dessus une partie en cours
     * mêlerait deux exécutions.
     *
     * @param header en-tête déjà décodé, qui dit où sont les deux binaires
     * @param rom    l'image dont cet en-tête a été décodé
     */
    void boot(const CartridgeHeader& header, std::span<const std::uint8_t> rom);

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

    /**
     * Copie un bloc de la cartouche vers la mémoire, mot par mot.
     *
     * Le transfert de cartouche se fait par mots sur console, et une taille qui
     * n'est pas un multiple de quatre écrit donc jusqu'à trois octets de plus
     * que le bloc annoncé.
     *
     * La borne sur le tampon n'est pas une seconde validation : l'en-tête refuse
     * déjà les blocs qui sortent du fichier. Elle protège du cas où l'image
     * fournie n'est pas celle dont l'en-tête a été décodé.
     */
    static void load_block(
        Bus& memory,
        std::span<const std::uint8_t> rom,
        std::uint32_t offset,
        std::uint32_t address,
        std::uint32_t size
    );

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
