#pragma once

#include "system/interrupt_controller.hpp"
#include "video/display_controller.hpp"
#include "video/engine2d.hpp"
#include "video/video_memory.hpp"

#include <cstdint>
#include <span>
#include <vector>

namespace ravenemu::nds {

/**
 * Le matériel vidéo, que les deux processeurs se partagent.
 *
 * Il vivait dans la carte du processeur principal, ce qui suffisait tant que lui
 * seul y touchait. Le contrôleur d'affichage change cela : le processeur
 * secondaire lit l'état du balayage et le compteur de lignes, et se fait
 * réveiller par eux. Laisser ce matériel dans la carte de l'autre aurait obligé
 * une carte à dépendre de sa jumelle, alors qu'elles sont paires.
 *
 * C'est la même décision que pour `SystemMemory`, et pour la même raison : ce
 * que deux processeurs partagent ne peut pas appartenir à l'un d'eux.
 */
class VideoSystem {
public:
    VideoSystem(
        InterruptController& main_interrupts,
        InterruptController& secondary_interrupts
    );

    /** Étendue de la palette, les deux moteurs comprises. */
    static constexpr std::uint32_t palette_bytes = 2U * 1024U;
    /** Étendue de la mémoire d'objets, les deux moteurs comprises. */
    static constexpr std::uint32_t object_attribute_bytes = 2U * 1024U;

    void reset() noexcept;

    [[nodiscard]] VideoMemory& memory() noexcept { return memory_; }
    [[nodiscard]] std::span<std::uint8_t> palette() noexcept { return palette_; }
    [[nodiscard]] std::span<std::uint8_t> object_attributes() noexcept { return objects_; }

    [[nodiscard]] Engine2d& engine(Engine which) noexcept {
        return which == Engine::main ? main_engine_ : secondary_engine_;
    }
    // Les mêmes accès en lecture seule : un relevé consulte la console sans
    // avoir le droit de la changer, et le type le dit plutôt que la coutume.
    [[nodiscard]] const Engine2d& engine(Engine which) const noexcept {
        return which == Engine::main ? main_engine_ : secondary_engine_;
    }
    [[nodiscard]] DisplayController& display() noexcept { return display_; }
    [[nodiscard]] const DisplayController& display() const noexcept { return display_; }

private:
    // L'ordre compte : la palette et les attributs précèdent les moteurs, qui
    // s'y adossent, et les moteurs précèdent le contrôleur, qui les pilote.
    VideoMemory memory_{};
    std::vector<std::uint8_t> palette_;
    std::vector<std::uint8_t> objects_;
    Engine2d main_engine_;
    Engine2d secondary_engine_;
    DisplayController display_;
};

} // namespace ravenemu::nds
