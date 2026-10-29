package com.ravenemu.app;

/**
 * Read only display metadata from the standard Game Boy and Game Boy Advance
 * cartridge headers. This parser never executes ROM bytes or treats metadata
 * as a path, and does not enforce a commercial Nintendo logo/header checksum:
 * homebrew cartridges may intentionally omit those fields.
 */
final class RomMetadata {
    private RomMetadata() {}

    static String cartridgeTitle(String system, byte[] rom) {
        if (rom == null || system == null) return "";
        if ("gba".equals(system)) return asciiTitle(rom, 0xA0, 12);
        if ("gb".equals(system) || "gbc".equals(system)) {
            if (rom.length <= 0x143) return "";
            int flag = rom[0x143] & 0xff;
            // The CGB compatibility marker shares byte 0x143 with the title.
            int length = flag == 0x80 || flag == 0xC0 ? 15 : 16;
            return asciiTitle(rom, 0x134, length);
        }
        return "";
    }

    private static String asciiTitle(byte[] data, int offset, int length) {
        if (offset > data.length || length > data.length - offset) return "";
        StringBuilder title = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int ch = data[offset + i] & 0xff;
            if (ch == 0) break;
            if (ch < 32 || ch > 126) return "";
            title.append((char)ch);
        }
        return title.toString().trim();
    }

    static String displayPlatform(String system, byte[] rom) {
        if ("gba".equals(system)) return "Game Boy Advance";
        if ("gbc".equals(system)) return "Game Boy Color";
        if ("gb".equals(system)) {
            if (rom != null && rom.length > 0x143) {
                int flag = rom[0x143] & 0xff;
                if (flag == 0xC0) return "Game Boy Color uniquement";
                if (flag == 0x80) return "Game Boy / Game Boy Color";
            }
            return "Game Boy";
        }
        return "Inconnu";
    }
}
