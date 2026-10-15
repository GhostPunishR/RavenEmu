package com.ravenemu.shared

/** Game Boy, Game Boy Color and Game Boy Advance import. */
object RomImportPolicy {
    /** The largest GBA cartridge; GB/GBC cartridges stop at 8 MiB. */
    const val maxRomBytes: Int = 32 * 1024 * 1024

    fun acceptsRomFile(filename: String): Boolean {
        val name = filename.substringAfterLast('/').trim()
        return EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
    }

    /**
     * A `.gba` file goes to the GBA core. For `.gb` and `.gbc`, the cartridge
     * decides, not the extension: header byte 0x143 is 0x80 for a color game
     * that also runs on a Game Boy and 0xC0 for a Game Boy Color exclusive.
     * Both run on RavenCore's GBC core, in color, and RetroAchievements lists
     * them under Game Boy Color. Any other value is a monochrome cartridge
     * for the GB core. Same rule as RavenCore's header parser.
     */
    fun platformOf(filename: String, rom: ByteArray): GamePlatform {
        if (filename.trim().endsWith(".gba", ignoreCase = true)) return GamePlatform.GBA
        val flag = if (rom.size > CGB_FLAG) rom[CGB_FLAG].toInt() and 0xFF else 0
        return if (flag == 0x80 || flag == 0xC0) GamePlatform.GBC else GamePlatform.GB
    }

    /** RetroAchievements console IDs, which differ from RavenCore's enum. */
    fun retroAchievementsConsole(platform: GamePlatform): Int = when (platform) {
        GamePlatform.GB -> 4
        GamePlatform.GBA -> 5
        GamePlatform.GBC -> 6
    }

    private val EXTENSIONS = listOf(".gb", ".gbc", ".gba")
    private const val CGB_FLAG = 0x143
}

data class LoadedGameInfo(
    val filename: String,
    val platform: GamePlatform,
    val width: Int,
    val height: Int,
)
