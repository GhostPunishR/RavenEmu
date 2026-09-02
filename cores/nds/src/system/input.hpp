#pragma once

#include <ravenemu/core.hpp>

#include <cstdint>

namespace ravenemu::nds {

/**
 * L'état des touches, que les deux processeurs consultent.
 *
 * ### Ce qui se lit où
 *
 * Les dix touches de la face avant se lisent des deux côtés, à la même adresse.
 * Les deux touches supplémentaires, le contact de l'écran tactile et celui du
 * couvercle ne se lisent **que du côté du processeur secondaire** : c'est lui
 * qui tient ces entrées, et un jeu qui veut les connaître doit les lui demander
 * par la file. Cette asymétrie est celle du matériel, et elle explique pourquoi
 * l'état vit ici plutôt que dans l'une des deux cartes : ce que deux processeurs
 * partagent n'appartient à aucun des deux.
 *
 * ### Un bit à zéro veut dire enfoncée
 *
 * Les deux registres sont **actifs à zéro**. Ce n'est pas une bizarrerie
 * gratuite : les touches tirent une ligne vers la masse, et une ligne que rien
 * ne tire se lit à un. Un émulateur qui l'oublierait donnerait une console dont
 * toutes les touches sont enfoncées en permanence, ce qui ne ressemble pas à une
 * panne mais à un jeu qui part tout seul.
 *
 * La même raison décide de ce que rendent les bits sans emploi : un pour tous,
 * puisque rien ne les tire. Ce n'est pas une affirmation de plus, c'est la
 * conséquence de la convention.
 *
 * ### Ce qui n'est pas là
 *
 * Les coordonnées de l'écran tactile. Elles ne passent pas par un registre mais
 * par un convertisseur que le processeur secondaire interroge en série, et cet
 * organe n'existe pas encore. Le contact lui-même est modélisé, parce qu'il est
 * porté par le registre des touches supplémentaires et non par le convertisseur.
 *
 * Le microphone, qui passe par le même convertisseur.
 */
class InputState {
public:
    // Bits du registre des dix touches, dans l'ordre du matériel.
    static constexpr std::uint16_t key_a = 1U << 0U;
    static constexpr std::uint16_t key_b = 1U << 1U;
    static constexpr std::uint16_t key_select = 1U << 2U;
    static constexpr std::uint16_t key_start = 1U << 3U;
    static constexpr std::uint16_t key_right = 1U << 4U;
    static constexpr std::uint16_t key_left = 1U << 5U;
    static constexpr std::uint16_t key_up = 1U << 6U;
    static constexpr std::uint16_t key_down = 1U << 7U;
    static constexpr std::uint16_t key_r = 1U << 8U;
    static constexpr std::uint16_t key_l = 1U << 9U;
    /** Les dix touches occupent les dix bits bas ; les six autres sont sans emploi. */
    static constexpr std::uint16_t key_mask = 0x03ff;

    // Bits du registre que seul le processeur secondaire lit.
    static constexpr std::uint16_t extra_x = 1U << 0U;
    static constexpr std::uint16_t extra_y = 1U << 1U;
    /** Contact de l'écran tactile, sans ses coordonnées. */
    static constexpr std::uint16_t extra_pen = 1U << 6U;
    static constexpr std::uint16_t extra_lid = 1U << 7U;
    static constexpr std::uint16_t extra_mask = extra_x | extra_y | extra_pen | extra_lid;

    /**
     * Touche du matériel correspondant à une touche de l'interface partagée.
     *
     * Les deux touches supplémentaires de la console n'y ont pas d'équivalent :
     * l'énumération partagée décrit une manette à dix touches. Elles sont donc
     * modélisées dans le matériel mais hors de portée de cette interface, et les
     * rendre atteignables demandera de l'élargir, ce qui touche les autres cœurs
     * et la couche Android.
     */
    [[nodiscard]] static constexpr std::uint16_t key_for(Button button) noexcept {
        switch (button) {
        case Button::up: return key_up;
        case Button::down: return key_down;
        case Button::left: return key_left;
        case Button::right: return key_right;
        case Button::a: return key_a;
        case Button::b: return key_b;
        case Button::start: return key_start;
        case Button::select: return key_select;
        case Button::l: return key_l;
        case Button::r: return key_r;
        }
        return 0;
    }

    void reset() noexcept { held_ = 0; extra_held_ = 0; }

    /** Enfonce ou relâche une ou plusieurs des dix touches. */
    void set_pressed(std::uint16_t keys, bool pressed) noexcept;
    /** Idem pour les entrées que seul le processeur secondaire voit. */
    void set_extra_pressed(std::uint16_t keys, bool pressed) noexcept;

    /** Touches enfoncées, actives à un : la vue de ce code, non celle du matériel. */
    [[nodiscard]] std::uint16_t held() const noexcept { return held_; }
    [[nodiscard]] std::uint16_t extra_held() const noexcept { return extra_held_; }

    /** Registre des dix touches, tel que le matériel le rend. */
    [[nodiscard]] std::uint16_t key_register() const noexcept {
        return static_cast<std::uint16_t>(~held_);
    }
    /** Registre des entrées du processeur secondaire, tel qu'il le rend. */
    [[nodiscard]] std::uint16_t extra_register() const noexcept {
        return static_cast<std::uint16_t>(~extra_held_);
    }

private:
    std::uint16_t held_{};
    std::uint16_t extra_held_{};
};

/**
 * Réglage du réveil par les touches, propre à chaque processeur.
 *
 * Deux conditions seulement, et elles ne se ressemblent pas : réveiller dès
 * qu'une des touches choisies est enfoncée, ou seulement quand **toutes** le
 * sont. La seconde sert aux combinaisons — c'est ainsi qu'un jeu attend qu'on
 * presse deux touches ensemble sans les scruter.
 */
class KeyInterrupt {
public:
    /** Les dix touches choisies occupent les dix bits bas. */
    static constexpr std::uint16_t mask_field = InputState::key_mask;
    static constexpr std::uint16_t enable = 1U << 14U;
    /** Posé, il faut toutes les touches choisies ; effacé, une seule suffit. */
    static constexpr std::uint16_t requires_all = 1U << 15U;
    /** Bits que le matériel laisse écrire. */
    static constexpr std::uint16_t writable_bits = mask_field | enable | requires_all;

    void reset() noexcept { control_ = 0; }

    [[nodiscard]] std::uint16_t control() const noexcept { return control_; }
    void set_control(std::uint16_t value) noexcept {
        control_ = static_cast<std::uint16_t>(value & writable_bits);
    }

    /**
     * Vrai quand les touches tenues satisfont la condition réglée.
     *
     * Une sélection vide ne réveille jamais, dans les deux conditions : avec
     * « une seule suffit » il n'y en a aucune à presser, et avec « toutes »
     * l'ensemble vide serait satisfait par n'importe quoi, ce qui réveillerait
     * sans fin un processeur qui n'a rien demandé.
     */
    [[nodiscard]] bool satisfied(std::uint16_t held) const noexcept;

private:
    std::uint16_t control_{};
};

} // namespace ravenemu::nds
