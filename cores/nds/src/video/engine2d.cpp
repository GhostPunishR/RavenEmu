#include "video/engine2d.hpp"

#include <ravenemu/nds/core.hpp>

#include <algorithm>
#include <utility>

namespace ravenemu::nds {

namespace {

constexpr std::uint32_t row_pixels = static_cast<std::uint32_t>(screen_width);
/** Étendue de l'abscisse d'un sprite : neuf bits, soit le double de l'écran. */
constexpr std::uint32_t object_line_pixels = 512;

/** Bits de la commande d'affichage. */
constexpr std::uint32_t three_dimensional_layer = 1U << 3U;
constexpr std::uint32_t forced_blank = 1U << 7U;
constexpr std::uint32_t first_background_enable = 8U;

constexpr std::uint32_t object_enable = 1U << 12U;
/** Palettes étendues des décors : d'autres couleurs, pas une autre forme. */
constexpr std::uint32_t extended_palettes = 1U << 30U;
constexpr std::uint32_t object_mapping_linear = 1U << 4U;

/** Priorité du fond : derrière tout ce qu'un plan peut poser. */
constexpr std::uint8_t backdrop_priority = 4;
/** Marque un pixel qu'aucun sprite n'a couvert ; au-delà de toute priorité. */
constexpr std::uint8_t no_object_priority = 5;

/**
 * Les douze formats d'un sprite, en tuiles.
 *
 * Deux champs séparés les donnent, la forme et la taille, et le couple ne se
 * déduit ni de l'un ni de l'autre. La quatrième forme n'existe pas sur le
 * matériel : elle rend une étendue nulle, et le sprite n'est pas dessiné.
 */
struct ObjectShape {
    std::uint32_t width;
    std::uint32_t height;
};

constexpr std::array<std::array<ObjectShape, 4>, 4> object_shapes{{
    {{{8U, 8U}, {16U, 16U}, {32U, 32U}, {64U, 64U}}},   // carrée
    {{{16U, 8U}, {32U, 8U}, {32U, 16U}, {64U, 32U}}},   // couchée
    {{{8U, 16U}, {8U, 32U}, {16U, 32U}, {32U, 64U}}},   // debout
    {{{0U, 0U}, {0U, 0U}, {0U, 0U}, {0U, 0U}}},         // interdite
}};

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

Engine2d::Engine2d(
    Engine engine,
    VideoMemory& video,
    std::span<const std::uint8_t> palette,
    std::span<const std::uint8_t> objects
) noexcept
    : engine_(engine), video_(video), palette_(palette), objects_(objects) {}

void Engine2d::reset() noexcept {
    display_control_ = 0;
    std::fill(background_control_.begin(), background_control_.end(), std::uint16_t{0});
    std::fill(scroll_x_.begin(), scroll_x_.end(), std::uint16_t{0});
    std::fill(scroll_y_.begin(), scroll_y_.end(), std::uint16_t{0});
    affine_ = {};
    reference_x_ = {};
    reference_y_ = {};
    current_x_ = {};
    current_y_ = {};
    unimplemented_layers_ = 0;
    unimplemented_display_ = 0;
    unimplemented_objects_ = 0;
    unimplemented_palettes_ = 0;
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

std::uint32_t Engine2d::palette_colour(std::uint32_t index, PaletteTable table) const noexcept {
    // Les deux moteurs se partagent les deux kilooctets de palette : deux tables
    // chacun, décor puis sprites.
    auto base = has_main_extensions() ? 0U : 2U * palette_table_bytes;
    if (table == PaletteTable::object) base += palette_table_bytes;
    const auto offset = static_cast<std::size_t>(base + index * 2U);
    if (offset + 1U >= palette_.size()) return 0;

    const auto packed = static_cast<std::uint32_t>(palette_[offset]) |
        (static_cast<std::uint32_t>(palette_[offset + 1U]) << 8U);
    return to_argb(packed);
}

std::int16_t Engine2d::affine_parameter(std::size_t slot, std::size_t which) const noexcept {
    if (slot >= affine_layers || which >= affine_parameters) return 0;
    return affine_[slot][which];
}

void Engine2d::set_affine_parameter(
    std::size_t slot,
    std::size_t which,
    std::uint16_t value
) noexcept {
    if (slot >= affine_layers || which >= affine_parameters) return;
    affine_[slot][which] = static_cast<std::int16_t>(value);
}

/**
 * Étend un point de départ de vingt-huit bits à trente-deux.
 *
 * Le matériel n'en garde que vingt-huit, et le vingt-huitième porte le signe.
 * Le recopier sur les quatre bits du haut est ce qui fait qu'un décalage
 * vers la gauche reste un nombre négatif.
 */
namespace {
[[nodiscard]] constexpr std::int32_t sign_extend_reference(std::uint32_t value) noexcept {
    constexpr std::uint32_t significant = 0x0fff'ffffU;
    constexpr std::uint32_t sign = 0x0800'0000U;
    const auto kept = value & significant;
    return static_cast<std::int32_t>((kept ^ sign) - sign);
}
} // namespace

std::int32_t Engine2d::reference_x(std::size_t slot) const noexcept {
    if (slot >= affine_layers) return 0;
    return reference_x_[slot];
}

void Engine2d::set_reference_x(std::size_t slot, std::uint32_t value) noexcept {
    if (slot >= affine_layers) return;
    reference_x_[slot] = sign_extend_reference(value);
    // Le matériel saisit à l'écriture : un jeu qui déplace son décor au milieu
    // d'une image le voit bouger dès la ligne suivante, non à l'image d'après.
    current_x_[slot] = reference_x_[slot];
}

std::int32_t Engine2d::reference_y(std::size_t slot) const noexcept {
    if (slot >= affine_layers) return 0;
    return reference_y_[slot];
}

void Engine2d::set_reference_y(std::size_t slot, std::uint32_t value) noexcept {
    if (slot >= affine_layers) return;
    reference_y_[slot] = sign_extend_reference(value);
    current_y_[slot] = reference_y_[slot];
}

void Engine2d::latch_references() noexcept {
    current_x_ = reference_x_;
    current_y_ = reference_y_;
}

void Engine2d::advance_references() noexcept {
    for (std::size_t slot = 0; slot < affine_layers; ++slot) {
        // Une ligne plus bas, c'est un pas de la deuxième colonne de la matrice.
        current_x_[slot] += affine_[slot][1];
        current_y_[slot] += affine_[slot][3];
    }
}

bool Engine2d::describe_transformed(
    std::size_t index,
    LayerKind kind,
    TransformedSource& source
) noexcept {
    const auto control = static_cast<std::uint32_t>(background_control_[index]);
    source.priority = static_cast<std::uint8_t>(control & 0x3U);
    source.wrap = (control & (1U << 13U)) != 0U;
    const auto size = (control >> 14U) & 0x3U;

    std::uint32_t screen_base = ((control >> 8U) & 0x1fU) * 0x800U;
    std::uint32_t character_base = ((control >> 2U) & 0xfU) * 0x4000U;
    if (has_main_extensions()) {
        character_base += ((display_control_ >> 24U) & 0x7U) * 0x1'0000U;
        screen_base += ((display_control_ >> 27U) & 0x7U) * 0x1'0000U;
    }
    source.tile_base = character_base;

    if (kind == LayerKind::affine) {
        // Quatre tailles carrées, de cent vingt-huit à mille vingt-quatre
        // pixels de côté.
        source.width = 128U << size;
        source.height = source.width;
        source.base = screen_base;
        source.form = TransformedSource::Form::tile_bytes;
        return true;
    }

    if (kind != LayerKind::extended) return false;

    // Trois formes se partagent ce plan, et deux bits les séparent. Sans image,
    // c'est une carte de deux octets par tuile ; avec, la couleur est soit un
    // indice de palette soit une couleur écrite en toutes lettres.
    const bool bitmap = (control & (1U << 7U)) != 0U;
    if (!bitmap) {
        source.width = 128U << size;
        source.height = source.width;
        source.base = screen_base;
        source.form = TransformedSource::Form::tile_words;
        return true;
    }

    // Les quatre tailles d'image ne sont pas carrées, contrairement aux cartes.
    constexpr std::array<std::pair<std::uint32_t, std::uint32_t>, 4> bitmap_sizes{{
        {128U, 128U}, {256U, 256U}, {512U, 256U}, {512U, 512U},
    }};
    source.width = bitmap_sizes[size].first;
    source.height = bitmap_sizes[size].second;
    // Une image se range par blocs de seize kilooctets, non de deux : le champ
    // est le même, l'unité ne l'est pas, faute de quoi le champ ne pourrait pas
    // désigner le haut de la fenêtre.
    source.base = ((control >> 8U) & 0x1fU) * 0x4000U;
    source.form = (control & (1U << 2U)) != 0U
        ? TransformedSource::Form::bitmap_direct
        : TransformedSource::Form::bitmap_indexed;
    return true;
}

std::uint32_t Engine2d::sample_transformed(
    const TransformedSource& source,
    std::uint32_t x,
    std::uint32_t y
) noexcept {
    switch (source.form) {
    case TransformedSource::Form::tile_bytes: {
        const auto columns = source.width / 8U;
        const auto tile = static_cast<std::uint32_t>(
            video_.read_background(engine_, source.base + (y / 8U) * columns + x / 8U));
        const auto colour_index = static_cast<std::uint32_t>(video_.read_background(
            engine_, source.tile_base + tile * 64U + (y % 8U) * 8U + (x % 8U)));
        if (colour_index == 0U) return 0;
        return palette_colour(colour_index, PaletteTable::background);
    }
    case TransformedSource::Form::tile_words: {
        const auto columns = source.width / 8U;
        const auto entry = static_cast<std::uint32_t>(video_.read_background16(
            engine_, source.base + ((y / 8U) * columns + x / 8U) * 2U));
        const auto tile = entry & 0x3ffU;
        const auto fine_x = (entry & (1U << 10U)) != 0U ? 7U - (x % 8U) : x % 8U;
        const auto fine_y = (entry & (1U << 11U)) != 0U ? 7U - (y % 8U) : y % 8U;
        const auto colour_index = static_cast<std::uint32_t>(video_.read_background(
            engine_, source.tile_base + tile * 64U + fine_y * 8U + fine_x));
        if (colour_index == 0U) return 0;
        return palette_colour(colour_index, PaletteTable::background);
    }
    case TransformedSource::Form::bitmap_indexed: {
        const auto colour_index = static_cast<std::uint32_t>(
            video_.read_background(engine_, source.base + y * source.width + x));
        if (colour_index == 0U) return 0;
        return palette_colour(colour_index, PaletteTable::background);
    }
    case TransformedSource::Form::bitmap_direct: {
        const auto packed = static_cast<std::uint32_t>(video_.read_background16(
            engine_, source.base + (y * source.width + x) * 2U));
        // Le bit du haut n'est pas une couleur : il dit si le point existe.
        if ((packed & 0x8000U) == 0U) return 0;
        return to_argb(packed);
    }
    }
    return 0;
}

void Engine2d::render_transformed_row(
    std::size_t index,
    const TransformedSource& source,
    std::span<Pixel> line
) noexcept {
    const auto slot = index - first_affine_layer;
    // Un pas vers la droite sur l'écran est un pas de la première colonne de la
    // matrice ; le point de départ de la ligne porte déjà les lignes passées.
    const auto step_x = static_cast<std::int32_t>(affine_[slot][0]);
    const auto step_y = static_cast<std::int32_t>(affine_[slot][2]);
    auto position_x = current_x_[slot];
    auto position_y = current_y_[slot];

    for (std::uint32_t screen_x = 0; screen_x < row_pixels; ++screen_x) {
        // Huit bits de fraction : le point de l'image est ce qui reste une fois
        // la fraction retirée, et non la valeur arrondie.
        const auto sample_x = position_x >> 8;
        const auto sample_y = position_y >> 8;
        position_x += step_x;
        position_y += step_y;

        std::uint32_t x = 0;
        std::uint32_t y = 0;
        if (source.wrap) {
            // Les étendues sont des puissances de deux : le reste se prend au
            // masque, ce qui traite les positions négatives sans les distinguer.
            x = static_cast<std::uint32_t>(sample_x) & (source.width - 1U);
            y = static_cast<std::uint32_t>(sample_y) & (source.height - 1U);
        } else {
            if (sample_x < 0 || sample_y < 0) continue;
            x = static_cast<std::uint32_t>(sample_x);
            y = static_cast<std::uint32_t>(sample_y);
            if (x >= source.width || y >= source.height) continue;
        }

        const auto colour = sample_transformed(source, x, y);
        if (colour == 0U) continue;

        auto& pixel = line[screen_x];
        if (source.priority >= pixel.priority) continue;
        pixel = Pixel{colour, source.priority};
    }
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
        pixel = Pixel{palette_colour(colour_index, PaletteTable::background), priority};
    }
}

void Engine2d::render_object(std::size_t index, std::uint32_t row, std::span<Pixel> line) noexcept {
    // Les deux moteurs se partagent les deux kilooctets d'attributs, une table
    // chacun, comme ils se partagent la palette.
    const auto base = has_main_extensions() ? 0U : object_table_bytes;
    const auto entry = static_cast<std::size_t>(base) + index * 8U;
    if (entry + 6U >= objects_.size()) return;

    const auto attribute = [this, entry](std::size_t which) {
        const auto at = entry + which * 2U;
        return static_cast<std::uint32_t>(objects_[at]) |
            (static_cast<std::uint32_t>(objects_[at + 1U]) << 8U);
    };

    const auto first = attribute(0);
    const auto second = attribute(1);
    const auto third = attribute(2);

    const bool rotated = (first & (1U << 8U)) != 0U;
    if (rotated) {
        // Un sprite tournant dessiné comme un sprite ordinaire donnerait une
        // image plausible et fausse : mieux vaut ne rien poser et le dire.
        ++unimplemented_objects_;
        return;
    }
    // Sans rotation, le second bit éteint le sprite. C'est un état ordinaire,
    // et non un manque : la plupart des sprites d'un jeu sont éteints.
    if ((first & (1U << 9U)) != 0U) return;

    const auto mode = (first >> 10U) & 0x3U;
    if (mode != 0U) {
        // Semi-transparence, fenêtre par sprite, image directe : trois formes
        // dont aucune ne se dessine comme un sprite ordinaire.
        ++unimplemented_objects_;
        return;
    }
    // La mosaïque, elle, ne change pas la nature du sprite : le dessiner sans
    // elle vaut mieux que de ne pas le dessiner du tout.
    if ((first & (1U << 12U)) != 0U) ++unimplemented_objects_;

    const auto shape = object_shapes[(first >> 14U) & 0x3U][(second >> 14U) & 0x3U];

    // La ligne se compte modulo 256 : un sprite posé bas reparaît en haut, et
    // c'est ainsi qu'on le fait entrer par le bord. La forme interdite n'a pas
    // besoin d'être écartée à part : son étendue est nulle, donc aucune ligne
    // n'est jamais dedans, et une garde de plus ne serait jamais exercée.
    const auto top = first & 0xffU;
    const auto row_in_object = (row - top) & 0xffU;
    if (row_in_object >= shape.height) return;

    const bool full_palette = (first & (1U << 13U)) != 0U;
    const bool flip_x = (second & (1U << 12U)) != 0U;
    const bool flip_y = (second & (1U << 13U)) != 0U;

    const auto left = second & 0x1ffU;
    const auto priority = static_cast<std::uint8_t>((third >> 10U) & 0x3U);
    const auto sub_palette = (third >> 12U) & 0xfU;
    const auto base_tile = third & 0x3ffU;

    const auto bytes_per_tile = full_palette ? 64U : 32U;
    const bool linear = (display_control_ & object_mapping_linear) != 0U;
    // Rangés à la suite, les sprites partent d'un pas que le moteur principal
    // règle ; rangés en grille, ils partent toujours de trente-deux octets.
    const auto granularity = linear && has_main_extensions()
        ? 32U << ((display_control_ >> 20U) & 0x3U)
        : 32U;
    const auto tiles_across = shape.width / 8U;

    const auto object_row = flip_y ? shape.height - 1U - row_in_object : row_in_object;

    for (std::uint32_t column = 0; column < shape.width; ++column) {
        // L'abscisse tient sur neuf bits et se replie : un sprite posé très à
        // droite entre par la gauche. Ce qui reste au-delà de l'écran est déposé
        // tout de même, dans la partie du tampon que la composition ne relit
        // pas : découper ici demanderait une condition qu'aucune image ne
        // permettrait de vérifier, puisqu'un pixel hors écran ne se voit nulle
        // part. La largeur du tampon fait le découpage à sa place.
        const auto screen_x = (left + column) & 0x1ffU;

        const auto object_column = flip_x ? shape.width - 1U - column : column;

        const auto tile_column = object_column / 8U;
        const auto tile_row = object_row / 8U;
        const auto address = linear
            ? base_tile * granularity + (tile_row * tiles_across + tile_column) * bytes_per_tile
            // En grille, la zone fait trente-deux emplacements de large, et une
            // tuile en deux cent cinquante-six couleurs en occupe deux.
            : (base_tile + tile_row * 32U + tile_column * (full_palette ? 2U : 1U)) * 32U;

        const auto fine_x = object_column % 8U;
        const auto fine_y = object_row % 8U;

        std::uint32_t colour_index = 0;
        if (full_palette) {
            colour_index = video_.read_object(engine_, address + fine_y * 8U + fine_x);
        } else {
            const auto packed = static_cast<std::uint32_t>(
                video_.read_object(engine_, address + fine_y * 4U + fine_x / 2U));
            const auto nibble = (fine_x & 1U) != 0U ? packed >> 4U : packed & 0xfU;
            if (nibble != 0U) colour_index = sub_palette * 16U + nibble;
        }

        if (colour_index == 0U) continue;

        auto& pixel = line[screen_x];
        // Entre sprites, le premier de la table l'emporte à priorité égale, et
        // il est déjà passé : la comparaison est stricte, comme entre plans.
        if (priority >= pixel.priority) continue;
        pixel = Pixel{palette_colour(colour_index, PaletteTable::object), priority};
    }
}

void Engine2d::render_object_row(std::uint32_t row, std::span<Pixel> line) noexcept {
    for (std::size_t index = 0; index < object_count; ++index) {
        render_object(index, row, line);
    }
}

void Engine2d::render_row(std::uint32_t row, std::span<std::int32_t> out) noexcept {
    if (out.size() < row_pixels) return;

    // Les points de départ des plans tournants sont saisis au premier trait et
    // avancent à chaque ligne, **que la ligne soit dessinée ou non** : un écran
    // éteint ne gèle pas une rotation, il la cache. C'est pourquoi la saisie et
    // l'avance encadrent tout le reste plutôt que d'accompagner le rendu.
    if (row == 0) latch_references();
    struct AdvanceOnExit {
        Engine2d& engine;
        ~AdvanceOnExit() { engine.advance_references(); }
    } advance_on_exit{*this};

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
        if (kind == LayerKind::text) {
            render_text_row(index, row, line);
            continue;
        }

        TransformedSource source{};
        if (!describe_transformed(index, kind, source)) {
            ++unimplemented_layers_;
            continue;
        }
        // Une palette étendue change les couleurs, pas la forme : le plan est
        // dessiné, mais avec la palette ordinaire. C'est compté à part, parce
        // qu'une image aux teintes fausses ne se cherche pas au même endroit
        // qu'une image absente.
        if (source.form == TransformedSource::Form::tile_words &&
            (display_control_ & extended_palettes) != 0U) {
            ++unimplemented_palettes_;
        }
        render_transformed_row(index, source, line);
    }

    if ((display_control_ & object_enable) != 0U) {
        // Le tampon des sprites couvre toute l'étendue de l'abscisse, non la
        // seule largeur de l'écran : un sprite posé au-delà du bord y dépose ce
        // qui dépasse, et la composition qui suit ne relit que l'écran.
        std::array<Pixel, object_line_pixels> objects{};
        std::fill(objects.begin(), objects.end(), Pixel{0, no_object_priority});
        render_object_row(row, objects);

        // **Un sprite passe devant un décor de même priorité.** C'est l'inverse
        // de la règle qui vaut entre décors, où c'est le plus petit numéro qui
        // l'emporte, et c'est ce qui met un personnage devant son sol plutôt que
        // dedans. La comparaison est donc large ici, et stricte là-bas.
        for (std::uint32_t x = 0; x < row_pixels; ++x) {
            if (objects[x].priority > line[x].priority) continue;
            line[x] = objects[x];
        }
    }

    for (std::uint32_t x = 0; x < row_pixels; ++x) {
        out[x] = static_cast<std::int32_t>(line[x].colour);
    }
}

} // namespace ravenemu::nds
