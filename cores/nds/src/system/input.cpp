#include "system/input.hpp"

namespace ravenemu::nds {

void InputState::set_pressed(std::uint16_t keys, bool pressed) noexcept {
    const auto selected = static_cast<std::uint16_t>(keys & key_mask);
    held_ = static_cast<std::uint16_t>(pressed ? (held_ | selected) : (held_ & ~selected));
}

void InputState::set_extra_pressed(std::uint16_t keys, bool pressed) noexcept {
    const auto selected = static_cast<std::uint16_t>(keys & extra_mask);
    extra_held_ =
        static_cast<std::uint16_t>(pressed ? (extra_held_ | selected) : (extra_held_ & ~selected));
}

bool KeyInterrupt::satisfied(std::uint16_t held) const noexcept {
    if ((control_ & enable) == 0U) return false;

    const auto selected = static_cast<std::uint16_t>(control_ & mask_field);
    // Une sélection vide ne réveille jamais : sans elle, la condition « toutes »
    // serait satisfaite par n'importe quel état, touches relâchées comprises.
    if (selected == 0U) return false;

    const auto pressed = static_cast<std::uint16_t>(held & selected);
    if ((control_ & requires_all) != 0U) return pressed == selected;
    return pressed != 0U;
}

} // namespace ravenemu::nds
