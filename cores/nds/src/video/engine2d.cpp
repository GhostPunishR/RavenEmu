#include "video/engine2d.hpp"

#include <ravenemu/nds/core.hpp>

#include <algorithm>

namespace ravenemu::nds {

namespace {

constexpr std::uint32_t row_pixels = static_cast<std::uint32_t>(screen_width);

/** Bits de la commande d'affichage. */
constexpr std::uint32_t three_dimensional_layer = 1U << 3U;
constexpr std::uint32_t forced_blank = 1U << 7U;
constexpr std::uint32_t first_background_enable = 8U;

/** Priorité du fond : derrière tout ce qu'un plan peut poser. */
constexpr std::uint8_t backdrop_priority = 4;

/**
 * Nature de chaque plan selon le mode de fond.
 *
 * C'est la table qui fait qu'un numéro de plan ne veut rien dire à lui seul. Le
 * dernier mode n'appartient qu'au moteur principal, et il n'a que deux plans.
 */
constexpr std::array<std::array<LayerKind, Engine2d::background_count>, 7> mode_layers{{
    {LayerKind::text, LayerKind::text, LayerKind::text, LayerKind::text},
    {LayerKind::text, LayerKind::text, LayerKind::text, LayerKind::affine},
    {LayerKind::text, LayerKind::text, LayerKind::affine, LayerKind::affine},
    {LayerKind::text, LayerKind::text, LayerKind::text, LayerKind::extended},
    {LayerKind::text, LayerKind::text, LayerKind::affine, LayerKind::extended},
    {LayerKind::text, LayerKind::text, LayerKind::extended, LayerKind::extended},
    {LayerKind::three_dimensional, LayerKind::none, LayerKind::large_bitmap, LayerKind::none},
}};

/** Cinq bits par composante deviennent huit sans perdre les extrêmes. */
[[nodiscard]] constexpr std::uint32_t expand_component(std::uint32_t value) noexcept {
    return (value << 3U) | (value >> 2U);
}

/** La console range ses couleurs en bleu-vert-rouge ; l'hôte les attend en ARGB. */
[[nodiscard]] constexpr std::uint32_t to_argb(std::uint32_t packed) noexcept {
    const auto red = expand_component(packed & 0x1fU);
    const auto green = expand_component((packed >> 5U) & 0x1fU);
    const auto blue = expand_component((packed >> 10U) & 0x1fU);
    return 0xff00'0000U | (red << 16U) | (green << 8U) | blue;
}

} // namespace

Engine2d::Engine2d(Engine engine, VideoMemory& video, std::span<const std::uint8_t> palette) noexcept
    : engine_(engine), video_(video), palette_(palette) {}

void Engine2d::reset() noexcept {
    display_control_ = 0;
    std::fill(background_control_.begin(), background_control_.end(), std::uint16_t{0});
    std::fill(scroll_x_.begin(), scroll_x_.end(), std::uint16_t{0});
    std::fill(scroll_y_.begin(), scroll_y_.end(), std::uint16_t{0});
    unimplemented_layers_ = 0;
    unimplemented_display_ = 0;
}

std::uint16_t Engine2d::background_control(std::size_t index) const noexcept {
    if (index >= background_count) return 0;
    return background_control_[index];
}

void Engine2d::set_background_control(std::size_t index, std::uint16_t value) noexcept {
    if (index >= background_count) return;
    background_control_[index] = value;
}

std::uint16_t Engine2d::scroll_x(std::size_t index) const noexcept {
    if (index >= background_count) return 0;
    return scroll_x_[index];
}

void Engine2d::set_scroll_x(std::size_t index, std::uint16_t value) noexcept {
    if (index >= background_count) return;
    scroll_x_[index] = value;
}

std::uint16_t Engine2d::scroll_y(std::size_t index) const noexcept {
    if (index >= background_count) return 0;
    return scroll_y_[index];
}

void Engine2d::set_scroll_y(std::size_t index, std::uint16_t value) noexcept {
    if (index >= background_count) return;
    scroll_y_[index] = value;
}

LayerKind Engine2d::layer_kind(std::size_t index) const noexcept {
    if (index >= background_count) return LayerKind::none;

    const auto mode = background_mode();
    // Le dernier mode n'existe pas sur le matériel, et le précédent appartient
    // au seul moteur principal.
    if (mode >= mode_layers.size()) return LayerKind::none;
    if (mode == mode_layers.size() - 1U && !has_main_extensions()) return LayerKind::none;

    const auto kind = mode_layers[mode][index];
    // Le premier plan cède la place au rendu 3D quand le moteur principal le
    // demande : le plan existe toujours, mais ce n'est plus un décor en tuiles.
    if (index == 0 && kind == LayerKind::text && has_main_extensions() &&
        (display_control_ & three_dimensional_layer) != 0U) {
        return LayerKind::three_dimensional;
    }
    return kind;
}

std::uint32_t Engine2d::palette_colour(std::uint32_t index) const noexcept {
    // Les deux moteurs se partagent les deux kilooctets de palette : deux tables
    // chacun, décor puis sprites.
    const auto base = has_main_extensions() ? 0U : 2U * palette_table_bytes;
    const auto offset = static_cast<std::size_t>(base + index * 2U);
    if (offset + 1U >= palette_.size()) return 0;

    const auto packed = static_cast<std::uint32_t>(palette_[offset]) |
        (static_cast<std::uint32_t>(palette_[offset + 1U]) << 8U);
    return to_argb(packed);
}

void Engine2d::render_text_row(
    std::size_t index,
    std::uint32_t row,
    std::span<Pixel> line
) noexcept {
    const auto control = static_cast<std::uint32_t>(background_control_[index]);
    const auto priority = static_cast<std::uint8_t>(control & 0x3U);
    const bool full_palette = (control & (1U << 7U)) != 0U;

    const auto size = (control >> 14U) & 0x3U;
    const std::uint32_t width = (size & 1U) != 0U ? 512U : 256U;
    const std::uint32_t height = (size & 2U) != 0U ? 512U : 256U;

    std::uint32_t character_base = ((control >> 2U) & 0xfU) * 0x4000U;
    std::uint32_t screen_base = ((control >> 8U) & 0x1fU) * 0x800U;
    if (has_main_extensions()) {
        // Deux champs de plus décalent toutes les bases du moteur principal,
        // seul moyen pour lui d'atteindre le haut de sa fenêtre.
        character_base += ((display_control_ >> 24U) & 0x7U) * 0x1'0000U;
        screen_base += ((display_control_ >> 27U) & 0x7U) * 0x1'0000U;
    }

    const auto y = (row + scroll_y_[index]) % height;
    const auto tile_row = (y % 256U) / 8U;
    const auto pixel_row = y % 8U;

    for (std::uint32_t screen_x = 0; screen_x < row_pixels; ++screen_x) {
        const auto x = (screen_x + scroll_x_[index]) % width;

        // La carte se découpe en blocs de 256 sur 256, rangés l'un après
        // l'autre. Le bloc du bas ne se trouve pas au même rang selon que la
        // carte est large ou non, et c'est là qu'une confusion ferait afficher
        // un quart de décor pour un autre.
        auto block = x >= 256U ? 1U : 0U;
        if (y >= 256U) block += width == 512U ? 2U : 1U;

        const auto tile_column = (x % 256U) / 8U;
        const auto entry = static_cast<std::uint32_t>(video_.read_background16(
            engine_,
            screen_base + block * 0x800U + (tile_row * 32U + tile_column) * 2U
        ));

        const auto tile = entry & 0x3ffU;
        const bool flip_x = (entry & (1U << 10U)) != 0U;
        const bool flip_y = (entry & (1U << 11U)) != 0U;
        const auto sub_palette = (entry >> 12U) & 0xfU;

        const auto column = x % 8U;
        const auto fine_x = flip_x ? 7U - column : column;
        const auto fine_y = flip_y ? 7U - pixel_row : pixel_row;

        std::uint32_t colour_index = 0;
        if (full_palette) {
            colour_index = video_.read_background(
                engine_, character_base + tile * 64U + fine_y * 8U + fine_x);
        } else {
            const auto packed = static_cast<std::uint32_t>(video_.read_background(
                engine_, character_base + tile * 32U + fine_y * 4U + fine_x / 2U));
            // Deux pixels par octet, le premier des deux dans les bits bas.
            const auto nibble = (fine_x & 1U) != 0U ? packed >> 4U : packed & 0xfU;
            // Une sous-palette ne déplace que les couleurs qui en sont : la
            // couleur zéro reste la transparence, et non la première de la
            // seizaine.
            if (nibble != 0U) colour_index = sub_palette * 16U + nibble;
        }

        if (colour_index == 0U) continue;

        auto& pixel = line[screen_x];
        // À priorité égale, le plan de plus petit numéro l'emporte, et il est
        // déjà passé : la comparaison doit donc être stricte.
        if (priority >= pixel.priority) continue;
        pixel = Pixel{palette_colour(colour_index), priority};
    }
}

void Engine2d::render_row(std::uint32_t row, std::span<std::int32_t> out) noexcept {
    if (out.size() < row_pixels) return;

    const auto write = [&out](std::uint32_t colour) {
        for (std::uint32_t x = 0; x < row_pixels; ++x) out[x] = static_cast<std::int32_t>(colour);
    };

    // Le blanc forcé n'éteint pas la mémoire : il coupe la sortie, et l'écran
    // devient blanc, non noir.
    if ((display_control_ & forced_blank) != 0U) {
        write(0xffff'ffffU);
        return;
    }

    const auto display_mode = (display_control_ >> 16U) & 0x3U;
    if (display_mode == 0U) {
        write(0xff00'0000U);
        return;
    }
    if (display_mode != 1U) {
        // Afficher une banque telle quelle, ou lire l'image depuis la mémoire
        // principale : deux modes qui n'appartiennent qu'au moteur principal et
        // qu'aucun code ne sert encore.
        ++unimplemented_display_;
        write(0xff00'0000U);
        return;
    }

    std::array<Pixel, row_pixels> line{};
    const Pixel backdrop{backdrop_colour(), backdrop_priority};
    std::fill(line.begin(), line.end(), backdrop);

    for (std::uint32_t index = 0; index < background_count; ++index) {
        if ((display_control_ & (1U << (first_background_enable + index))) == 0U) continue;

        const auto kind = layer_kind(index);
        // Un plan que le mode ne donne pas n'est pas un manque : le matériel n'en
        // affiche pas non plus, et le compter donnerait une fausse alerte.
        if (kind == LayerKind::none) continue;
        if (kind != LayerKind::text) {
            ++unimplemented_layers_;
            continue;
        }
        render_text_row(index, row, line);
    }

    for (std::uint32_t x = 0; x < row_pixels; ++x) {
        out[x] = static_cast<std::int32_t>(line[x].colour);
    }
}

} // namespace ravenemu::nds
