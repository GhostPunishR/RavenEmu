#pragma once

#include "system/firmware.hpp"
#include "system/input.hpp"
#include "system/interrupt_controller.hpp"

#include <array>
#include <cstdint>

namespace ravenemu::nds {

/**
 * L'alimentation de la console, interrogée par le port série.
 *
 * Trois choses lui sont demandées : allumer ou éteindre l'amplificateur du son
 * et le rétroéclairage des deux écrans, dire où en est la batterie, et éteindre
 * la console. Un jeu ne s'en sert pas beaucoup, mais **le code de démarrage du
 * processeur secondaire s'y adresse presque tout de suite** : c'est lui qui
 * allume les écrans, et sans réponse il n'en sort pas.
 *
 * Ce que cet organe fait des réglages qu'il reçoit : il les retient, et rien de
 * plus. Le rétroéclairage n'éteint pas l'image, parce qu'un écran éteint sur un
 * téléphone ressemblerait à une panne de l'émulateur plutôt qu'à une console
 * qui a coupé sa lumière. La demande d'extinction, elle, est retenue et se lit,
 * pour que la couche qui pilote la console sache que le jeu a demandé à sortir.
 */
class PowerManagement {
public:
    // Les quatre registres de la puce, dans son ordre.
    static constexpr std::uint8_t register_control = 0;
    static constexpr std::uint8_t register_battery = 1;
    static constexpr std::uint8_t register_microphone_amplifier = 2;
    static constexpr std::uint8_t register_microphone_gain = 3;
    static constexpr std::uint8_t register_count = 4;

    /** Dans le premier octet d'un échange : posé, il demande une lecture. */
    static constexpr std::uint8_t read_flag = 0x80;
    /** Le reste du premier octet désigne le registre. */
    static constexpr std::uint8_t index_mask = 0x7f;

    // Bits du registre de commande.
    static constexpr std::uint8_t sound_amplifier = 1U << 0U;
    static constexpr std::uint8_t sound_muted = 1U << 1U;
    static constexpr std::uint8_t lower_backlight = 1U << 2U;
    static constexpr std::uint8_t upper_backlight = 1U << 3U;
    static constexpr std::uint8_t power_off = 1U << 6U;

    /** Dans le registre de batterie : posé, il annonce une charge faible. */
    static constexpr std::uint8_t low_battery = 1U << 0U;

    /**
     * Ce que rend le registre de batterie.
     *
     * Une batterie saine. Un émulateur qui tourne sur un téléphone n'a pas de
     * batterie de console à relever, et annoncer une charge faible ferait
     * afficher un avertissement à un jeu sans raison.
     */
    static constexpr std::uint8_t healthy_battery = 0;

    void reset() noexcept;

    /** Échange un octet, et rend ce que la puce renvoie en même temps. */
    [[nodiscard]] std::uint8_t exchange(std::uint8_t byte) noexcept;

    /** Termine la commande en cours : la puce oublie le registre visé. */
    void deselect() noexcept { has_index_ = false; }

    [[nodiscard]] std::uint8_t control() const noexcept { return registers_[register_control]; }

    /** Vrai quand le programme a demandé l'extinction de la console. */
    [[nodiscard]] bool powered_off() const noexcept { return powered_off_; }

    /** Registres visés qui n'existent pas sur la puce. */
    [[nodiscard]] std::uint32_t unknown_register_count() const noexcept { return unknown_; }

private:
    std::array<std::uint8_t, register_count> registers_{};
    std::uint8_t index_{};
    bool reading_{};
    bool has_index_{};
    bool powered_off_{};
    std::uint32_t unknown_{};
};

/**
 * La mémoire de réglages vue comme une puce du bus série.
 *
 * `Firmware` dit **ce que la mémoire contient** ; cette classe dit **comment on
 * le lui demande**. Les deux sont séparées parce qu'elles se trompent
 * différemment : un contenu faux donne un jeu qui affiche le mauvais nom, un
 * protocole faux donne un jeu qui n'obtient rien du tout.
 *
 * ### Les commandes servies
 *
 * Lire à une adresse, et lire le registre d'état. Deux commandes de plus sont
 * reçues sans rien lire : celles qui arment et désarment l'écriture, qu'un
 * programme envoie avant d'écrire et dont il relit l'effet dans le registre
 * d'état. Les servir coûte deux bits et évite qu'un programme prudent se croie
 * en panne.
 *
 * **Aucune écriture n'est servie.** Les réglages de RavenEmu ne se modifient pas
 * depuis un jeu : il n'y a pas de fichier derrière eux, et une écriture acceptée
 * puis oubliée au redémarrage serait un mensonge plus coûteux qu'un refus. Une
 * commande non servie est comptée et rend zéro.
 */
class FirmwareFlash {
public:
    // Les commandes servies, dans le codage de la puce.
    static constexpr std::uint8_t command_read = 0x03;
    static constexpr std::uint8_t command_read_status = 0x05;
    static constexpr std::uint8_t command_write_enable = 0x06;
    static constexpr std::uint8_t command_write_disable = 0x04;

