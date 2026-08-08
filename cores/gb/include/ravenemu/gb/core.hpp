#pragma once
#include <ravenemu/core.hpp>

namespace ravenemu::gb {
// The public GB factory remains the stable RavenEmu native contract.
inline std::unique_ptr<Core> make_core() { return make_game_boy_core(); }
}
