#pragma once

#include "system/inter_processor.hpp"
#include "system/interrupt_controller.hpp"

#include <array>
#include <cstdint>
#include <span>

namespace ravenemu::nds {

/**
 * Le bus de cartouche, par lequel un jeu lit la suite de sa ROM.
 *
 * ### Pourquoi il manquait, et ce qu'il change
 *
 * L'amorçage recopie les deux blocs de code que l'en-tête décrit, et rien de
 * plus. Tout le reste d'une cartouche — décors, sprites, musiques, niveaux, et
 * le code que le jeu charge en cours de route — se lit par ce bus, à la demande.
 * Un jeu privé de bus ne montre pas un écran incomplet : il s'arrête à la
 * première chose qu'il veut charger, et attend une donnée qui n'arrive jamais.
 *
 * ### Comment un transfert se fait
 *
 * Le jeu écrit une commande de huit octets, puis allume le transfert dans le
 * registre de commande en y indiquant la taille du bloc voulu. Le matériel rend
 * alors les données **mot par mot** : le jeu lit le port de données autant de
 * fois qu'il y a de mots, et un indicateur lui dit quand un mot est prêt. La fin
 * du transfert éteint l'allumage et peut poser une interruption.
 *
 * Cette cadence mot par mot n'est pas un détail de mise en œuvre : c'est elle
 * que le jeu observe, en scrutant l'indicateur ou en armant un canal de
 * transfert autonome sur ce moment précis.
 *
 * ### Les commandes servies
 *
 * Deux suffisent à un jeu déjà amorcé : **lire à une adresse** et **demander
 * l'identifiant de la puce**. Les autres appartiennent aux phases d'amorçage de
 * la console, qui chiffrent leurs échanges avec des clés que ce dépôt ne
 * contient pas et ne peut pas contenir. Une commande non servie **rend des
 * octets à un** et est comptée, plutôt que de rendre zéro : zéro est une donnée
 * plausible, un octet à un ressemble à une cartouche absente, ce que le
 * programme sait déjà interpréter.
 *
 * ### Ce qui est approché, et dit
 *
 * Aucun chiffrement. Sur console, les commandes sont brouillées par deux
 * mécanismes successifs pendant l'amorçage, et le jeu lui-même n'en voit rien :
 * il écrit des commandes en clair, que le matériel brouille sous lui. Ce bus
 * reçoit donc ce que le jeu écrit, ce qui est exactement ce dont le jeu a
 * besoin.
 *
 * Aucune durée. Un mot est prêt dès qu'on le demande, là où le matériel fait
 * attendre. La différence s'observerait sur un programme qui compte les cycles
 * d'attente ; elle ne s'observe pas sur un programme qui scrute l'indicateur ou
 * qui laisse un canal autonome faire la copie.
 *
 * ### L'image de la cartouche
 *
 * Ce bus **ne garde pas de copie** de l'image : il la relit à la demande. Une
 * cartouche fait jusqu'à cent vingt-huit mégaoctets, et en doubler la présence
 * en mémoire pour un téléphone n'aurait pas de sens. L'appelant garde donc
 * l'image vivante aussi longtemps que la console tourne, ce que la fabrique du
 * cœur assure en la possédant.
 */
class Cartridge {
public:
    explicit Cartridge(InterruptController& main, InterruptController& secondary) noexcept
        : main_interrupts_(main), secondary_interrupts_(secondary) {}

    /** Longueur d'une commande, en octets. */
    static constexpr std::size_t command_bytes = 8;

    // Bits du registre de commande du transfert.
    /** Taille du bloc demandé, sur trois bits. */
    static constexpr std::uint32_t block_size_shift = 24;
    static constexpr std::uint32_t block_size_mask = 0x7;
    /** Posé par le matériel quand un mot attend d'être lu. */
    static constexpr std::uint32_t data_ready = 1U << 23U;
    /** Posé par le jeu pour lancer un transfert, effacé à la fin. */
    static constexpr std::uint32_t start = 1U << 31U;
    /** Bits que le jeu écrit vraiment : les deux autres appartiennent au matériel. */
    static constexpr std::uint32_t writable_control = ~(data_ready | start);

    /** Autorisation d'interruption de fin de transfert, dans le registre auxiliaire. */
    static constexpr std::uint16_t transfer_interrupt = 1U << 14U;

    // Commandes servies.
    /** Lire un bloc à l'adresse portée par les quatre octets suivants. */
    static constexpr std::uint8_t command_read = 0xb7;
    /** Rendre l'identifiant de la puce. */
    static constexpr std::uint8_t command_chip_id = 0xb8;

