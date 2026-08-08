#pragma once
#include <ravenemu/core.hpp>

namespace ravenemu::gbc {
// Compatibility boundary: the existing GB engine switches to CGB hardware
// mode from the cartridge header. Keeping this factory mapped to the same core
// preserves ROM/library identity while allowing CGB organs to be extracted here
// incrementally under parity tests.
inline std::unique_ptr<Core> make_core() { return make_game_boy_core(); }
}
