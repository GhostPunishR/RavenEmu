#include "video/video_system.hpp"

#include <algorithm>

namespace ravenemu::nds {

VideoSystem::VideoSystem(
    InterruptController& main_interrupts,
    InterruptController& secondary_interrupts
)
    : palette_(palette_bytes, 0),
      objects_(object_attribute_bytes, 0),
      main_engine_(Engine::main, memory_, palette_, objects_),
      secondary_engine_(Engine::secondary, memory_, palette_, objects_),
      display_(main_engine_, secondary_engine_, main_interrupts, secondary_interrupts) {}

void VideoSystem::reset() noexcept {
    memory_.reset();
    std::fill(palette_.begin(), palette_.end(), std::uint8_t{0});
    std::fill(objects_.begin(), objects_.end(), std::uint8_t{0});
    main_engine_.reset();
    secondary_engine_.reset();
    display_.reset();
}

} // namespace ravenemu::nds
