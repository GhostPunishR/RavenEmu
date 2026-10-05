#pragma once

#include <array>
#include <cstdint>

namespace ravenemu::nds {

/**
 * La mémoire de réglages de la console, vue depuis le port série.
 *
 * ### Ce que c'est, et ce que ce n'est pas
 *
 * La console porte une petite mémoire flash qui contient deux choses très
 * différentes : d'un côté le **programme d'amorçage graphique** — le menu, les
 * animations, le code qui lance une cartouche — et de l'autre un **bloc de
 * réglages** que l'utilisateur remplit — son nom, sa langue, son anniversaire,
 * et l'étalonnage de son écran tactile.
 *
 * Le programme d'amorçage graphique **n'est pas ici et ne peut pas y être** :
 * c'est du code de la console. RavenEmu ne le copie pas, et n'en a pas besoin,
 * puisqu'il amorce une cartouche directement d'après son en-tête.
 *
 * Le bloc de réglages, lui, n'est pas du code : c'est une **structure décrite
 * publiquement**, que ce fichier **remplit avec ses propres valeurs**. Aucun
 * octet n'en est relevé sur une console. Ce qui compte pour un jeu n'est pas
 * quelles valeurs il y trouve, mais qu'il en trouve de cohérentes.
 *
 * ### Pourquoi un jeu la lit
 *
 * Un jeu qui affiche le nom du joueur, choisit sa langue, ou convertit un
 * contact de l'écran tactile en pixel, lit ce bloc. Le dernier point est le plus
 * dur à contourner : **l'écran tactile ne rend pas des pixels mais des mesures
 * brutes**, et c'est l'étalonnage enregistré ici qui dit comment les traduire.
 * Un bloc absent ou incohérent donne un jeu qui répond à côté du doigt.
 *
 * ### L'étalonnage, et pourquoi il se referme sur lui-même
 *
 * L'étalonnage donne deux points : pour chacun, une mesure brute et le pixel qui
 * lui correspond. Un jeu interpole entre les deux. Ici les deux points sont
 * choisis pour que la mesure brute vaille **seize fois le pixel**, et le
 * convertisseur rend exactement cela. La conversion du jeu retombe donc au pixel
 * près sur l'endroit touché, et ce n'est pas une coïncidence heureuse : les deux
 * moitiés sont construites l'une pour l'autre, et une suite le vérifie.
 *
 * ### Ce que la flash rend là où rien n'est défini
 *
 * Des octets à un, comme une flash effacée. C'est ce qu'une mémoire non écrite
 * rend vraiment, et c'est plus honnête que des zéros : zéro est un contenu
 * plausible, un octet à un se reconnaît comme du vide.
 */
class Firmware {
public:
    /** Taille de la mémoire, telle que la console la présente. */
    static constexpr std::uint32_t size_bytes = 256U * 1024U;

    /** Longueur du bloc de réglages. */
    static constexpr std::uint32_t settings_bytes = 0x100;

    /**
     * Adresse du pointeur vers le bloc de réglages, et son unité.
     *
     * Le pointeur tient sur seize bits pour une mémoire de dix-huit : il compte
     * donc par groupes de huit octets, et c'est cette division que le lecteur
     * doit défaire.
     */
    static constexpr std::uint32_t settings_pointer_address = 0x20;
    static constexpr std::uint32_t settings_pointer_unit = 8;

    /**
     * Adresses des deux exemplaires du bloc de réglages.
     *
     * La console en garde deux et retient celui dont le compteur de mise à jour
     * est le plus haut, pour qu'une écriture interrompue ne perde pas les
     * réglages. Les deux exemplaires portés ici sont **identiques**, compteur
     * compris : quelle que soit la règle qu'un jeu applique pour choisir, il
     * trouve la même chose.
     */
    static constexpr std::uint32_t first_settings_address = 0x0003'fe00;
    static constexpr std::uint32_t second_settings_address = 0x0003'ff00;

    // Champs du bloc de réglages dont un jeu se sert.
    static constexpr std::uint32_t version_offset = 0x00;
    static constexpr std::uint32_t favourite_colour_offset = 0x02;
    static constexpr std::uint32_t birthday_month_offset = 0x03;
    static constexpr std::uint32_t birthday_day_offset = 0x04;
    static constexpr std::uint32_t nickname_offset = 0x06;
    static constexpr std::uint32_t nickname_length_offset = 0x1a;
    static constexpr std::uint32_t message_length_offset = 0x50;
    /** Étalonnage de l'écran tactile : deux mesures, deux pixels, deux fois. */
    static constexpr std::uint32_t calibration_offset = 0x58;
    static constexpr std::uint32_t language_offset = 0x64;
    static constexpr std::uint32_t update_count_offset = 0x70;
    /** Somme de contrôle des 0x70 premiers octets du bloc. */
    static constexpr std::uint32_t checksum_offset = 0x72;

    /** Version de la structure des réglages. */
    static constexpr std::uint8_t settings_version = 5;

    /**
     * Rapport entre une mesure brute et un pixel.
     *
     * Douze bits de mesure pour huit bits de pixel : seize est le plus grand
     * facteur qui ne déborde pas, et le laisser entier rend la conversion
     * exacte dans les deux sens.
     */
    static constexpr std::uint32_t touch_scale = 16;

    // Les deux points d'étalonnage, en pixels. Ils sont écartés sans toucher les
    // bords : deux points trop proches rendraient l'interpolation d'un jeu
    // sensible à l'arrondi.
    static constexpr std::uint8_t first_point_x = 0x20;
    static constexpr std::uint8_t first_point_y = 0x20;
    static constexpr std::uint8_t second_point_x = 0xe0;
    static constexpr std::uint8_t second_point_y = 0xb0;

    /** Langue inscrite dans les réglages. */
    static constexpr std::uint8_t language_english = 1;

    /** Ce que rend une adresse qu'aucun contenu ne couvre. */
    static constexpr std::uint8_t erased_byte = 0xff;

    Firmware() noexcept;

    /** Octet à [address], ou une flash effacée au-delà de ce qui est défini. */
    [[nodiscard]] std::uint8_t byte_at(std::uint32_t address) const noexcept;

    /** Le bloc de réglages tel qu'il est servi, pour qu'une suite le relise. */
    [[nodiscard]] const std::array<std::uint8_t, settings_bytes>& settings() const noexcept {
        return settings_;
    }

private:
    void build_settings() noexcept;
    void write16(std::uint32_t offset, std::uint16_t value) noexcept;

    std::array<std::uint8_t, settings_bytes> settings_{};
    /** Pointeur vers les réglages, dans l'unité que la console emploie. */
    std::uint16_t settings_pointer_{};
};

} // namespace ravenemu::nds
