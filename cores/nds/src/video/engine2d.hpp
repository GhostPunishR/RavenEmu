#pragma once

#include "video/video_memory.hpp"

#include <array>
#include <cstdint>
#include <span>

namespace ravenemu::nds {

/**
 * Nature d'un plan de décor, telle que le mode de fond la fixe.
 *
 * Le même numéro de plan ne désigne pas la même chose d'un mode à l'autre : le
 * plan 3 est un décor en tuiles dans un mode, une surface tournante dans un
 * autre, et n'existe pas dans un troisième. C'est ce tableau, et non le numéro,
 * qui dit comment le lire.
 */
enum class LayerKind : std::uint8_t {
    /** Le mode ne donne pas ce plan. */
    none,
    /** Tuiles et carte, avec défilement : le seul rendu de ce lot. */
    text,
    affine,
    extended,
    large_bitmap,
    three_dimensional,
};

/**
 * Moteur graphique 2D.
 *
 * La console en a deux, et ils ne sont pas identiques. Le principal peut placer
 * ses décors n'importe où dans une fenêtre de cinq cent douze kilooctets et
 * décale ses bases par deux champs supplémentaires ; il sait afficher une banque
 * vidéo telle quelle et lire une image depuis la mémoire principale ; il reçoit
 * le rendu 3D comme un plan de décor. Le secondaire n'a rien de tout cela. Ces
 * différences sont portées par un champ nommé plutôt que par deux classes, pour
 * la même raison que les deux processeurs partagent une implémentation : deux
 * copies dériveraient l'une de l'autre.
 *
 * ### Ce que ce moteur rend
 *
 * Les décors en mode texte : tuiles de huit sur huit, seize ou deux cent
 * cinquante-six couleurs, retournement dans les deux sens, quatre tailles de
 * carte, défilement, et la résolution des priorités entre les quatre plans et le
 * fond. C'est le socle : tous les autres modes de décor s'appuient sur les mêmes
 * palettes, les mêmes priorités et la même composition.
 *
 * Les sprites ordinaires : cent vingt-huit objets, douze formats, les deux
 * profondeurs de palette, les deux retournements, les deux rangements de tuiles,
 * et leur composition avec les décors. **Un sprite passe devant un décor de même
 * priorité**, ce qui n'est pas la règle qui vaut entre décors, et c'est ce qui
 * met un personnage devant son sol plutôt que dedans.
 *
 * Les décors transformés, sous leurs quatre formes : la carte d'un octet par
 * tuile des plans tournants, la carte de deux octets des plans étendus, et les
 * deux sortes d'image — un octet par point à travers la palette, ou deux octets
 * de couleur écrite en toutes lettres. Tous passent par la même matrice, avec
 * son point de départ saisi à l'écriture et avancé d'une ligne à l'autre, et
 * par le même choix au bord : se taire ou se répéter.
 *
 * ### Ce qu'il ne rend pas encore
 *
 * La grande image du dernier mode, le plan 3D, les sprites tournants, la
 * semi-transparence, la fenêtre par sprite, les sprites en image directe, les
 * fenêtres et les mélanges. Rien de cela n'est passé sous silence : un plan ou
 * un sprite décrit sous une de ces formes est **compté**, parce qu'un élément
 * absent qui ne dit rien se confond avec un élément vide, et que les deux ne
 * veulent pas dire la même chose.
 *
 * Les palettes étendues sont un cas à part, et ont leur propre compteur : le
 * plan est bien dessiné, mais des mauvaises couleurs. Une image aux teintes
 * fausses ne se cherche pas là où on cherche une image absente.
 *
 * ### Sur la couleur zéro
 *
 * La première couleur d'une palette n'est pas une couleur : c'est l'absence de
 * pixel. Un décor la traverse, et c'est ce qui permet à quatre plans de se
 * superposer sans se cacher entièrement. La première entrée de la palette de
 * décor sert par ailleurs de fond, là où plus rien ne couvre.
 */
class Engine2d {
public:
    /**
     * @param engine    lequel des deux moteurs, ce qui fixe ce qu'il sait faire
     * @param video     les banques et leur aiguillage, partagés par les deux
     * @param palette   les deux kilooctets de palette, partagés par les deux
     * @param objects   les deux kilooctets d'attributs d'objets, partagés aussi
     */
    Engine2d(
        Engine engine,
        VideoMemory& video,
        std::span<const std::uint8_t> palette,
        std::span<const std::uint8_t> objects
    ) noexcept;

