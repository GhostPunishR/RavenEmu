package com.ravenemu.app;

import org.junit.Test;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public final class RomMetadataTest {
    @Test public void readsGameBoyHeaderTitles() {
        byte[] gb = new byte[0x150];
        byte[] title = "PUZZLE TEST".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, gb, 0x134, title.length);
        assertEquals("PUZZLE TEST", RomMetadata.cartridgeTitle("gb", gb));
        assertEquals("Game Boy", RomMetadata.displayPlatform("gb", gb));

        gb[0x143] = (byte)0x80;
        assertEquals("PUZZLE TEST", RomMetadata.cartridgeTitle("gbc", gb));
        assertEquals("Game Boy / Game Boy Color", RomMetadata.displayPlatform("gb", gb));
        gb[0x143] = (byte)0xC0;
        assertEquals("Game Boy Color uniquement", RomMetadata.displayPlatform("gb", gb));
    }

    @Test public void readsGbaHeaderAndRejectsTruncatedData() {
        byte[] gba = new byte[0xC0];
        byte[] title = "RAVEN DEMO".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, gba, 0xA0, title.length);
        assertEquals("RAVEN DEMO", RomMetadata.cartridgeTitle("gba", gba));
        assertEquals("Game Boy Advance", RomMetadata.displayPlatform("gba", gba));
        assertEquals("", RomMetadata.cartridgeTitle("gba", new byte[0xA8]));
        assertEquals("", RomMetadata.cartridgeTitle("gb", new byte[32]));
        assertEquals("", RomMetadata.cartridgeTitle("invalid", gba));
    }

    @Test public void ignoresUntrustedNonAsciiTitle() {
        byte[] gb = new byte[0x150];
        gb[0x134] = (byte)0xFF;
        assertEquals("", RomMetadata.cartridgeTitle("gb", gb));
        gb[0x134] = (byte)'A';
        gb[0x135] = (byte)1;
        assertEquals("", RomMetadata.cartridgeTitle("gb", gb));
    }
}
