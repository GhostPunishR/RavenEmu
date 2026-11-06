package com.ravenemu.app.data.saves;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A game's cheats, after the beta's GbCheatList: as RavenEmu PC writes them
 * beside its saves, a line per cheat, "1<TAB>name<TAB>code" (0 when it is
 * off), so the same file reads on both. A code is kept on one line: RavenCore
 * reads spaces, line breaks and '+' alike between its lines. Free of Android
 * types; lists are immutable. */
public final class CheatList {
    private CheatList() {}

    public static final class Cheat {
        public final String name, code;
        public final boolean enabled;

        public Cheat(String name, String code, boolean enabled) {
            this.name = name == null ? "" : name;
            this.code = code == null ? "" : code;
            this.enabled = enabled;
        }

        public Cheat withEnabled(boolean value) { return new Cheat(name, code, value); }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Cheat)) return false;
            Cheat cheat = (Cheat) other;
            return cheat.enabled == enabled && cheat.name.equals(name) && cheat.code.equals(code);
        }

        @Override public int hashCode() { return (name.hashCode() * 31 + code.hashCode()) * 2 + (enabled ? 1 : 0); }
    }

    /** Line breaks, tabs and runs of spaces become one space; the ends are trimmed. */
    public static String oneLine(String text) {
        if (text == null) return "";
        StringBuilder line = new StringBuilder();
        for (String part : text.split("[ \t\r\n]+")) {
            if (part.isEmpty()) continue;
            if (line.length() > 0) line.append(' ');
            line.append(part);
        }
        return line.toString();
    }

    /** Unreadable lines are skipped, and a cheat without code. */
    public static List<Cheat> parse(String text) {
        List<Cheat> cheats = new ArrayList<>();
        if (text == null) return Collections.unmodifiableList(cheats);
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            int first = line.indexOf('\t');
            int second = first < 0 ? -1 : line.indexOf('\t', first + 1);
            if (second < 0) continue;
            String code = oneLine(line.substring(second + 1));
            if (code.isEmpty()) continue;
            cheats.add(new Cheat(line.substring(first + 1, second), code, line.substring(0, first).equals("1")));
        }
        return Collections.unmodifiableList(cheats);
    }

    /** A name holds no tab or line break: they would split its line. */
    public static String format(List<Cheat> cheats) {
        StringBuilder text = new StringBuilder();
        for (Cheat cheat : cheats) {
            String name = cheat.name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
            text.append(cheat.enabled ? '1' : '0').append('\t').append(name).append('\t')
                    .append(oneLine(cheat.code)).append('\n');
        }
        return text.toString();
    }

    /** The codes RavenCore runs: the enabled ones, in order. */
    public static String[] active(List<Cheat> cheats) {
        List<String> codes = new ArrayList<>();
        for (Cheat cheat : cheats) if (cheat.enabled) codes.add(cheat.code);
        return codes.toArray(new String[0]);
    }

    /** The game menu's entry, as the beta's CheatsMenuButton. */
    public static String menuLabel(List<Cheat> cheats) {
        int on = active(cheats).length;
        return on == 0 ? "Codes de triche" : "Codes de triche · " + on + " actif" + (on > 1 ? "s" : "");
    }
}