    /** Nombre de plans de décor. */
    static constexpr std::size_t background_count = 4;
    /** Nombre de sprites décrits par la mémoire d'objets. */
    static constexpr std::size_t object_count = 128;
    /** Étendue d'une table de palette, en octets. */
    static constexpr std::uint32_t palette_table_bytes = 512;
    /** Étendue de la table d'attributs d'un moteur, en octets. */
    static constexpr std::uint32_t object_table_bytes = 1024;

    [[nodiscard]] Engine engine() const noexcept { return engine_; }

    void reset() noexcept;

    [[nodiscard]] std::uint32_t display_control() const noexcept { return display_control_; }
    void set_display_control(std::uint32_t value) noexcept { display_control_ = value; }

    [[nodiscard]] std::uint16_t background_control(std::size_t index) const noexcept;
    void set_background_control(std::size_t index, std::uint16_t value) noexcept;

    [[nodiscard]] std::uint16_t scroll_x(std::size_t index) const noexcept;
    void set_scroll_x(std::size_t index, std::uint16_t value) noexcept;
    [[nodiscard]] std::uint16_t scroll_y(std::size_t index) const noexcept;
    void set_scroll_y(std::size_t index, std::uint16_t value) noexcept;

    /**
     * Plans capables de tourner : les deux derniers seulement.
     *
     * Le matériel ne donne les paramètres de transformation qu'aux plans 2 et 3,
     * et c'est pourquoi les modes qui font tourner un décor le font toujours
     * avec ceux-là. Les tableaux de cette classe sont donc indexés par un
     * **rang de transformation** — 0 pour le plan 2, 1 pour le plan 3 — et non
     * par le numéro de plan, qui laisserait deux cases toujours vides.
     */
    static constexpr std::size_t affine_layers = 2;
    /** Numéro du premier plan qui tourne. */
    static constexpr std::size_t first_affine_layer = 2;
    /** Nombre de coefficients de la matrice : a, b, c, d. */
    static constexpr std::size_t affine_parameters = 4;

    /**
     * Un coefficient de la matrice, en virgule fixe à huit bits de fraction.
     *
     * [slot] est le rang de transformation, [which] le rang du coefficient dans
     * l'ordre où le matériel les range : a, b, c, d.
     */
    [[nodiscard]] std::int16_t affine_parameter(std::size_t slot, std::size_t which) const noexcept;
    void set_affine_parameter(std::size_t slot, std::size_t which, std::uint16_t value) noexcept;

    /**
     * Le point de l'image que le coin haut-gauche de l'écran montre.
     *
     * Vingt-huit bits signés, en virgule fixe à huit bits de fraction. Le
     * matériel le **saisit** à l'écriture et le fait avancer d'une ligne à
     * l'autre : c'est ce qui permet à une rotation de rester cohérente sur
     * toute la hauteur de l'écran.
     */
    [[nodiscard]] std::int32_t reference_x(std::size_t slot) const noexcept;
    void set_reference_x(std::size_t slot, std::uint32_t value) noexcept;
    [[nodiscard]] std::int32_t reference_y(std::size_t slot) const noexcept;
    void set_reference_y(std::size_t slot, std::uint32_t value) noexcept;

    /** Mode de fond en cours, celui qui décide de la nature de chaque plan. */
    [[nodiscard]] std::uint32_t background_mode() const noexcept { return display_control_ & 0x7U; }
    [[nodiscard]] LayerKind layer_kind(std::size_t index) const noexcept;

    /** Dessine une ligne de l'écran. La ligne doit faire au moins 256 pixels. */
    void render_row(std::uint32_t row, std::span<std::int32_t> out) noexcept;

    /** Plans demandés dans un mode que ce lot ne dessine pas encore. */
    [[nodiscard]] std::uint32_t unimplemented_layer_count() const noexcept { return unimplemented_layers_; }
    /** Modes d'affichage que ce lot ne sert pas encore. */
    [[nodiscard]] std::uint32_t unimplemented_display_count() const noexcept { return unimplemented_display_; }
    /** Sprites décrits sous une forme que ce lot ne dessine pas encore. */
    [[nodiscard]] std::uint32_t unimplemented_object_count() const noexcept { return unimplemented_objects_; }
    /**
     * Lignes dessinées avec la palette ordinaire là où une palette étendue
     * était demandée.
     *
     * Elles sont dessinées, mais **pas des bonnes couleurs**. Un compteur à
     * part parce que ce n'est ni un plan absent ni un plan rendu : c'est un
     * plan rendu de travers, et confondre les trois ferait chercher au mauvais
     * endroit devant une image aux teintes fausses.
     */
    [[nodiscard]] std::uint32_t unimplemented_palette_count() const noexcept { return unimplemented_palettes_; }

private:
    /** Ce qu'un plan a déposé sur un pixel. */
    struct Pixel {
        std::uint32_t colour;
        std::uint8_t priority;
    };

