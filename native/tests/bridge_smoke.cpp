#include <ravenemu/bridge.h>
#include <algorithm>
#include <cstdint>
#include <iostream>
#include <memory>
#include <vector>

// Exercise automatic RTC detection through the same public ABI as Android/iOS.
// All bytes are fabricated: ARM B ., an identity string and GPIO literals.
static bool automatic_rtc_battery_round_trip() {
    std::vector<std::uint8_t> rom(0x800);
    rom[0] = 0xfe; rom[1] = 0xff; rom[2] = 0xff; rom[3] = 0xea;
    rom[0xb2] = 0x96;
    constexpr char identity[] = "pokemon red version";
    constexpr char flash[] = "FLASH1M_V103";
    std::copy_n(identity, sizeof(identity), rom.begin() + 0x108);
    std::copy_n(flash, sizeof(flash), rom.begin() + 0x600);
    std::unique_ptr<ravenemu_bridge, decltype(&ravenemu_destroy)> core(
        ravenemu_create(RAVENEMU_GBA), ravenemu_destroy);
    std::vector<std::uint8_t> legacy(0x20000, 0xff);
    legacy[0x123] = 0x5a; legacy[0x10123] = 0xa5;
    // A Pokémon identity alone must not add hardware to the cartridge.
    if (!core || !ravenemu_load(core.get(), rom.data(), rom.size(), legacy.data(), legacy.size()) ||
        ravenemu_battery_size(core.get()) != legacy.size()) return false;
    for (unsigned port = 0; port < 3; ++port) {
        const auto at = 0x400 + port * 8;
        rom[at] = static_cast<std::uint8_t>(0xc4 + port * 2);
        rom[at + 3] = 8;
    }
    // Reproduce the old hardware layout for this exact ROM, not another hash.
    if (!ravenemu_set_gba_rtc(core.get(), RAVENEMU_GBA_RTC_OFF) ||
        !ravenemu_load(core.get(), rom.data(), rom.size(), legacy.data(), legacy.size())) return false;
    std::vector<std::uint8_t> without_rtc(ravenemu_state_size(core.get()));
    if (without_rtc.empty() ||
        !ravenemu_save_state(core.get(), without_rtc.data(), without_rtc.size()) ||
        !ravenemu_set_gba_rtc(core.get(), RAVENEMU_GBA_RTC_AUTO)) return false;
    if (!ravenemu_load(core.get(), rom.data(), rom.size(), legacy.data(), legacy.size()) ||
        ravenemu_battery_size(core.get()) != legacy.size() + 16) return false;
    // A pre-detection state lacks GPIO. Reject it while keeping battery data.
    if (ravenemu_load_state(core.get(), without_rtc.data(), without_rtc.size())) return false;
    std::vector<std::uint8_t> battery(ravenemu_battery_size(core.get()));
    if (!ravenemu_copy_battery(core.get(), battery.data(), battery.size()) ||
        !std::equal(legacy.begin(), legacy.end(), battery.begin())) return false;
    // Reopening and resetting must preserve both Flash banks and the RTC layout.
    if (!ravenemu_load(core.get(), rom.data(), rom.size(), battery.data(), battery.size()) ||
        !ravenemu_reset(core.get()) || ravenemu_battery_size(core.get()) != battery.size() ||
        !ravenemu_copy_battery(core.get(), battery.data(), battery.size()) ||
        !std::equal(legacy.begin(), legacy.end(), battery.begin())) return false;
    std::vector<std::uint8_t> state(ravenemu_state_size(core.get()));
    if (state.empty() || !ravenemu_save_state(core.get(), state.data(), state.size()) ||
        !ravenemu_load_state(core.get(), state.data(), state.size())) return false;
    return true;
}

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
    // Two games side by side: the link cable advances both handles, and a
    // linked handle refuses to run alone.
    Handle other(ravenemu_create(RAVENEMU_GBC), ravenemu_destroy);
    if (!other || !ravenemu_load(other.get(), rom.data(), rom.size(), nullptr, 0)) return 8;
    ravenemu_bridge* const pair[] = {handle.get(), other.get()};
    std::unique_ptr<ravenemu_link, decltype(&ravenemu_link_destroy)> link(
        ravenemu_link_create(pair, 2), ravenemu_link_destroy);
    std::vector<std::uint32_t> second(size);
    std::uint32_t* const frames[] = {frame.data(), second.data()};
    if (!link || !ravenemu_link_run_frame(link.get(), frames, frame.size()) ||
        ravenemu_run_frame(handle.get(), frame.data(), frame.size())) return 9;
    Handle advance(ravenemu_create(RAVENEMU_GBA), ravenemu_destroy);
    ravenemu_bridge* const mixed[] = {advance.get(), other.get()};
    if (!advance || ravenemu_link_create(mixed, 2)) return 10;
    link.reset();
    if (!ravenemu_run_frame(handle.get(), frame.data(), frame.size())) return 11;
    if (!automatic_rtc_battery_round_trip()) {
        std::cerr << "Automatic GBA RTC detection or battery/state round-trip failed\n";
        return 12;
    }
    std::cout << "Normalized C API: create GB/GBC/GBA, frame, audio, state round-trip, link cable and automatic RTC passed\n";
}
