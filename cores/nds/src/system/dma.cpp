#include "system/dma.hpp"

namespace ravenemu::nds {

namespace {

/**
 * Étendue du compteur d'unités, qui n'est pas la même des deux côtés.
 *
 * Le processeur principal compte sur vingt et un bits, le secondaire sur seize.
 * Un compte nul vaut l'étendue entière, et c'est ainsi qu'on demande le plus
 * long transfert possible.
 */
constexpr std::uint32_t main_unit_mask = 0x001f'ffff;
constexpr std::uint32_t secondary_unit_mask = 0x0000'ffff;

/** Source d'interruption du canal de rang [channel]. */
[[nodiscard]] constexpr std::uint32_t interrupt_source(std::size_t channel) noexcept {
    return InterruptController::dma_0 << channel;
}

/**
 * Pas d'une adresse entre deux unités, selon sa façon d'évoluer.
 *
 * Le pas est rendu non signé, la décroissance étant portée par le repli du
 * complément à deux : une adresse est un motif de trente-deux bits et non un
 * nombre relatif, et la faire passer par un type signé n'ajouterait qu'une
 * conversion à surveiller.
 */
[[nodiscard]] constexpr std::uint32_t step_of(
    DmaController::AddressMode mode,
    std::uint32_t unit
) noexcept {
    switch (mode) {
    case DmaController::AddressMode::decrement:
        return 0U - unit;
    case DmaController::AddressMode::fixed:
        return 0;
    default:
        return unit;
    }
}

} // namespace

void DmaController::reset() noexcept {
    channels_ = {};
    pending_ = 0;
    unsupported_timings_ = 0;
}

std::uint32_t DmaController::source(std::size_t channel) const noexcept {
    return channels_[channel].source;
}

void DmaController::set_source(std::size_t channel, std::uint32_t value) noexcept {
    channels_[channel].source = value;
}

std::uint32_t DmaController::destination(std::size_t channel) const noexcept {
    return channels_[channel].destination;
}

void DmaController::set_destination(std::size_t channel, std::uint32_t value) noexcept {
    channels_[channel].destination = value;
}

std::uint32_t DmaController::control(std::size_t channel) const noexcept {
    return channels_[channel].control;
}

std::uint16_t DmaController::command(std::size_t channel) const noexcept {
    return static_cast<std::uint16_t>(channels_[channel].control >> 16U);
}

DmaController::Timing DmaController::timing(std::size_t channel) const noexcept {
    // Le champ n'a pas la même largeur des deux côtés : le processeur principal
    // dispose de huit moments, le secondaire de quatre.
    const auto field = serves_main()
        ? (command(channel) >> 11U) & 0x7U
        : (command(channel) >> 12U) & 0x3U;

    if (field == 0U) return Timing::immediate;
    if (field == 1U) return Timing::vertical_blank;
    // Le retour horizontal n'est un moment que pour le processeur principal :
    // chez le secondaire, la même valeur désigne la cartouche.
    if (field == 2U && serves_main()) return Timing::horizontal_blank;
    return Timing::unsupported;
}

std::uint32_t DmaController::units(std::size_t channel) const noexcept {
    const auto mask = serves_main() ? main_unit_mask : secondary_unit_mask;
    const auto written = channels_[channel].control & mask;
    // Un compte nul demande l'étendue entière, et non l'absence de transfert.
    return written != 0U ? written : mask + 1U;
}

DmaController::AddressMode DmaController::source_mode(std::size_t channel) const noexcept {
    return static_cast<AddressMode>((command(channel) >> source_mode_shift) & mode_mask);
}

DmaController::AddressMode DmaController::destination_mode(std::size_t channel) const noexcept {
    return static_cast<AddressMode>((command(channel) >> destination_mode_shift) & mode_mask);
}

void DmaController::set_control(std::size_t channel, std::uint32_t value) noexcept {
    auto& state = channels_[channel];
    const bool was_enabled = (state.control & (static_cast<std::uint32_t>(enable) << 16U)) != 0U;
    state.control = value;
    const bool now_enabled = (state.control & (static_cast<std::uint32_t>(enable) << 16U)) != 0U;

    // L'allumage est un front : c'est lui qui fige les adresses de départ et,
    // pour un départ immédiat, arme le canal. Réécrire la commande sur un canal
    // déjà allumé ne relance rien.
    if (was_enabled || !now_enabled) return;

    state.cursor_source = state.source;
    state.cursor_destination = state.destination;

    // Un canal armé sur un moment dont l'organe n'existe pas est compté ici, au
    // moment où il le demande : attendre le moment lui-même ne compterait jamais
    // rien, puisque personne ne le produit. Les deux cas s'excluent, et aucun
    // n'a donc à couper court à l'autre.
    const auto moment = timing(channel);
    if (moment == Timing::unsupported) ++unsupported_timings_;
    if (moment == Timing::immediate) arm(channel);
}

void DmaController::arm(std::size_t channel) noexcept {
    pending_ |= 1U << channel;
}

void DmaController::trigger(Timing moment) noexcept {
    for (std::size_t channel = 0; channel < count; ++channel) {
        if ((command(channel) & enable) == 0U) continue;
        if (timing(channel) != moment) continue;
        arm(channel);
    }
}

void DmaController::transfer(std::size_t channel, Bus& memory) noexcept {
    auto& state = channels_[channel];

    const bool words = (command(channel) & transfers_words) != 0U;
    const std::uint32_t unit = words ? 4U : 2U;
    const auto source_step = step_of(source_mode(channel), unit);
    const auto destination_step = step_of(destination_mode(channel), unit);
    const auto total = units(channel);

    for (std::uint32_t index = 0; index < total; ++index) {
        if (words) {
            memory.write32(state.cursor_destination, memory.read32(state.cursor_source));
        } else {
            memory.write16(state.cursor_destination, memory.read16(state.cursor_source));
        }
        state.cursor_source += source_step;
        state.cursor_destination += destination_step;
    }

    if ((command(channel) & interrupt_enable) != 0U) {
        interrupts_.request(interrupt_source(channel));
    }

    // La répétition n'a de sens qu'avec un moment : un départ immédiat qui se
    // répéterait tournerait sans fin, et le matériel ne le fait pas.
    const bool repeating =
        (command(channel) & repeats) != 0U && timing(channel) != Timing::immediate;
    if (!repeating) {
        state.control &= ~(static_cast<std::uint32_t>(enable) << 16U);
        return;
    }

    // Une arrivée en reprise repart de son adresse d'origine à chaque tour ;
    // les autres continuent là où elles en sont.
    if (destination_mode(channel) == AddressMode::increment_and_reload) {
        state.cursor_destination = state.destination;
    }
}

void DmaController::run(Bus& memory) noexcept {
    for (std::size_t channel = 0; channel < count; ++channel) {
        if ((pending_ & (1U << channel)) == 0U) continue;
        pending_ &= ~(1U << channel);
        transfer(channel, memory);
    }
}

} // namespace ravenemu::nds
