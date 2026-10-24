#include "rewind.hpp"
#include <algorithm>
namespace ravenemu_android {
namespace {
// A literal run ends at this many zero bytes: shorter gaps cost less inline.
constexpr std::size_t zero_gap = 4;
void put(std::vector<std::uint8_t>& out, std::size_t value) {
    while (value >= 0x80) {
        out.push_back(static_cast<std::uint8_t>(value | 0x80));
        value >>= 7;
    }
    out.push_back(static_cast<std::uint8_t>(value));
}
bool get(const std::vector<std::uint8_t>& in, std::size_t& at, std::size_t& value) {
    value = 0;
    for (unsigned shift = 0; at < in.size() && shift < 64; shift += 7) {
        const auto byte = in[at++];
        value |= static_cast<std::size_t>(byte & 0x7f) << shift;
        if (!(byte & 0x80)) return true;
    }
    return false;
}
} // namespace

std::vector<std::uint8_t> encode_delta(const std::vector<std::uint8_t>& from, const std::vector<std::uint8_t>& to) {
    std::vector<std::uint8_t> out;
    const std::size_t n = std::min(from.size(), to.size());
    std::size_t i = 0;
    while (i < n) {
        const std::size_t zeros_start = i;
        while (i < n && from[i] == to[i]) ++i;
        if (i == n) break;
        // The literal runs until a gap of zero_gap equal bytes, or the end.
        const std::size_t literal_start = i;
        std::size_t equal = 0;
        while (i < n && equal < zero_gap) {
            equal = from[i] == to[i] ? equal + 1 : 0;
            ++i;
        }
        const std::size_t literal_end = i - equal;
        put(out, literal_start - zeros_start);
        put(out, literal_end - literal_start);
        for (std::size_t k = literal_start; k < literal_end; ++k) out.push_back(from[k] ^ to[k]);
        i = literal_end;
    }
    return out;
}

bool apply_delta(std::vector<std::uint8_t>& state, const std::vector<std::uint8_t>& delta) {
    std::size_t at = 0, position = 0;
    while (at < delta.size()) {
        std::size_t zeros = 0, length = 0;
        if (!get(delta, at, zeros) || !get(delta, at, length)) return false;
        position += zeros;
        if (position + length > state.size() || at + length > delta.size()) return false;
        for (std::size_t k = 0; k < length; ++k) state[position + k] ^= delta[at + k];
        position += length;
        at += length;
    }
    return true;
}

void RewindBuffer::push(const std::vector<std::uint8_t>& state) {
    if (top_.size() != state.size()) {
        clear();
        top_ = state;
        return;
    }
    auto delta = encode_delta(top_, state);
    used_ += delta.size();
    deltas_.push_back(std::move(delta));
    top_ = state;
    while (used_ > budget_ && !deltas_.empty()) {
        used_ -= deltas_.front().size();
        deltas_.pop_front();
    }
}

bool RewindBuffer::pop(std::vector<std::uint8_t>& state) {
    if (deltas_.empty()) return false;
    if (!apply_delta(top_, deltas_.back())) {
        clear();
        return false;
    }
    used_ -= deltas_.back().size();
    deltas_.pop_back();
    state = top_;
    return true;
}

void RewindBuffer::clear() {
    deltas_.clear();
    top_.clear();
    used_ = 0;
}
} // namespace ravenemu_android
