#pragma once

#include "cpu/arm_core.hpp"
#include "cpu/bus.hpp"
#include "system/inter_processor.hpp"
#include "system/interrupt_controller.hpp"

#include <cstdint>
#include <optional>
#include <span>

namespace ravenemu::nds {

class Cp15;

/**
 * Les services du programme d'amorçage, rendus sans ce programme.
 *
 * ### Pourquoi cet organe existe
 *
 * Un jeu de la console ne se contente pas de son propre code : il appelle le
 * programme d'amorçage pour attendre le retour vertical, diviser, décompresser
 * ses données, recopier de la mémoire. Ces appels passent par `SWI`. Sans
 * personne pour y répondre, un jeu ne dépasse pas ses premières instructions —
 * pas parce qu'il plante, mais parce qu'il attend une réponse qui ne vient pas.
 *
 * **Ce programme n'est pas fourni avec RavenEmu et ne peut pas l'être** : c'est
 * du code de la console. Les services sont donc réécrits d'après la description
 * publique de leur **comportement** : ce qu'ils reçoivent, ce qu'ils rendent.
 * Aucun octet n'en est copié, et l'organe ne prétend pas reproduire les durées
 * ni les effets de bord non documentés.
 *
 * ### Ce qui est rendu hors du processeur, et ce qui ne l'est pas
 *
 * L'appel logiciel est **intercepté** avant son vecteur : le service est rendu
 * ici, et l'exécution reprend à l'instruction suivante, comme le fait le vrai
 * programme d'amorçage en revenant par `movs pc, lr`. Un appel non couvert
 * redescend au vecteur, où le programme trouvera ce que la mémoire contient.
 *
 * **L'interruption, elle, n'est pas interceptée.** Le vecteur porte un
 * gestionnaire écrit pour RavenEmu — six instructions, dans la région du
 * programme d'amorçage — que le processeur émulé exécute vraiment. Le
 * changement de mode, l'empilement et le retour restent donc ceux du matériel,
 * là où les simuler aurait demandé de reproduire à la main ce que le cœur sait
 * déjà faire. Ce gestionnaire saute au gestionnaire du jeu, dont l'adresse est
 * rangée à une place que la console fixe, puis rend la main.
 *
 * ### L'attente d'interruption
 *
 * C'est le service qui compte le plus, et le seul qui ait de la mémoire. Un jeu
 * qui attend le retour vertical arrête son processeur, et ce qui le relance
 * n'est pas l'interruption elle-même mais un **mot d'indicateurs que son propre
 * gestionnaire tient à jour**. Ce mot est à une place fixe, en bout de la
 * mémoire propre du processeur secondaire et en bout de la mémoire locale de
 * données du principal. L'attente consulte ce mot ; tant que le bit demandé n'y
 * est pas, le processeur reste arrêté.
 *
 * Cette indirection n'est pas un détail : sans elle, l'attente se terminerait
 * dès la première interruption venue, quelle qu'elle soit, et un jeu qui attend
 * le retour vertical repartirait sur un débordement de minuterie.
 *
 * ### Où la table des vecteurs est cherchée
 *
 * Le processeur secondaire n'a pas le choix : sa table est en bas de l'espace,
 * là où sa région commence. Le principal, lui, la place où son coprocesseur le
 * dit, et ce coprocesseur sort de la mise sous tension en désignant la base
 * basse alors que sa région est en haut.
 *
 * Ce décalage ne laisse pas de trou, et il vaut mieux le dire que le combler au
 * jugé : à la mise sous tension, le processeur masque ses interruptions. Aucune
 * ne peut donc être prise avant que le programme ne les démasque, et un
 * programme qui les démasque a d'abord configuré son coprocesseur — il n'aurait
 * ni pile ni gestionnaire sans cela. Poser la base haute d'office aurait été
 * une affirmation sur le matériel que rien dans ce dépôt ne soutient.
 */
class Bios final : public SoftwareInterruptHandler {
public:
    /**
     * @param side       lequel des deux processeurs est servi
     * @param core       le cœur dont les registres portent arguments et résultats
     * @param memory     la carte mémoire de ce processeur
     * @param interrupts son contrôleur d'interruptions
     * @param cp15       son coprocesseur, ou rien pour le processeur secondaire
     */
    Bios(
        Processor side,
        ArmCore& core,
        Bus& memory,
        InterruptController& interrupts,
        const Cp15* cp15
    ) noexcept;

    /** Réécrit la table des vecteurs et oublie toute attente en cours. */
    void install(std::span<std::uint8_t> region) noexcept;

    void reset() noexcept;

    [[nodiscard]] bool handle_software_interrupt(std::uint32_t number) override;

    /** Vrai tant qu'une attente d'interruption est en cours. */
    [[nodiscard]] bool waiting() const noexcept { return wait_.has_value(); }

