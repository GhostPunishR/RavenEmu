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

    /** The RavenCore that runs both games: GBA for two GBA games; for Game Boy
     * games the Game Boy Color core as soon as one of them needs it (a GBC
     * game, or Settings → Console giving Game Boy games their colours). Each
     * player's core, as the settings pick it for a game alone, comes first. */
    public static String core(String firstCore, String secondCore) {
        if ("gba".equals(firstCore) && "gba".equals(secondCore)) return "gba";
        if ("gba".equals(firstCore) || "gba".equals(secondCore)) return null;
        return "gbc".equals(firstCore) || "gbc".equals(secondCore) ? "gbc" : "gb";
    }
}