    /** Laquelle des deux tables de palette d'un moteur. */
    enum class PaletteTable : std::uint8_t { background, object };

    [[nodiscard]] std::uint32_t palette_colour(std::uint32_t index, PaletteTable table) const noexcept;
    /** Couleur de fond : là où aucun plan ne couvre. */
    [[nodiscard]] std::uint32_t backdrop_colour() const noexcept {
        return palette_colour(0, PaletteTable::background);
    }

    void render_text_row(
        std::size_t index,
        std::uint32_t row,
        std::span<Pixel> line
    ) noexcept;

    /** Ce qu'un plan transformé va lire, et comment. */
    struct TransformedSource {
        /** Les quatre formes qu'une image transformée peut prendre. */
        enum class Form : std::uint8_t {
            /** Carte d'un octet par tuile, tuiles de 256 couleurs. */
            tile_bytes,
            /** Carte de deux octets par tuile : retournements et palette. */
            tile_words,
            /** Image d'un octet par pixel, indices dans la palette. */
            bitmap_indexed,
            /** Image de deux octets par pixel, couleur directe. */
            bitmap_direct,
        };

        Form form;
        std::uint32_t width;
        std::uint32_t height;
        /** Base de la carte, ou de l'image quand il n'y a pas de carte. */
        std::uint32_t base;
        /** Base des tuiles ; sans objet pour une image. */
        std::uint32_t tile_base;
        /** Vrai quand l'image se répète au-delà de ses bords. */
        bool wrap;
        std::uint8_t priority;
    };

    /** Décrit ce qu'un plan transformé doit lire, ou rend faux s'il ne le sait pas. */
    [[nodiscard]] bool describe_transformed(
        std::size_t index,
        LayerKind kind,
        TransformedSource& source
    ) noexcept;

    /** Dessine une ligne d'un plan transformé, quelle que soit sa forme. */
    void render_transformed_row(
        std::size_t index,
        const TransformedSource& source,
        std::span<Pixel> line
    ) noexcept;

    /** Couleur d'un point de l'image, ou zéro pour un point transparent. */
    [[nodiscard]] std::uint32_t sample_transformed(
        const TransformedSource& source,
        std::uint32_t x,
        std::uint32_t y
    ) noexcept;

    /** Saisit les points de départ, au premier trait de l'image. */
    void latch_references() noexcept;
    /** Fait avancer les points de départ d'une ligne. */
    void advance_references() noexcept;

    void render_object_row(std::uint32_t row, std::span<Pixel> line) noexcept;
    /** Dépose un sprite sur la ligne, s'il la traverse. */
    void render_object(std::size_t index, std::uint32_t row, std::span<Pixel> line) noexcept;

    [[nodiscard]] bool has_main_extensions() const noexcept { return engine_ == Engine::main; }

    Engine engine_;
    VideoMemory& video_;
    std::span<const std::uint8_t> palette_;
    std::span<const std::uint8_t> objects_;

    std::uint32_t display_control_{};
    std::array<std::uint16_t, background_count> background_control_{};
    std::array<std::uint16_t, background_count> scroll_x_{};
    std::array<std::uint16_t, background_count> scroll_y_{};

    std::array<std::array<std::int16_t, affine_parameters>, affine_layers> affine_{};
    std::array<std::int32_t, affine_layers> reference_x_{};
    std::array<std::int32_t, affine_layers> reference_y_{};
    /** Où en est chaque plan tournant sur la ligne en cours. */
    std::array<std::int32_t, affine_layers> current_x_{};
    std::array<std::int32_t, affine_layers> current_y_{};

    std::uint32_t unimplemented_layers_{};
    std::uint32_t unimplemented_display_{};
    std::uint32_t unimplemented_objects_{};
    std::uint32_t unimplemented_palettes_{};
};

} // namespace ravenemu::nds
