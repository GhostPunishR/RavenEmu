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
 * ### Ce que ce lot rend
 *
 * Les décors en mode texte : tuiles de huit sur huit, seize ou deux cent
 * cinquante-six couleurs, retournement dans les deux sens, quatre tailles de
 * carte, défilement, et la résolution des priorités entre les quatre plans et le
 * fond. C'est le socle : tous les autres modes de décor s'appuient sur les mêmes
 * palettes, les mêmes priorités et la même composition.
 *
 * ### Ce qu'il ne rend pas encore
 *
 * Les sprites, les décors tournants, les modes étendus, la grande image, le plan
 * 3D, les fenêtres, les mélanges, la mosaïque et les palettes étendues. Un plan
 * demandé dans un de ces modes n'est pas dessiné en silence : il est **compté**,
 * parce qu'un plan absent qui ne dit rien se confond avec un plan vide, et que
 * les deux ne veulent pas dire la même chose.
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
     */
    Engine2d(Engine engine, VideoMemory& video, std::span<const std::uint8_t> palette) noexcept;

    /** Nombre de plans de décor. */
    static constexpr std::size_t background_count = 4;
    /** Étendue d'une table de palette, en octets. */
    static constexpr std::uint32_t palette_table_bytes = 512;

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

    /** Mode de fond en cours, celui qui décide de la nature de chaque plan. */
    [[nodiscard]] std::uint32_t background_mode() const noexcept { return display_control_ & 0x7U; }
    [[nodiscard]] LayerKind layer_kind(std::size_t index) const noexcept;

    /** Dessine une ligne de l'écran. La ligne doit faire au moins 256 pixels. */
    void render_row(std::uint32_t row, std::span<std::int32_t> out) noexcept;

    /** Plans demandés dans un mode que ce lot ne dessine pas encore. */
    [[nodiscard]] std::uint32_t unimplemented_layer_count() const noexcept { return unimplemented_layers_; }
    /** Modes d'affichage que ce lot ne sert pas encore. */
    [[nodiscard]] std::uint32_t unimplemented_display_count() const noexcept { return unimplemented_display_; }

private:
    /** Ce qu'un plan a déposé sur un pixel. */
    struct Pixel {
        std::uint32_t colour;
        std::uint8_t priority;
    };

    [[nodiscard]] std::uint32_t palette_colour(std::uint32_t index) const noexcept;
    /** Couleur de fond : là où aucun plan ne couvre. */
    [[nodiscard]] std::uint32_t backdrop_colour() const noexcept { return palette_colour(0); }

    void render_text_row(
        std::size_t index,
        std::uint32_t row,
        std::span<Pixel> line
    ) noexcept;

    [[nodiscard]] bool has_main_extensions() const noexcept { return engine_ == Engine::main; }

    Engine engine_;
    VideoMemory& video_;
    std::span<const std::uint8_t> palette_;

    std::uint32_t display_control_{};
    std::array<std::uint16_t, background_count> background_control_{};
    std::array<std::uint16_t, background_count> scroll_x_{};
    std::array<std::uint16_t, background_count> scroll_y_{};

    std::uint32_t unimplemented_layers_{};
    std::uint32_t unimplemented_display_{};
};

} // namespace ravenemu::nds
