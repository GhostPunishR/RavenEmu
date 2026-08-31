#include "video/display_controller.hpp"

#include <ravenemu/nds/core.hpp>

namespace ravenemu::nds {

namespace {

/**
 * Bits du registre d'état que le logiciel peut écrire.
 *
 * Les trois indicateurs n'en sont pas : ils décrivent le balayage, et le
 * matériel les impose. Un émulateur qui les laisserait écrire donnerait à un jeu
 * le pouvoir de se mentir sur la position du faisceau.
 */
constexpr std::uint16_t writable_bits =
    DisplayController::vertical_blank_interrupt |
    DisplayController::horizontal_blank_interrupt |
    DisplayController::line_match_interrupt |
    DisplayController::line_match_high_bit |
    0xff00U;

constexpr std::uint32_t row_pixels = static_cast<std::uint32_t>(screen_width);
constexpr std::uint32_t bottom_row = static_cast<std::uint32_t>(bottom_screen_first_row);
constexpr std::uint32_t framebuffer_pixels =
    row_pixels * static_cast<std::uint32_t>(framebuffer_height);

} // namespace

DisplayController::DisplayController(
    Engine2d& main,
    Engine2d& secondary,
    InterruptController& main_interrupts,
    InterruptController& secondary_interrupts
) noexcept
    : main_engine_(main), secondary_engine_(secondary),
      main_interrupts_(main_interrupts), secondary_interrupts_(secondary_interrupts) {}

void DisplayController::reset() noexcept {
    line_ = 0;
    swapped_ = false;
    main_ = Side{};
    secondary_ = Side{};
}

DisplayController::Side& DisplayController::side_of(Processor side) noexcept {
    return side == Processor::main ? main_ : secondary_;
}

const DisplayController::Side& DisplayController::side_of(Processor side) const noexcept {
    return side == Processor::main ? main_ : secondary_;
}

InterruptController& DisplayController::interrupts_of(Processor side) noexcept {
    return side == Processor::main ? main_interrupts_ : secondary_interrupts_;
}

std::uint32_t DisplayController::watched_line(Processor side) const noexcept {
    const auto written = static_cast<std::uint32_t>(side_of(side).written);
    // Les huit bits bas de la ligne guettée sont rangés en haut du registre, et
    // le neuvième est allé se loger tout seul plus bas. Les recoller à l'envers
    // ferait guetter une ligne pour une autre, sans que rien ne le signale.
    return ((written >> 8U) & 0xffU) | (((written >> 7U) & 1U) << 8U);
}

std::uint16_t DisplayController::status(Processor side) const noexcept {
    // Le tampon ne contient déjà que des bits écrivables : l'écriture les a
    // filtrés. Le refiltrer ici ne changerait rien et ne pourrait pas être
    // éprouvé.
    auto value = side_of(side).written;

    // La toute dernière ligne n'est pas comptée comme retour vertical : le
    // balayage y repart déjà, et un logiciel qui scrute cet indicateur le voit
    // retomber une ligne avant la fin.
    if (line_ >= visible_lines && line_ != total_lines - 1U) {
        value = static_cast<std::uint16_t>(value | vertical_blank_flag);
    }

    // Le retour horizontal n'a pas d'indicateur ici, et c'est un choix : ce
    // modèle avance ligne par ligne, si bien que toute lecture se fait à une
    // frontière de ligne, où le faisceau n'est pas en retour horizontal.
    // Prétendre le contraire serait inventer une position dans la ligne.

    if (line_ == watched_line(side)) {
        value = static_cast<std::uint16_t>(value | line_match_flag);
    }
    return value;
}

void DisplayController::set_status(Processor side, std::uint16_t value) noexcept {
    side_of(side).written = static_cast<std::uint16_t>(value & writable_bits);
}

void DisplayController::raise_for_both(std::uint16_t enable, std::uint32_t source) noexcept {
    for (const auto side : {Processor::main, Processor::secondary}) {
        if ((side_of(side).written & enable) == 0U) continue;
        interrupts_of(side).request(source);
    }
}

void DisplayController::advance_line() noexcept {
    // Le retour horizontal appartient à la ligne qui s'achève, et il a lieu sur
    // toutes les lignes, y compris celles qu'on n'affiche pas : le faisceau les
    // balaie tout de même. À cette granularité, sa place avant ou après le
    // changement de ligne ne s'observe pas, puisqu'il ne dépend pas de la ligne.
    raise_for_both(horizontal_blank_interrupt, InterruptController::horizontal_blank);

    line_ = (line_ + 1U) % total_lines;

    // Le retour vertical se pose une fois, au passage sur la première ligne qui
    // ne s'affiche plus, et non à chacune des lignes qui suivent.
    if (line_ == visible_lines) {
        raise_for_both(vertical_blank_interrupt, InterruptController::vertical_blank);
    }

    // La ligne guettée est propre à chaque processeur : ils n'attendent pas
    // forcément la même.
    for (const auto side : {Processor::main, Processor::secondary}) {
        if (line_ != watched_line(side)) continue;
        if ((side_of(side).written & line_match_interrupt) == 0U) continue;
        interrupts_of(side).request(InterruptController::line_match);
    }
}

void DisplayController::render_row_into(
    std::uint32_t row,
    std::span<std::int32_t> framebuffer
) noexcept {
    if (framebuffer.size() < framebuffer_pixels) return;

    // Quel moteur alimente quel écran est décidé par le matériel, non par une
    // convention de ce code.
    auto& top = swapped_ ? secondary_engine_ : main_engine_;
    auto& bottom = swapped_ ? main_engine_ : secondary_engine_;

    top.render_row(row, framebuffer.subspan(row * row_pixels, row_pixels));
    bottom.render_row(row, framebuffer.subspan((bottom_row + row) * row_pixels, row_pixels));
}

void DisplayController::render_current_line(std::span<std::int32_t> framebuffer) noexcept {
    // Les lignes qui ne s'affichent pas se balaient tout de même : elles ne se
    // dessinent simplement nulle part.
    if (line_ >= visible_lines) return;
    render_row_into(line_, framebuffer);
}

void DisplayController::render_frame(std::span<std::int32_t> framebuffer) noexcept {
    for (std::uint32_t row = 0; row < visible_lines; ++row) render_row_into(row, framebuffer);
}

} // namespace ravenemu::nds
