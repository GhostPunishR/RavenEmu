#pragma once
#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>
namespace ravenemu_android {
// The last moments of a game, to play it backwards. Each frame's state is kept
// as what changed since the one before (the two XORed, runs of zeros skipped),
// so a GBA state of 640 KB costs a few KB a frame. Past the memory budget, the
// oldest frames go first. Same scheme as RavenEmu PC.
class RewindBuffer {
public:
    explicit RewindBuffer(std::size_t budget_bytes) : budget_(budget_bytes) {}
    // The state after a frame. One of another size starts the buffer over.
    void push(const std::vector<std::uint8_t>& state);
    // Steps back one frame: `state` becomes the one before the last pushed or
    // popped. False, `state` untouched, when no older one is left.
    bool pop(std::vector<std::uint8_t>& state);
    void clear();
    std::size_t frames() const { return deltas_.size(); }
    std::size_t bytes() const { return used_; }
private:
    std::size_t budget_;
    std::size_t used_ = 0;
    std::vector<std::uint8_t> top_;                // the latest state
    std::deque<std::vector<std::uint8_t>> deltas_; // each: top_ at its time XOR the one before, encoded
};
// The encoding of an XOR of two states: (zeros, literal length, literal bytes)
// groups, lengths as LEB128. Exposed for the tests.
std::vector<std::uint8_t> encode_delta(const std::vector<std::uint8_t>& from, const std::vector<std::uint8_t>& to);
// XORs an encoded delta into `state`, which must have the size it was made for.
bool apply_delta(std::vector<std::uint8_t>& state, const std::vector<std::uint8_t>& delta);
} // namespace ravenemu_android
