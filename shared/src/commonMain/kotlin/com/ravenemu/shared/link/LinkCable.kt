package com.ravenemu.shared.link

import com.ravenemu.shared.library.GameEntry
import com.ravenemu.shared.library.GamePlatform

/**
 * Two games side by side on one device, joined by a virtual link cable.
 * The cable joins a Game Boy and a Game Boy Color in any mix, or two Game Boy
 * Advance; a game is never linked to itself, since a cartridge has only one
 * save.
 */
object LinkCable {
    fun compatible(first: GamePlatform, second: GamePlatform): Boolean =
        (first == GamePlatform.GBA) == (second == GamePlatform.GBA)

    /** Games of [library] that [game] can be linked to, in library order. */
    fun partners(game: GameEntry, library: List<GameEntry>): List<GameEntry> =
        library.filter { it.id != game.id && compatible(game.platform, it.platform) }
}
