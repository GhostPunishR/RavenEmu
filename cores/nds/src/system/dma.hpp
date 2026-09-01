#pragma once

#include "cpu/bus.hpp"
#include "system/inter_processor.hpp"
#include "system/interrupt_controller.hpp"

#include <array>
#include <cstdint>

namespace ravenemu::nds {

/**
 * Les quatre canaux de transfert autonome d'un processeur.
 *
 * ### Pourquoi ils comptent
 *
 * Un jeu ne recopie pas ses décors avec une boucle : il donne une adresse de
 * départ, une adresse d'arrivée, un nombre de mots, et le matériel s'en charge
 * pendant que le processeur fait autre chose. C'est par là que passent la
 * palette, la table des sprites et les tuiles à chaque trame. Un émulateur qui
 * n'a pas ces canaux ne montre pas un écran fautif : il montre un écran qui ne
 * se met jamais à jour, parce que rien n'arrive plus jamais dans la mémoire
 * vidéo.
 *
 * ### Quand un canal part
 *
 * À l'allumage pour un départ immédiat, ou au moment demandé : retour vertical,
 * retour horizontal. Les autres moments — la cartouche, la file géométrique, le
 * port Game Boy Advance, la liaison sans fil — désignent des organes qui
 * n'existent pas encore ; un canal qui les demande est **compté** plutôt que
 * parti au mauvais moment, un transfert déclenché trop tôt étant plus difficile
 * à diagnostiquer qu'un transfert qui n'a pas lieu.
 *
 * Les deux processeurs n'ont pas le même choix de moments ni la même largeur de
 * compteur, et ces différences sont portées par le champ qui dit lequel des deux
 * on sert, comme pour les processeurs et les moteurs graphiques.
 *
 * La valeur interdite du champ qui règle l'avancée de la **source** se comporte
 * ici comme un incrément. Le matériel n'en définit pas le comportement, il n'y a
 * donc rien à reproduire fidèlement ; incrémenter est le choix qui ressemble le
 * plus au reste du champ.
 *
 * ### La répétition
 *
 * Un canal peut se réarmer après chaque transfert. C'est ainsi qu'un jeu obtient
 * une copie à chaque trame sans y revenir. La répétition n'a de sens qu'avec un
 * moment : un départ immédiat qui se répéterait tournerait sans fin, et le
 * matériel ne le fait pas.
 *
 * ### Ce qui est approché, et dit
 *
 * Un transfert a lieu **d'un coup**, entre deux instructions, là où le matériel
 * l'entrelace avec le processeur en lui volant des cycles. La différence
 * s'observerait sur un programme qui lit la zone d'arrivée pendant que le
 * transfert la remplit ; elle ne s'observe pas sur un programme qui attend la
 * fin, ce que fait le logiciel ordinaire.
 */
class DmaController {
public:
    DmaController(Processor side, InterruptController& interrupts) noexcept
        : side_(side), interrupts_(interrupts) {}

    /** Nombre de canaux par processeur. */
    static constexpr std::size_t count = 4;
    /** Étendue d'un canal en mémoire : deux adresses et un compte. */
    static constexpr std::uint32_t channel_bytes = 12;

    // Bits de la commande, qui occupe le demi-mot haut du registre de compte.
    static constexpr std::uint32_t destination_mode_shift = 5;
    static constexpr std::uint32_t source_mode_shift = 7;
    static constexpr std::uint32_t mode_mask = 0x3;
    static constexpr std::uint16_t repeats = 1U << 9U;
    static constexpr std::uint16_t transfers_words = 1U << 10U;
    static constexpr std::uint16_t interrupt_enable = 1U << 14U;
    static constexpr std::uint16_t enable = 1U << 15U;

    /** Moment auquel un canal part. */
    enum class Timing : std::uint8_t {
        immediate,
        vertical_blank,
        horizontal_blank,
        /** Un moment dont l'organe n'existe pas encore. */
        unsupported,
    };

    /** Façon dont une adresse évolue entre deux unités transférées. */
    enum class AddressMode : std::uint8_t {
        increment,
        decrement,
        fixed,
        /** Incrémente puis reprend l'adresse de départ à la répétition. */
        increment_and_reload,
    };

    void reset() noexcept;

    [[nodiscard]] std::uint32_t source(std::size_t channel) const noexcept;
    void set_source(std::size_t channel, std::uint32_t value) noexcept;
    [[nodiscard]] std::uint32_t destination(std::size_t channel) const noexcept;
    void set_destination(std::size_t channel, std::uint32_t value) noexcept;

    /** Compte et commande, tels qu'ils partagent un mot de trente-deux bits. */
    [[nodiscard]] std::uint32_t control(std::size_t channel) const noexcept;
    void set_control(std::size_t channel, std::uint32_t value) noexcept;

    /** Moment demandé par un canal, décodé selon le processeur servi. */
    [[nodiscard]] Timing timing(std::size_t channel) const noexcept;

    /** Arme les canaux qui attendaient ce moment. */
    void trigger(Timing moment) noexcept;

    /** Vrai quand au moins un canal attend d'être exécuté. */
    [[nodiscard]] bool pending() const noexcept { return pending_ != 0U; }

    /**
     * Exécute les transferts armés.
     *
     * Les canaux sont servis du plus prioritaire au moins prioritaire, c'est-à-
     * dire du rang le plus bas au plus haut : deux canaux qui visent la même
     * arrivée doivent laisser le résultat du moins prioritaire par-dessus.
     */
    void run(Bus& memory) noexcept;

    /** Canaux armés sur un moment qu'aucun organe ne produit encore. */
    [[nodiscard]] std::uint32_t unsupported_timing_count() const noexcept {
        return unsupported_timings_;
    }

private:
    /** Ce qu'un canal possède en propre. */
    struct Channel {
        std::uint32_t source{};
        std::uint32_t destination{};
        /** Compte dans le demi-mot bas, commande dans le haut. */
        std::uint32_t control{};
        /** Adresses courantes, qui avancent pendant que le canal sert. */
        std::uint32_t cursor_source{};
        std::uint32_t cursor_destination{};
    };

    [[nodiscard]] std::uint16_t command(std::size_t channel) const noexcept;
    /** Nombre d'unités à transférer, un compte nul valant l'étendue entière. */
    [[nodiscard]] std::uint32_t units(std::size_t channel) const noexcept;
    [[nodiscard]] AddressMode source_mode(std::size_t channel) const noexcept;
    [[nodiscard]] AddressMode destination_mode(std::size_t channel) const noexcept;

    void arm(std::size_t channel) noexcept;
    void transfer(std::size_t channel, Bus& memory) noexcept;

    [[nodiscard]] bool serves_main() const noexcept { return side_ == Processor::main; }

    Processor side_;
    InterruptController& interrupts_;
    std::array<Channel, count> channels_{};
    /** Un bit par canal armé. */
    std::uint32_t pending_{};
    std::uint32_t unsupported_timings_{};
};

} // namespace ravenemu::nds
