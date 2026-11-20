package com.ravenemu.app.data.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Two games of the library on one phone, joined by a virtual link cable,
 * after the beta's LinkCable: a Game Boy and a Game Boy Color in any mix, or
 * two Game Boy Advance. A game is never linked to itself: a cartridge has only
 * one save. Free of Android types. */
public final class LinkCable {
    private LinkCable() {}

    public static boolean compatible(String first, String second) {
        return "gba".equals(first) == "gba".equals(second);
    }

    /** The games this one can be linked to, in the library's order. */
    public static List<RomLibrary.Entry> partners(RomLibrary.Entry game, List<RomLibrary.Entry> library) {
        List<RomLibrary.Entry> partners = new ArrayList<>();
        for (RomLibrary.Entry other : library)
            if (!other.key.equals(game.key) && compatible(game.system, other.system)) partners.add(other);
        return Collections.unmodifiableList(partners);
    }

    /** The RavenCore that runs both games: GBA for two GBA games, the Game
     * Boy Color core for two Game Boy or Game Boy Color games, none otherwise.
     * Each player's core, as the settings pick it for a game alone, comes first. */
    public static String core(String firstCore, String secondCore) {
        if ("gba".equals(firstCore) && "gba".equals(secondCore)) return "gba";
        if ("gba".equals(firstCore) || "gba".equals(secondCore)) return null;
        return "gbc";
    }

    /** The console both Game Boy games run on, set once for the two: the
     * Game Boy Color (or the GBC of a GBA) as soon as one of them needs it, a
     * GBC game or Settings → Console giving Game Boy games their colours.
     * Each player's console comes as ConsoleSettings.hardwareFor gives it. */
    public static String hardware(String first, String second) {
        if (first == null || second == null) return null;
        return "gb".equals(first) ? second : first;
    }
}