    /** Longueur de l'adresse qui suit une commande de lecture, en octets. */
    static constexpr std::uint8_t address_bytes = 3;

    // Bits du registre d'état.
    /** Une écriture est en cours. Elle ne l'est jamais ici : rien ne s'écrit. */
    static constexpr std::uint8_t status_busy = 1U << 0U;
    /** L'écriture est armée. */
    static constexpr std::uint8_t status_write_enabled = 1U << 1U;

    void reset() noexcept;

    [[nodiscard]] std::uint8_t exchange(std::uint8_t byte) noexcept;

    /** Termine la commande en cours : la puce repart d'une commande neuve. */
    void deselect() noexcept;

    [[nodiscard]] const Firmware& content() const noexcept { return content_; }
    [[nodiscard]] std::uint8_t status() const noexcept { return status_; }

    /** Commandes rencontrées que cette puce ne sert pas. */
    [[nodiscard]] std::uint32_t unsupported_count() const noexcept { return unsupported_; }
    /** Première commande non servie rencontrée, ou zéro. */
    [[nodiscard]] std::uint8_t first_unsupported() const noexcept { return first_unsupported_; }

private:
    [[nodiscard]] std::uint8_t begin_command(std::uint8_t command) noexcept;

    Firmware content_{};
    std::uint8_t command_{};
    bool has_command_{};
    /** Octets d'adresse encore attendus après une commande de lecture. */
    std::uint8_t address_remaining_{};
    std::uint32_t address_{};
    std::uint8_t status_{};
    std::uint32_t unsupported_{};
    std::uint8_t first_unsupported_{};
};

/**
 * Le convertisseur de l'écran tactile.
 *
 * ### Ce qu'il rend, et ce qu'il ne rend pas
 *
 * Il ne rend **pas des pixels** mais des mesures brutes sur douze bits, comme le
 * matériel : deux résistances, une par axe, dont il mesure la tension au point
 * de contact. C'est le jeu qui traduit, avec l'étalonnage enregistré dans les
 * réglages de la console. Rendre des pixels ici serait plus simple et
 * complètement faux : le jeu appliquerait sa conversion par-dessus et
 * atterrirait ailleurs.
 *
 * Les mesures rendues sont donc construites **pour l'étalonnage que RavenEmu
 * inscrit** dans les réglages, et les deux moitiés se referment l'une sur
 * l'autre au pixel près.
 *
 * ### Le protocole, en trois octets
 *
 * Le premier octet porte le bit de départ et le canal voulu ; il lance la
 * conversion et ne rend rien encore. Les deux suivants rendent les douze bits,
 * poids fort d'abord, décalés de trois vers la gauche — c'est ainsi que la puce
 * les présente, et un lecteur qui l'ignorerait obtiendrait des coordonnées huit
 * fois trop grandes.
 *
 * ### La pression
 *
 * Deux canaux la mesurent, et un jeu s'en sert pour distinguer un vrai contact
 * d'un frôlement. Ici, un contact est franc ou absent : l'écran d'un téléphone
 * donne une pression, mais elle n'a pas la même échelle que celle d'une
 * résistance, et la convertir demanderait une correspondance que rien n'établit.
 * Les deux canaux rendent donc un contact franc quand le stylet est posé, et
 * rien quand il ne l'est pas.
 */
class Touchscreen {
public:
    /** Posé dans le premier octet, il lance une conversion. */
    static constexpr std::uint8_t start_flag = 0x80;
    static constexpr std::uint8_t channel_shift = 4;
    static constexpr std::uint8_t channel_mask = 0x7;