    /**
     * Identifiant rendu pour la puce.
     *
     * La valeur décrit une cartouche ordinaire de la taille de l'image chargée.
     * Elle n'est pas relevée sur un matériel : un jeu s'en sert pour distinguer
     * une cartouche d'un lecteur de développement, et ce qui compte est qu'elle
     * ne ressemble ni à l'absence de cartouche, ni à un lecteur.
     */
    static constexpr std::uint32_t chip_id = 0x0000'00c2;

    void reset() noexcept;

    /** Confie une image au bus, sans en prendre de copie. */
    void insert(std::span<const std::uint8_t> image) noexcept;

    [[nodiscard]] std::uint32_t control() const noexcept { return control_; }
    void set_control(std::uint32_t value) noexcept;

    [[nodiscard]] std::uint16_t auxiliary_control() const noexcept { return auxiliary_; }
    void set_auxiliary_control(std::uint16_t value) noexcept { auxiliary_ = value; }

    [[nodiscard]] std::uint8_t command_byte(std::size_t index) const noexcept;
    void set_command_byte(std::size_t index, std::uint8_t value) noexcept;

    /**
     * Retire le mot suivant du transfert en cours.
     *
     * Hors transfert, rend des octets à un : c'est ce que lit un programme qui
     * s'adresse au port sans avoir rien demandé.
     */
    [[nodiscard]] std::uint32_t read_data() noexcept;

    /** Vrai tant qu'un transfert a des mots à rendre. */
    [[nodiscard]] bool transferring() const noexcept { return remaining_ != 0U; }

    /**
     * Lequel des deux processeurs tient le port.
     *
     * Un seul y accède à la fois, et c'est un registre du processeur principal
     * qui en décide. L'autre lit alors des octets à un, comme si la cartouche
     * n'était pas là.
     */
    [[nodiscard]] Processor owner() const noexcept { return owner_; }
    void set_owner(Processor side) noexcept { owner_ = side; }

    /** Commandes rencontrées que ce bus ne sert pas. */
    [[nodiscard]] std::uint32_t unsupported_count() const noexcept { return unsupported_; }
    /** Premier octet de commande non servi rencontré, ou zéro. */
    [[nodiscard]] std::uint8_t first_unsupported() const noexcept { return first_unsupported_; }

    /** Taille du bloc décrite par le champ [field], en octets. */
    [[nodiscard]] static std::uint32_t block_bytes(std::uint32_t field) noexcept;

    /**
     * Sert une lecture d'un registre du bus, et dit si l'adresse lui appartient.
     *
     * Le décodage est ici plutôt que dans chaque carte mémoire : les deux
     * processeurs voient les mêmes registres aux mêmes adresses, et deux copies
     * de ce décodage dériveraient. Ce que les cartes gardent est le routage,
     * pas la règle.
     *
     * Un processeur qui ne tient pas le port lit une cartouche absente. C'est
     * un refus **rendu**, non un refus signalé : l'adresse est bel et bien
     * décodée, et la compter comme inconnue serait faux.
     */
    [[nodiscard]] bool read_register(
        Processor side,
        std::uint32_t address,
        std::uint32_t width,
        std::uint32_t& value
    ) noexcept;

    /** Sert une écriture, et dit si l'adresse appartient au bus. */
    [[nodiscard]] bool write_register(
        Processor side,
        std::uint32_t address,
        std::uint32_t width,
        std::uint32_t value
    ) noexcept;

private:
    /** Vrai quand les [width] octets à partir de [address] appartiennent tous au bus. */
    [[nodiscard]] static bool covers(std::uint32_t address, std::uint32_t width) noexcept;
    [[nodiscard]] std::uint8_t register_byte(std::uint32_t address, bool held) const noexcept;
    void write_register_byte(std::uint32_t address, std::uint8_t byte) noexcept;

    void begin_transfer() noexcept;
    void finish_transfer() noexcept;
    [[nodiscard]] std::uint32_t word_at(std::uint32_t offset) const noexcept;
    void note_unsupported(std::uint8_t command) noexcept;

    InterruptController& main_interrupts_;
    InterruptController& secondary_interrupts_;

    std::span<const std::uint8_t> image_{};
    std::array<std::uint8_t, command_bytes> command_{};

    std::uint32_t control_{};
    std::uint16_t auxiliary_{};

    /** Adresse de lecture courante dans l'image, en octets. */
    std::uint32_t cursor_{};
    /** Mots restant à rendre. */
    std::uint32_t remaining_{};
    /** Vrai quand le transfert en cours ne lit pas l'image mais une valeur. */
    bool constant_source_{};
    std::uint32_t constant_value_{};

    Processor owner_{Processor::main};

    std::uint32_t unsupported_{};
    std::uint8_t first_unsupported_{};
};

} // namespace ravenemu::nds
