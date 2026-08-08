#include <ravenemu/gbc/core.hpp>
#include <cstdlib>

int main() {
    auto core = ravenemu::gbc::make_core();
    return core ? EXIT_SUCCESS : EXIT_FAILURE;
}