    // Les canaux servis.
    static constexpr std::uint8_t channel_y = 1;
    static constexpr std::uint8_t channel_first_pressure = 3;
    static constexpr std::uint8_t channel_second_pressure = 4;
    static constexpr std::uint8_t channel_x = 5;

    /** Les mesures tiennent sur douze bits. */
    static constexpr std::uint16_t value_mask = 0x0fff;
    /** Décalage que la puce applique en présentant ses douze bits sur seize. */
    static constexpr std::uint8_t presentation_shift = 3;

    // Ce que rendent les deux canaux de pression sous un contact franc. Leur
    // rapport est ce qu'un jeu regarde ; leurs valeurs prises seules ne veulent
    // rien dire.
    static constexpr std::uint16_t first_pressure_value = 0x0200;
    static constexpr std::uint16_t second_pressure_value = 0x0e00;

    explicit Touchscreen(const InputState& input) noexcept : input_(input) {}

    void reset() noexcept;

    [[nodiscard]] std::uint8_t exchange(std::uint8_t byte) noexcept;

    /** Termine la commande en cours : une conversion entamée est abandonnée. */
    void deselect() noexcept { remaining_ = 0; }

    /** Canaux demandés que ce convertisseur ne sert pas. */
    [[nodiscard]] std::uint32_t unknown_channel_count() const noexcept { return unknown_; }

    /** Mesure brute correspondant à un pixel, selon l'étalonnage inscrit. */
    [[nodiscard]] static constexpr std::uint16_t measure(std::uint8_t pixel) noexcept {
        return static_cast<std::uint16_t>(pixel * Firmware::touch_scale);
    }

private:
    [[nodiscard]] std::uint16_t convert(std::uint8_t channel) noexcept;

    const InputState& input_;
    /** Mesure en attente de lecture, déjà décalée comme la puce la présente. */
    std::uint16_t pending_{};
    /** Octets restant à rendre pour la conversion en cours. */
    std::uint8_t remaining_{};
    std::uint32_t unknown_{};
};

/**
 * Le port série du processeur secondaire, et les trois puces qui y pendent.
 *
 * ### Pourquoi il bloque un jeu du commerce
 *
 * Trois choses passent par ce seul fil : **l'écran tactile**, **les réglages
 * enregistrés dans la console**, et **la commande d'alimentation**. Un jeu du
 * commerce a besoin des trois au démarrage, et son code du processeur secondaire
 * s'y adresse avant même d'afficher quoi que ce soit. Sans ce port, il n'attend
 * pas une image : il attend une réponse, et le processeur principal, qui attend
 * son signal, s'arrête avec lui.
 *
 * ### Comment un échange se fait
 *
 * Un bus série n'a pas de lecture ni d'écriture : il a des **échanges**. Chaque
 * octet écrit dans le registre de données en fait sortir un, et en fait entrer
 * un autre au même instant. Ce que le programme lit ensuite est donc la réponse
 * à l'octet qu'il vient d'envoyer, jamais à celui qu'il s'apprête à envoyer.
 * C'est cette avance d'un octet qui donne au protocole sa forme : une commande,
 * puis autant d'octets creux qu'il y a de réponse à recueillir.
 *
 * ### Le maintien de la sélection
 *
 * Un bit du registre de commande dit si la puce reste sélectionnée après
 * l'échange. C'est **lui qui rend les commandes de plusieurs octets possibles** :
 * tant qu'il est posé, la puce se souvient de ce qu'on lui a demandé ; dès qu'il
 * est retiré, elle oublie et l'octet suivant repart d'une commande neuve. Un
 * émulateur qui l'ignorerait servirait la première commande de chaque suite et
 * rien d'autre.
 *
 * ### Ce qui n'est pas modélisé, et qui est compté
 *
 * Aucune durée : le bit d'occupation ne se lève jamais, un échange étant fini
 * dès qu'il est demandé. Le débit réglé est retenu sans effet. Les échanges de
 * seize bits, que le matériel sert de façon particulière, sont comptés plutôt
 * qu'approchés.
 */
class SerialPort {
public:
    SerialPort(InterruptController& interrupts, const InputState& input) noexcept;

