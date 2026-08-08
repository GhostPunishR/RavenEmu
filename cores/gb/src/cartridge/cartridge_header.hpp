#pragma once

#include "support/bits.hpp"

namespace ravenemu::cgb {

enum class MbcType : std::uint8_t { none, mbc1, mbc2, mbc3, mbc5, unsupported };

struct CartridgeHeader {
    int cartridge_type{};
    MbcType mbc{MbcType::unsupported};
    bool has_ram{};
    bool has_battery{};
    bool has_rtc{};
    int ram_size{};
    bool uses_color{};

    static constexpr std::size_t min_rom_size = 0x8000;
    static constexpr std::size_t max_rom_size = 8U * 1024U * 1024U;

    static CartridgeHeader parse(std::span<const std::uint8_t> rom) {
        if (rom.size() < min_rom_size) {
            throw RomLoadError("ROM trop petite (minimum 32768 octets)");
        }
        if (rom.size() > max_rom_size) {
            throw RomLoadError("ROM trop grande (maximum 8388608 octets)");
        }

        CartridgeHeader header;
        header.cartridge_type = rom[0x147];
        switch (header.cartridge_type) {
        case 0x00: case 0x08: case 0x09: header.mbc = MbcType::none; break;
        case 0x01: case 0x02: case 0x03: header.mbc = MbcType::mbc1; break;
        case 0x05: case 0x06: header.mbc = MbcType::mbc2; break;
        case 0x0f: case 0x10: case 0x11: case 0x12: case 0x13: header.mbc = MbcType::mbc3; break;
        case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e:
            header.mbc = MbcType::mbc5; break;
        default: header.mbc = MbcType::unsupported; break;
        }

        switch (header.cartridge_type) {
        case 0x02: case 0x03: case 0x05: case 0x06: case 0x08: case 0x09:
        case 0x10: case 0x12: case 0x13: case 0x1a: case 0x1b: case 0x1d: case 0x1e:
            header.has_ram = true; break;
        default: break;
        }
        switch (header.cartridge_type) {
        case 0x03: case 0x06: case 0x09: case 0x0f: case 0x10: case 0x13:
        case 0x1b: case 0x1e: header.has_battery = true; break;
        default: break;
        }
        header.has_rtc = header.cartridge_type == 0x0f || header.cartridge_type == 0x10;
        if (header.mbc == MbcType::mbc2) {
            header.has_ram = true;
            header.ram_size = 512;
        } else if (header.has_ram) {
            switch (rom[0x149]) {
            case 0x01: header.ram_size = 2 * 1024; break;
            case 0x02: header.ram_size = 8 * 1024; break;
            case 0x03: header.ram_size = 32 * 1024; break;
            case 0x04: header.ram_size = 128 * 1024; break;
            case 0x05: header.ram_size = 64 * 1024; break;
            default: header.ram_size = 0; break;
            }
        }
        const auto cgb_flag = rom[0x143];
        header.uses_color = cgb_flag == 0x80 || cgb_flag == 0xc0;
        return header;
    }
};

} // namespace ravenemu::cgb
