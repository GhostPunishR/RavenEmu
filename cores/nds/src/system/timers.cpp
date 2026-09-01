#include "system/timers.hpp"

namespace ravenemu::nds {

namespace {

/** Étendue d'un compteur de seize bits. */
constexpr std::uint32_t period = 0x1'0000;

/** Source d'interruption de la minuterie de rang [index]. */
[[nodiscard]] constexpr std::uint32_t interrupt_source(std::size_t index) noexcept {
    return InterruptController::timer_0 << index;
}

} // namespace

void Timers::reset() noexcept {
    timers_ = {};
}

std::uint16_t Timers::counter(std::size_t index) const noexcept {
    return timers_[index].counter;
}

std::uint16_t Timers::reload(std::size_t index) const noexcept {
    return timers_[index].reload;
}

std::uint16_t Timers::control(std::size_t index) const noexcept {
    return timers_[index].control;
}

std::uint32_t Timers::overflow_count(std::size_t index) const noexcept {
    return timers_[index].overflows;
}

void Timers::set_reload(std::size_t index, std::uint16_t value) noexcept {
    timers_[index].reload = value;
}

void Timers::set_control(std::size_t index, std::uint16_t value) noexcept {
    auto& timer = timers_[index];
    const auto written = static_cast<std::uint16_t>(value & writable_bits);

    // L'allumage est un front : réécrire le bit sur une minuterie déjà allumée
    // ne recharge pas son compteur, et un jeu qui règle son diviseur en cours de
    // route ne verrait pas son temps repartir de zéro.
    const bool was_enabled = (timer.control & enable) != 0U;
    const bool now_enabled = (written & enable) != 0U;
    // L'allumage reprend le rechargement, mais ne remet pas la phase du
    // diviseur à zéro : rien ici ne dit qu'il le ferait, et la remettre serait
    // une affirmation que rien ne vérifie. L'écart possible vaut au plus une
    // période de diviseur, soit moins d'un pas de minuterie.
    if (!was_enabled && now_enabled) timer.counter = timer.reload;

    timer.control = written;
}

std::uint32_t Timers::add_ticks(Timer& timer, std::uint32_t ticks) noexcept {
    const std::uint32_t total = timer.counter + ticks;
    if (total < period) {
        timer.counter = static_cast<std::uint16_t>(total);
        return 0;
    }

    // Après le premier débordement, le compteur repart du rechargement : les
    // débordements suivants sont espacés de cette période-là, non de l'étendue
    // entière du compteur.
    const std::uint32_t span = period - timer.reload;
    const std::uint32_t excess = total - period;
    timer.counter = static_cast<std::uint16_t>(timer.reload + excess % span);
    return 1U + excess / span;
}

void Timers::advance(std::uint32_t cycles) noexcept {
    std::uint32_t carried = 0;

    for (std::size_t index = 0; index < count; ++index) {
        auto& timer = timers_[index];
        const auto previous = carried;
        carried = 0;
        timer.overflows = 0;

        if ((timer.control & enable) == 0U) continue;

        // La première minuterie n'a pas de prédécesseur : le bit d'enchaînement
        // ne peut rien commander chez elle, et reste sans effet.
        const bool chained = index > 0 && (timer.control & cascade) != 0U;

        std::uint32_t ticks = 0;
        if (chained) {
            ticks = previous;
        } else {
            const auto divider = prescalers[timer.control & prescaler_mask];
            const auto available = timer.remainder + cycles;
            ticks = available / divider;
            timer.remainder = available % divider;
        }

        timer.overflows = add_ticks(timer, ticks);
        carried = timer.overflows;

        if (timer.overflows != 0U && (timer.control & interrupt_enable) != 0U) {
            interrupts_.request(interrupt_source(index));
        }
    }
}

} // namespace ravenemu::nds