    /**
     * Les deux registres du port.
     *
     * Ils n'appartiennent pas au fichier des registres partagés : le processeur
     * principal ne les voit pas, et une seule carte les route. Ce qui est
     * partagé y vit ; ce qui ne l'est pas vit chez son organe.
     */
    static constexpr std::uint32_t control_address = 0x0400'01c0;
    static constexpr std::uint32_t data_address = 0x0400'01c2;

    // Bits du registre de commande.
    static constexpr std::uint16_t baud_rate_mask = 0x3;
    /** Posé pendant un échange sur console ; ici il ne se lève jamais. */
    static constexpr std::uint16_t busy = 1U << 7U;
    static constexpr std::uint16_t device_shift = 8;
    static constexpr std::uint16_t device_mask = 0x3;
    /** Posé, l'échange porte seize bits au lieu de huit. */
    static constexpr std::uint16_t wide_transfer = 1U << 10U;
    /** Posé, la puce reste sélectionnée après l'échange. */
    static constexpr std::uint16_t hold_selection = 1U << 11U;
    static constexpr std::uint16_t interrupt_enable = 1U << 14U;
    /** Sans lui, aucun échange n'a lieu. */
    static constexpr std::uint16_t bus_enable = 1U << 15U;
    /** Bits que le programme écrit vraiment : l'occupation appartient au matériel. */
    static constexpr std::uint16_t writable_control = static_cast<std::uint16_t>(~busy);

    /** Les puces qui pendent au bus, dans l'ordre où le registre les désigne. */
    enum class Device : std::uint8_t {
        power = 0,
        firmware = 1,
        touchscreen = 2,
        /** Rien n'y pend : la valeur existe dans le registre, pas sur le bus. */
        reserved = 3,
    };

    void reset() noexcept;

    [[nodiscard]] std::uint16_t control() const noexcept { return control_; }
    void set_control(std::uint16_t value) noexcept;

    /** Dernier octet reçu, en attente de lecture. */
    [[nodiscard]] std::uint8_t data() const noexcept { return data_; }

    /** Envoie un octet, et retient celui qui entre pendant qu'il sort. */
    void write_data(std::uint8_t byte) noexcept;

    /** La puce actuellement désignée par le registre de commande. */
    [[nodiscard]] Device device() const noexcept {
        return static_cast<Device>((control_ >> device_shift) & device_mask);
    }

    [[nodiscard]] PowerManagement& power() noexcept { return power_; }
    [[nodiscard]] FirmwareFlash& firmware() noexcept { return firmware_; }
    [[nodiscard]] Touchscreen& touchscreen() noexcept { return touchscreen_; }

    /** Échanges demandés vers une place où rien ne pend, ou dans une forme non servie. */
    [[nodiscard]] std::uint32_t unsupported_count() const noexcept { return unsupported_; }

    /**
     * Sert une lecture d'un registre du port, et dit si l'adresse lui
     * appartient.
     *
     * Comme pour le bus de cartouche, le décodage vit chez l'organe : la carte
     * ne garde que le routage. Lire le registre de données **ne déclenche
     * rien** — l'octet est déjà là, entré pendant que le précédent sortait.
     */
    [[nodiscard]] bool read_register(
        std::uint32_t address,
        std::uint32_t width,
        std::uint32_t& value
    ) const noexcept;

    /** Sert une écriture, et dit si l'adresse appartient au port. */
    [[nodiscard]] bool write_register(
        std::uint32_t address,
        std::uint32_t width,
        std::uint32_t value
    ) noexcept;

private:
    [[nodiscard]] static bool covers(std::uint32_t address, std::uint32_t width) noexcept;
    [[nodiscard]] std::uint8_t register_byte(std::uint32_t address) const noexcept;
    void write_register_byte(std::uint32_t address, std::uint8_t byte) noexcept;

    /** Fait oublier à toutes les puces la commande qu'elles avaient en cours. */
    void deselect_all() noexcept;

    InterruptController& interrupts_;

    PowerManagement power_{};
    FirmwareFlash firmware_{};
    Touchscreen touchscreen_;

    std::uint16_t control_{};
    std::uint8_t data_{};
    std::uint32_t unsupported_{};
};

} // namespace ravenemu::nds
