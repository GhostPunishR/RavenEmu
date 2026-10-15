#include <ravenemu/bridge.h>
#include <cstdint>
#include <iostream>
#include <memory>
#include <vector>

// A synthetic cartridge with an endless JP 0100, containing no game content.
// Exercise the normalized ABI through real frame, audio and state operations.
int main() {
    if (ravenemu_api_version() != RAVENEMU_API_VERSION) return 1;
    using Handle = std::unique_ptr<ravenemu_bridge, decltype(&ravenemu_destroy)>;
    for (const int system : {RAVENEMU_GB, RAVENEMU_GBC, RAVENEMU_GBA}) {
        Handle handle(ravenemu_create(system), ravenemu_destroy);
        if (!handle || ravenemu_audio_rate(handle.get()) <= 0 ||
            ravenemu_video_fps(handle.get()) <= 0) return 2;
    }
    std::vector<std::uint8_t> rom(0x8000);
    rom[0x100] = 0xc3;
    rom[0x102] = 0x01;
    Handle handle(ravenemu_create(RAVENEMU_GB), ravenemu_destroy);
    if (!handle || !ravenemu_load(handle.get(), rom.data(), rom.size(), nullptr, 0)) return 3;
    const auto size = ravenemu_video_width(handle.get()) * ravenemu_video_height(handle.get());
    if (size != 160 * 144 || !ravenemu_set_button(handle.get(), RAVENEMU_A, 1)) return 4;
    std::vector<std::uint32_t> frame(size);
    if (!ravenemu_run_frame(handle.get(), frame.data(), frame.size())) return 5;
    std::vector<std::int16_t> audio(8192);
    if (ravenemu_read_audio(handle.get(), audio.data(), audio.size()) == 0) return 6;
    std::vector<std::uint8_t> state(ravenemu_state_size(handle.get()));
    if (state.empty() || !ravenemu_save_state(handle.get(), state.data(), state.size()) ||
        !ravenemu_run_frame(handle.get(), frame.data(), frame.size()) ||
        !ravenemu_load_state(handle.get(), state.data(), state.size())) return 7;
    std::cout << "Normalized C API: create GB/GBC/GBA, frame, audio and state round-trip passed\n";
}
