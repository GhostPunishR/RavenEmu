#pragma once

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace ravenemu::nds {

/** Lequel des deux moteurs graphiques s'adresse à la mémoire vidéo. */
enum class Engine : std::uint8_t {
    /** Moteur principal : trois destinations que l'autre n'a pas. */
    main,
    /** Moteur secondaire : plus étroit, et servi par d'autres banques. */
    secondary,
};

/**
 * Destination d'une banque vidéo.
 *
 * Une banque n'est pas une mémoire à une adresse fixe : c'est un bloc qu'on
 * branche quelque part. Le même bloc peut servir de décor au moteur principal,
 * de sprites au secondaire, de texture au moteur 3D ou de palette étendue, et
 * ce qu'il vaut à une adresse donnée dépend entièrement de ce branchement.
 */
enum class VramTarget : std::uint8_t {
    /** Fenêtre de transfert : celle qu'on emprunte pour remplir la banque. */
    transfer,
    background_main,
    object_main,
    background_secondary,
    object_secondary,
    texture,
    texture_palette,
    background_palette_main,
    object_palette_main,
    background_palette_secondary,
    object_palette_secondary,
    /** Prêtée au processeur secondaire, qui la voit dans sa propre carte. */
    secondary_processor,
    /** Combinaison que le matériel ne définit pas. */
    reserved,
};

/**
 * Les neuf banques vidéo et leur aiguillage.
 *
 * ### Pourquoi cette classe existe
 *
 * Les banques étaient jusqu'ici de simples tableaux d'octets atteignables par
 * la fenêtre de transfert. C'était suffisant tant que personne ne les lisait ;
 * ça ne l'est plus dès qu'un moteur graphique doit y trouver ses décors. Une
 * adresse de décor ne désigne pas une banque en particulier : elle désigne une
 * place dans une fenêtre, et c'est la configuration qui dit quelle banque y
 * répond, ou aucune.
 *
 * ### Le champ de destination n'a pas la même largeur partout
 *
 * Deux banques n'acceptent que quatre destinations, cinq en acceptent huit, et
 * la formule qui place la banque dans sa fenêtre change d'une banque à l'autre :
 * certaines se placent par blocs de 128 kilooctets, d'autres par blocs de 16, et
 * deux d'entre elles combinent deux bits d'écart qui ne se suivent pas. Ces
 * différences ne se déduisent de rien : elles sont décrites une par une.
 *
 * ### Quand deux banques se disputent la même place
 *
 * Rien n'empêche le logiciel de brancher deux banques au même endroit. Le
 * matériel ne tranche pas vraiment — le résultat n'est pas défini — et prétendre
 * le contraire serait une affirmation gratuite. Ici la première banque dans
 * l'ordre alphabétique répond, et le recouvrement est **compté** : un émulateur
 * qui l'absorberait en silence laisserait une faute de configuration se
 * manifester bien plus loin, sous la forme d'un décor faux.
 *
 * ### Ce qui est décodé sans être servi
 *
 * Les textures, les palettes de textures et les palettes étendues ont leurs
 * destinations décodées, et une banque qui y est branchée disparaît bien des
 * fenêtres de décor et de sprites — c'est le point qui compte pour le moteur 2D.
 * Mais rien ne lit encore ces fenêtres-là, faute de moteur 3D et de palettes
 * étendues. Les nommer permet de les distinguer d'une banque éteinte, ce qui
 * n'est pas la même chose.
 */
class VideoMemory {
public:
    VideoMemory();

    /** Nombre de banques, nommées de A à I sur le matériel. */
    static constexpr std::size_t bank_count = 9;
    /** Première adresse de la fenêtre de transfert. */
    static constexpr std::uint32_t transfer_base = 0x0680'0000;

    /** Étendue de chaque fenêtre de moteur, en octets. */
    static constexpr std::uint32_t background_main_bytes = 512U * 1024U;
    static constexpr std::uint32_t object_main_bytes = 256U * 1024U;
    static constexpr std::uint32_t background_secondary_bytes = 128U * 1024U;
    static constexpr std::uint32_t object_secondary_bytes = 128U * 1024U;

    void reset() noexcept;

    [[nodiscard]] std::uint8_t control(std::size_t bank) const noexcept;
    void set_control(std::size_t bank, std::uint8_t value) noexcept;

    /** Contenu brut d'une banque, pour la remplir sans passer par le bus. */
    [[nodiscard]] std::span<std::uint8_t> bank(std::size_t index) noexcept;

    /**
     * Octet visé dans la fenêtre de transfert, ou nul.
     *
     * Nul veut dire deux choses que le matériel ne distingue pas non plus : la
     * banque est éteinte, ou elle est branchée ailleurs et ne se voit plus ici.
     */
    [[nodiscard]] std::uint8_t* transfer(std::uint32_t address) noexcept;

    /** Lecture par la fenêtre de décor d'un moteur ; zéro si rien n'y répond. */
    [[nodiscard]] std::uint8_t read_background(Engine engine, std::uint32_t offset) const noexcept;
    [[nodiscard]] std::uint16_t read_background16(Engine engine, std::uint32_t offset) const noexcept;
    /** Lecture par la fenêtre de sprites d'un moteur ; zéro si rien n'y répond. */
    [[nodiscard]] std::uint8_t read_object(Engine engine, std::uint32_t offset) const noexcept;

    /** Où une banque est branchée, et à quelle place dans sa fenêtre. */
    struct Assignment {
        VramTarget target{VramTarget::transfer};
        std::uint32_t offset{};
        bool enabled{};
    };

    [[nodiscard]] Assignment assignment(std::size_t bank) const noexcept;

    /** Lectures tombées sur une place que deux banques se disputent. */
    [[nodiscard]] std::uint32_t overlap_count() const noexcept { return overlaps_; }
    /** Lectures tombées sur une place qu'aucune banque ne sert. */
    [[nodiscard]] std::uint32_t unbacked_count() const noexcept { return unbacked_; }

private:
    /** Lecture par une fenêtre quelconque, celle qui porte toute la logique. */
    [[nodiscard]] std::uint8_t read_window(VramTarget target, std::uint32_t offset) const noexcept;

    std::vector<std::uint8_t> bytes_;
    std::array<std::uint8_t, bank_count> control_{};

    // Comptés pendant une lecture, qui est par ailleurs constante : ce sont des
    // observations sur la configuration, pas un état du matériel.
    mutable std::uint32_t overlaps_{};
    mutable std::uint32_t unbacked_{};
};

} // namespace ravenemu::nds
