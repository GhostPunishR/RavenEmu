#include "../android/rewind.hpp"
#include <cstdint>
#include <iostream>
#include <random>
#include <stdexcept>
#include <vector>
using namespace ravenemu_android;
static void require(bool ok, const char* what) { if (!ok) throw std::runtime_error(what); }

// A "machine" of 640 KB whose frames change a few scattered bytes and a block.
static std::vector<std::uint8_t> frame(std::uint32_t n) {
    std::vector<std::uint8_t> state(640 * 1024, 0x5A);
    std::mt19937 random(n);
    for (int k = 0; k < 300; ++k) state[random() % state.size()] = static_cast<std::uint8_t>(random());
    for (std::size_t k = 0; k < 2048; ++k) state[0x8000 + k] = static_cast<std::uint8_t>(n + k);
    return state;
}

int main() {
    // The delta encoding: identical states give nothing, any change comes back.
    const auto a = frame(1), b = frame(2);
    require(encode_delta(a, a).empty(), "No change, no bytes");
    auto delta = encode_delta(a, b);
    require(delta.size() < 8 * 1024, "A few KB for a frame of a 640 KB state");
    auto c = a;
    require(apply_delta(c, delta) && c == b, "a XOR delta gives b");
    require(apply_delta(c, delta) && c == a, "and back: b XOR delta gives a");
    std::vector<std::uint8_t> edges(10, 0), changed(10, 0);
    changed[0] = 1; changed[9] = 2; changed[5] = 3;
    auto small = edges;
    require(apply_delta(small, encode_delta(edges, changed)) && small == changed, "Changes at both ends");
    std::vector<std::uint8_t> shorter(5, 0);
    require(!apply_delta(shorter, encode_delta(edges, changed)), "A delta beyond the state is refused");
    require(!apply_delta(small, {0x80}), "A broken delta is refused");

    // The buffer: frames come back newest first, exactly.
    RewindBuffer buffer(64u << 20);
    for (std::uint32_t n = 0; n < 120; ++n) buffer.push(frame(n));
    require(buffer.frames() == 119, "Every frame after the first is a delta");
    std::vector<std::uint8_t> state;
    for (std::uint32_t n = 119; n-- > 0;) {
        require(buffer.pop(state), "A frame to go back to");
        require(state == frame(n), "The frame before, exactly");
    }
    require(!buffer.pop(state) && state == frame(0), "Nothing before the first frame");
    // Pushing after going back continues from there.
    buffer.push(frame(500));
    require(buffer.pop(state) && state == frame(0), "Back from a new frame to where the rewind stopped");

    // The budget: the oldest frames go first.
    RewindBuffer tight(32 * 1024);
    for (std::uint32_t n = 0; n < 200; ++n) tight.push(frame(n));
    require(tight.bytes() <= 32 * 1024 && tight.frames() > 0 && tight.frames() < 199, "Within its budget");
    const auto kept = tight.frames();
    for (std::size_t k = 0; k < kept; ++k) require(tight.pop(state), "Every kept frame comes back");
    require(state == frame(static_cast<std::uint32_t>(199 - kept)), "The oldest kept is the right one");
    require(!tight.pop(state), "Then nothing");

    // Another state size (another machine) starts over.
    buffer.clear();
    buffer.push(frame(1));
    buffer.push(frame(2));
    buffer.push(std::vector<std::uint8_t>(100, 1));
    require(buffer.frames() == 0 && !buffer.pop(state), "A state of another size starts over");
    std::cout << "Rewind buffer passed\n";
}
