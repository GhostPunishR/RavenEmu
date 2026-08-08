#include <ravenemu/gbc/core.hpp>

namespace ravenemu::gbc {
std::unique_ptr<Core> make_core() {
    return make_game_boy_core();
}
} // namespace ravenemu::gbc
