#pragma once

#include "system/interrupt_controller.hpp"

#include <array>
#include <cstdint>

namespace ravenemu::nds {

/**
 * Les quatre minuteries d'un processeur.
 *
 * ### Pourquoi elles comptent
 *
 * Jusqu'ici le balayage était la seule horloge de la console : un programme ne
 * pouvait mesurer que des trames. C'est trop grossier pour presque tout — un
 * son se cadence à des dizaines de milliers de fois par seconde, une animation
 * se compte en fractions de trame, une attente se compte en microsecondes. Un
 * jeu privé de minuteries ne va pas plus lentement : il s'arrête, parce qu'il
 * attend un compteur qui ne bouge jamais.
 *
 * ### Ce qui les fait avancer
 *
 * L'horloge maître, divisée. Quatre diviseurs seulement, et ce sont ceux du
 * matériel : un, soixante-quatre, deux cent cinquante-six, mille vingt-quatre.
 * Le reste de la division est **conservé d'un pas à l'autre** ; le jeter ferait
 * dériver une minuterie lente d'autant plus vite qu'on l'interroge souvent, et
 * la dérive serait invisible jusqu'à ce qu'un jeu compte dessus.
 *
 * ### L'enchaînement
 *
 * Une minuterie peut compter les débordements de celle qui la précède au lieu
 * de compter le temps. C'est ainsi qu'on obtient un compteur plus large que
 * seize bits, et c'est courant. La première n'a pas de prédécesseur : le bit
 * existe dans son registre mais ne peut rien commander, et il est donc sans
 * effet plutôt que refusé.
 *
 * ### Deux registres qui ne disent pas la même chose
 *
 * Le registre bas se lit et s'écrit, mais pas au même endroit : **on y lit le
 * compteur, on y écrit la valeur de rechargement**. Un émulateur qui écrirait
 * le compteur donnerait à un jeu le pouvoir de replacer le temps où il veut, et
 * un qui relirait le rechargement lui montrerait un compteur immobile.
 *
 * Le rechargement est repris à deux moments : quand la minuterie est allumée, et
 * à chaque débordement. L'allumage est un **front** : réécrire le bit d'allumage
 * sur une minuterie déjà allumée ne recharge rien.
 *
 * L'allumage ne remet pas la phase du diviseur à zéro. Rien ici ne dit qu'il le
 * ferait, et la remettre serait une affirmation que rien ne vérifie ; l'écart
 * possible vaut au plus une période de diviseur, soit moins d'un pas.
 *
 * ### Ce qui est approché, et dit
 *
 * Les minuteries avancent ici par blocs de cycles, à la granularité que leur
 * donne l'organe qui les fait tourner — une ligne de balayage. Une interruption
 * de minuterie tombe donc à une frontière de ligne plutôt qu'à l'instant exact
 * du débordement. C'est suffisant pour tout ce qui se compte en dizaines de
 * microsecondes ; ce ne le serait pas pour un son échantillonné directement par
 * une minuterie, qui demandera un modèle plus fin.
 */
class Timers {
public:
    explicit Timers(InterruptController& interrupts) noexcept : interrupts_(interrupts) {}

    /** Nombre de minuteries par processeur. */
    static constexpr std::size_t count = 4;

    // Bits du registre de commande.
    /** Deux bits de division de l'horloge. */
    static constexpr std::uint16_t prescaler_mask = 0x0003;
    /** Compter les débordements de la minuterie précédente plutôt que le temps. */
    static constexpr std::uint16_t cascade = 1U << 2U;
    static constexpr std::uint16_t interrupt_enable = 1U << 6U;
    static constexpr std::uint16_t enable = 1U << 7U;
    /** Bits que le matériel laisse écrire ; les autres se lisent à zéro. */
    static constexpr std::uint16_t writable_bits = prescaler_mask | cascade | interrupt_enable | enable;

    /** Diviseurs d'horloge, dans l'ordre du champ. */
    static constexpr std::array<std::uint32_t, 4> prescalers{{1, 64, 256, 1024}};

    void reset() noexcept;

    /** Compteur courant, celui que rend la lecture du registre bas. */
    [[nodiscard]] std::uint16_t counter(std::size_t index) const noexcept;
    /** Valeur de rechargement, celle qu'écrit le registre bas. */
    [[nodiscard]] std::uint16_t reload(std::size_t index) const noexcept;
    [[nodiscard]] std::uint16_t control(std::size_t index) const noexcept;

    void set_reload(std::size_t index, std::uint16_t value) noexcept;
    void set_control(std::size_t index, std::uint16_t value) noexcept;

    /**
     * Fait avancer les quatre minuteries de [cycles] cycles d'horloge maître.
     *
     * L'ordre compte : les minuteries sont traitées de la première à la
     * dernière, de sorte qu'un débordement se propage à l'enchaînée dans le même
     * pas plutôt qu'au suivant.
     */
    void advance(std::uint32_t cycles) noexcept;

    /** Débordements de la dernière avancée, pour les vérifications. */
    [[nodiscard]] std::uint32_t overflow_count(std::size_t index) const noexcept;

private:
    /** Ce qu'une minuterie possède en propre. */
    struct Timer {
        std::uint16_t counter{};
        std::uint16_t reload{};
        std::uint16_t control{};
        /** Cycles pas encore convertis en pas, gardés pour ne pas dériver. */
        std::uint32_t remainder{};
        std::uint32_t overflows{};
    };

    /**
     * Ajoute des pas à une minuterie et rend le nombre de débordements.
     *
     * Le calcul est fait d'un coup plutôt qu'en bouclant : une minuterie rapide
     * dont le rechargement est haut déborde des centaines de fois par ligne, et
     * boucler sur chaque débordement coûterait sans rien dire de plus.
     */
    [[nodiscard]] static std::uint32_t add_ticks(Timer& timer, std::uint32_t ticks) noexcept;

    InterruptController& interrupts_;
    std::array<Timer, count> timers_{};
};

} // namespace ravenemu::nds