    /** Adresse du mot d'indicateurs que le gestionnaire du jeu tient à jour. */
    [[nodiscard]] std::uint32_t interrupt_flags_address() const noexcept;

    /** Adresse où le jeu range l'adresse de son gestionnaire d'interruption. */
    [[nodiscard]] std::uint32_t interrupt_handler_address() const noexcept;

    /** Appels rencontrés que cet organe ne couvre pas. */
    [[nodiscard]] std::uint32_t unsupported_count() const noexcept { return unsupported_; }
    /** Premier appel non couvert rencontré, ou zéro. */
    [[nodiscard]] std::uint32_t first_unsupported() const noexcept { return first_unsupported_; }

    // Numéros des appels servis, dans l'ordre où le matériel les range.
    static constexpr std::uint32_t call_soft_reset = 0x00;
    static constexpr std::uint32_t call_wait_by_loop = 0x03;
    static constexpr std::uint32_t call_interrupt_wait = 0x04;
    static constexpr std::uint32_t call_vertical_blank_wait = 0x05;
    static constexpr std::uint32_t call_halt = 0x06;
    static constexpr std::uint32_t call_divide = 0x09;
    static constexpr std::uint32_t call_copy = 0x0b;
    static constexpr std::uint32_t call_fast_copy = 0x0c;
    static constexpr std::uint32_t call_square_root = 0x0d;
    static constexpr std::uint32_t call_checksum = 0x0e;
    static constexpr std::uint32_t call_is_debugger = 0x0f;
    static constexpr std::uint32_t call_bit_unpack = 0x10;
    static constexpr std::uint32_t call_lz77_to_memory = 0x11;
    static constexpr std::uint32_t call_lz77_to_video = 0x12;
    static constexpr std::uint32_t call_huffman = 0x13;
    static constexpr std::uint32_t call_run_length_to_memory = 0x14;
    static constexpr std::uint32_t call_run_length_to_video = 0x15;
    static constexpr std::uint32_t call_unfilter_8bit_to_memory = 0x16;
    static constexpr std::uint32_t call_unfilter_8bit_to_video = 0x17;
    static constexpr std::uint32_t call_unfilter_16bit = 0x18;

    /**
     * Où le gestionnaire du jeu est rangé, du côté du processeur secondaire.
     *
     * En bout de sa mémoire propre. Le mot d'indicateurs le précède
     * immédiatement, et c'est cet ordre que les programmes attendent.
     */
    static constexpr std::uint32_t secondary_handler_address = 0x0380'fffc;

    /** Distance entre le pointeur du gestionnaire et le mot d'indicateurs. */
    static constexpr std::uint32_t flags_below_handler = 4;

    /**
     * Où chaque processeur trouve l'adresse à laquelle repartir sur une relance.
     *
     * Ces deux places sont fixées par la console, en haut de la mémoire
     * principale, et le jeu y range son point de reprise avant de demander la
     * relance.
     */
    static constexpr std::uint32_t main_restart_vector = 0x027f'fe24;
    static constexpr std::uint32_t secondary_restart_vector = 0x027f'fe34;

    /** Étendue maximale d'une sortie de décompression, garde-fou du dépôt. */
    static constexpr std::uint32_t max_output_bytes = 1U << 22U;

private:
    /** Ce qu'une attente d'interruption retient d'un appel à l'autre. */
    struct Wait {
        std::uint32_t mask;
    };

    [[nodiscard]] bool serves_main() const noexcept { return side_ == Processor::main; }

    void write_interrupt_vector(std::span<std::uint8_t> region) noexcept;

    /**
     * Fait recommencer l'appel en cours au réveil.
     *
     * L'attente d'interruption du programme d'amorçage est une boucle. Elle est
     * tenue ici par le compteur de programme plutôt que par un état de plus : au
     * réveil, le gestionnaire du jeu s'exécute, rend la main sur l'appel, et
     * l'appel se repose la question. C'est aussi ce qui garantit qu'un réveil
     * causé par une autre source ne termine pas l'attente.
     */
    void repeat_call() noexcept;

    void soft_reset();
    void interrupt_wait(bool discard, std::uint32_t mask);
    void divide();
    void square_root();
    void checksum();
    void copy(bool fast);
    void bit_unpack();
    void lz77();
    void huffman();
    void run_length();
    void unfilter(bool halfwords);

    [[nodiscard]] std::uint32_t& reg(std::size_t index) noexcept;
    [[nodiscard]] std::uint32_t flags() noexcept;
    void clear_flags(std::uint32_t mask) noexcept;

    void note_unsupported(std::uint32_t number) noexcept;

    Processor side_;
    ArmCore& core_;
    Bus& memory_;
    InterruptController& interrupts_;
    const Cp15* cp15_;

    std::optional<Wait> wait_{};
    std::uint32_t unsupported_{};
    std::uint32_t first_unsupported_{};
};

} // namespace ravenemu::nds
