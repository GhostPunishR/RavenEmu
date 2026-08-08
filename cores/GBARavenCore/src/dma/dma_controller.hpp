#pragma once

#include "memory/bus.hpp"

namespace ravenemu::gba {

class DmaController {
public:
    DmaController(Bus& bus, InterruptController& interrupts) : bus_(bus), interrupts_(interrupts) {}
    void control_write(int channel, int control) {
        if ((control & 0x8000) == 0) return;
        source_[static_cast<std::size_t>(channel)] = i32(u32(read_io_word(source_offsets_[static_cast<std::size_t>(channel)])) & 0x0fff'ffffU);
        destination_[static_cast<std::size_t>(channel)] = i32(u32(read_io_word(destination_offsets_[static_cast<std::size_t>(channel)])) & 0x0fff'ffffU);
        if (timing(control) == 0) transfer(channel);
    }
    void trigger_vblank() { trigger_timing(1); }
    void trigger_hblank() { trigger_timing(2); }
    void trigger_sound_fifo(int fifo_channel) {
        const auto fifo_address = fifo_channel == 0 ? i32(0x040000a0U) : i32(0x040000a4U);
        for (int channel = 1; channel <= 2; ++channel) {
            const auto control = read_io_half(control_offsets_[static_cast<std::size_t>(channel)]);
            if ((control & 0x8000) == 0 || timing(control) != 3) continue;
            if (i32(u32(read_io_word(destination_offsets_[static_cast<std::size_t>(channel)])) & 0x0fff'ffffU) != fifo_address) continue;
            const auto started = std::chrono::steady_clock::now();
            const auto source_control = (control >> 7) & 3;
            auto source = source_[static_cast<std::size_t>(channel)];
            auto cycles = 2;
            for (int word = 0; word < 4; ++word) {
                cycles += access_cycles(source, 4, word > 0) + access_cycles(fifo_address, 4, word > 0);
                bus_.write32(fifo_address, bus_.read32(source));
                source = add32(source, delta(source_control, 4));
            }
            source_[static_cast<std::size_t>(channel)] = source;
            finish(channel, cycles, started);
            if ((control & 0x4000) != 0) interrupts_.request(InterruptController::dma0 + channel);
        }
    }
    int take_pending_cycles() noexcept { return std::exchange(pending_cycles, 0); }
    [[nodiscard]] bool active() const noexcept { return pending_cycles > 0; }
    std::array<std::int32_t, 9> export_state() const noexcept {
        std::array<std::int32_t, 9> result{};
        std::copy(source_.begin(), source_.end(), result.begin());
        std::copy(destination_.begin(), destination_.end(), result.begin() + 4);
        result[8] = pending_cycles;
        return result;
    }
    void import_state(std::span<const std::int32_t> values) {
        if (values.size() != 9) throw SaveStateError("État DMA GBA invalide");
        std::copy_n(values.begin(), 4, source_.begin());
        std::copy_n(values.begin() + 4, 4, destination_.begin());
        pending_cycles = values[8];
    }
    void reset() noexcept { source_.fill(0); destination_.fill(0); pending_cycles = 0; last_channel = -1; }
    int pending_cycles{};
    int last_channel{-1};

private:
    static constexpr std::array source_offsets_{0x0b0, 0x0bc, 0x0c8, 0x0d4};
    static constexpr std::array destination_offsets_{0x0b4, 0x0c0, 0x0cc, 0x0d8};
    static constexpr std::array count_offsets_{0x0b8, 0x0c4, 0x0d0, 0x0dc};
    static constexpr std::array control_offsets_{0x0ba, 0x0c6, 0x0d2, 0x0de};
    int read_io_half(int offset) const noexcept {
        return bus_.io[static_cast<std::size_t>(offset)] | bus_.io[static_cast<std::size_t>(offset + 1)] << 8;
    }
    std::int32_t read_io_word(int offset) const noexcept {
        return i32(static_cast<std::uint32_t>(read_io_half(offset)) |
                   (static_cast<std::uint32_t>(read_io_half(offset + 2)) << 16U));
    }
    static int timing(int control) noexcept { return (control >> 12) & 3; }
    static int delta(int control, int size) noexcept {
        if (control == 0 || control == 3) return size;
        if (control == 1) return -size;
        return 0;
    }
    int word_count(int channel, int raw) const noexcept {
        const auto mask = channel == 3 ? 0xffff : 0x3fff;
        const auto value = raw & mask;
        return value == 0 ? mask + 1 : value;
    }
    int access_cycles(std::int32_t address, int size, bool sequential) const noexcept {
        return 1 + bus_.timing.wait_states(address, size, sequential);
    }
    void trigger_timing(int requested) {
        for (int channel = 0; channel < 4; ++channel) {
            const auto control = read_io_half(control_offsets_[static_cast<std::size_t>(channel)]);
            if ((control & 0x8000) != 0 && timing(control) == requested) transfer(channel);
        }
    }
    void transfer(int channel) {
        const auto started = std::chrono::steady_clock::now();
        const auto index = static_cast<std::size_t>(channel);
        const auto control = read_io_half(control_offsets_[index]);
        const auto size = (control & 0x0400) != 0 ? 4 : 2;
        const auto destination_control = (control >> 5) & 3;
        const auto source_control = (control >> 7) & 3;
        const auto count = word_count(channel, read_io_half(count_offsets_[index]));
        auto source = source_[index]; auto destination = destination_[index];
        if (auto* memory = bus_.eeprom(); memory &&
            ((u32(source) >> 24U) == 0x0dU || (u32(destination) >> 24U) == 0x0dU)) {
            memory->hint_transfer_length(count);
        }
        auto cycles = 2;
        for (int word = 0; word < count; ++word) {
            cycles += access_cycles(source, size, word > 0) + access_cycles(destination, size, word > 0);
            if (size == 4) bus_.write32(destination, bus_.read32(source));
            else bus_.write16(destination, bus_.read16(source));
            source = add32(source, delta(source_control, size));
            destination = add32(destination, delta(destination_control, size));
        }
        source_[index] = source;
        if (destination_control != 3) destination_[index] = destination;
        finish(channel, cycles, started);
        if ((control & 0x4000) != 0) interrupts_.request(InterruptController::dma0 + channel);
        if ((control & 0x0200) == 0 || timing(control) == 0) {
            const auto cleared = control & ~0x8000;
            bus_.io[static_cast<std::size_t>(control_offsets_[index] + 1)] = static_cast<std::uint8_t>(cleared >> 8);
        }
    }
    void finish(int channel, int cycles, std::chrono::steady_clock::time_point started) {
        bus_.take_wait_cycles();
        if (bus_.diagnostics.measuring_time) {
            bus_.diagnostics.dma_nanos += std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - started).count();
        }
        bus_.break_access_sequence(); pending_cycles += cycles; last_channel = channel;
    }
    Bus& bus_;
    InterruptController& interrupts_;
    std::array<std::int32_t, 4> source_{};
    std::array<std::int32_t, 4> destination_{};
};

inline void dma_control_write(DmaController* dma, int channel, int value) {
    if (dma) dma->control_write(channel, value);
}

} // namespace ravenemu::gba
