package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.data.skins.DeltaSkin.Console;
import com.ravenemu.app.data.skins.DeltaSkin.Edges;
import com.ravenemu.app.data.skins.DeltaSkin.Item;
import com.ravenemu.app.data.skins.DeltaSkin.Representation;
import com.ravenemu.app.ui.player.DeltaSkinLayout.Box;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A Delta skin's touches, after the beta's DeltaSkinInputMapper and
 * DeltaSkinInputState: what a point of the panel presses (the drawn frame,
 * else its extended edges, bounded by the panel), the D-pad cut in three by
 * three for the diagonals, and every finger combined, a button released only
 * when its last finger leaves it. The "menu" input opens the game menu, once
 * per press. Pure, UI thread. */
final class DeltaSkinInput {
    /** What a pressed part draws over: an item, or one cell of a D-pad (0..8). */
    static final class Target {
        final int item, cell;
        final Box box;

        Target(int item, int cell, Box box) {
            this.item = item;
            this.cell = cell;
            this.box = box;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Target)) return false;
            Target target = (Target) other;
            return target.item == item && target.cell == cell && target.box.equals(box);
        }

        @Override public int hashCode() { return (item * 31 + cell) * 31 + box.hashCode(); }
    }

    static final class Mapped {
        static final Mapped NONE = new Mapped(0, false, Collections.emptySet());
        final int mask;
        final boolean menu;
        final Set<Target> visuals;

        Mapped(int mask, boolean menu, Set<Target> visuals) {
            this.mask = mask;
            this.menu = menu;
            this.visuals = visuals;
        }

        boolean empty() { return mask == 0 && !menu && visuals.isEmpty(); }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Mapped)) return false;
            Mapped mapped = (Mapped) other;
            return mapped.mask == mask && mapped.menu == menu && mapped.visuals.equals(visuals);
        }

        @Override public int hashCode() { return (mask * 31 + (menu ? 1 : 0)) * 31 + visuals.hashCode(); }
    }

    private final Console console;
    private final Representation representation;
    private final Box panel;

    DeltaSkinInput(Console console, Representation representation, Box panel) {
        this.console = console;
        this.representation = representation;
        this.panel = panel;
    }

    Mapped at(double x, double y) {
        if (!panel.contains(x, y)) return Mapped.NONE;
        double sx = panel.width() / representation.mapping.width, sy = panel.height() / representation.mapping.height;
        List<Integer> hits = new ArrayList<>();
        List<Box> frames = new ArrayList<>();
        List<Boolean> inside = new ArrayList<>();
        for (int index = 0; index < representation.items.size(); index++) {
            Item item = representation.items.get(index);
            Box frame = DeltaSkinLayout.map(item.frame, panel, representation.mapping);
            Edges parent = representation.extendedEdges, edges = item.extendedEdges;
            Box expanded = new Box(frame.left - edges.left(parent) * sx, frame.top - edges.top(parent) * sy,
                    frame.right + edges.right(parent) * sx, frame.bottom + edges.bottom(parent) * sy).intersect(panel);
            if (expanded == null || !expanded.contains(x, y)) continue;
            hits.add(index);
            frames.add(frame);
            inside.add(frame.contains(x, y));
        }
        // A drawn frame wins over a neighbour's extension: extensions fill the
        // gaps without pressing two buttons at once over another one.
        boolean anyInside = inside.contains(Boolean.TRUE);
        int mask = 0;
        boolean menu = false;
        Set<Target> visuals = new LinkedHashSet<>();
        for (int i = 0; i < hits.size(); i++) {
            if (anyInside && !inside.get(i)) continue;
            int index = hits.get(i);
            Item item = representation.items.get(index);
            Box frame = frames.get(i);
            if (!item.directional()) {
                int buttons = 0;
                boolean itemMenu = false;
                for (String input : item.buttons) {
                    buttons |= bit(input);
                    itemMenu |= input.trim().equalsIgnoreCase("menu");
                }
                mask |= buttons;
                menu |= itemMenu;
                if (buttons != 0 || itemMenu) visuals.add(new Target(index, -1, frame));
            } else {
                int cell = cell(x, y, frame);
                String[] directions = directions(cell);
                for (String direction : directions) {
                    String input = item.directions.get(direction);
                    if (input == null) continue;
                    mask |= bit(input);
                    menu |= input.trim().equalsIgnoreCase("menu");
                }
                if (directions.length > 0) visuals.add(new Target(index, cell, cellBox(frame, cell)));
            }
        }
        return new Mapped(mask, menu, Collections.unmodifiableSet(visuals));
    }

    private int bit(String raw) {
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "up": return NativeBridge.UP;
            case "down": return NativeBridge.DOWN;
            case "left": return NativeBridge.LEFT;
            case "right": return NativeBridge.RIGHT;
            case "a": return NativeBridge.A;
            case "b": return NativeBridge.B;
            case "start": return NativeBridge.START;
            case "select": return NativeBridge.SELECT;
            case "l": return console == Console.GBA ? NativeBridge.L : 0;
            case "r": return console == Console.GBA ? NativeBridge.R : 0;
            default: return 0;
        }
    }

    private static int cell(double x, double y, Box frame) {
        double cx = Math.max(frame.left, Math.min(frame.right, x)), cy = Math.max(frame.top, Math.min(frame.bottom, y));
        int column = (int) Math.floor(Math.max(0, Math.min(2.999999, (cx - frame.left) / frame.width() * 3)));
        int row = (int) Math.floor(Math.max(0, Math.min(2.999999, (cy - frame.top) / frame.height() * 3)));
        return row * 3 + column;
    }

    private static String[] directions(int cell) {
        switch (cell) {
            case 0: return new String[]{"up", "left"};
            case 1: return new String[]{"up"};
            case 2: return new String[]{"up", "right"};
            case 3: return new String[]{"left"};
            case 5: return new String[]{"right"};
            case 6: return new String[]{"down", "left"};
            case 7: return new String[]{"down"};
            case 8: return new String[]{"down", "right"};
            default: return new String[0];
        }
    }

    private static Box cellBox(Box frame, int cell) {
        double w = frame.width() / 3, h = frame.height() / 3;
        int column = cell % 3, row = cell / 3;
        return new Box(frame.left + column * w, frame.top + row * h, frame.left + (column + 1) * w,
                frame.top + (row + 1) * h);
    }

    /** Every finger on the skin: their buttons combined, the menu opened when
     * a first finger reaches it. */
    static final class Fingers {
        interface Listener {
            void buttons(int mask);
            void menu();
            void visuals(Set<Target> visuals);
        }

        private final Map<Integer, Mapped> pointers = new LinkedHashMap<>();
        private final Listener listener;
        private int mask;
        private boolean menu;

        Fingers(Listener listener) { this.listener = listener; }

        void update(int pointer, Mapped input) {
            Mapped previous = pointers.get(pointer);
            if (input.equals(previous == null ? Mapped.NONE : previous)) return;
            if (input.empty()) pointers.remove(pointer);
            else pointers.put(pointer, input);
            publish();
        }

        void release(int pointer) {
            if (pointers.remove(pointer) != null) publish();
        }

        void releaseAll() {
            if (pointers.isEmpty() && mask == 0 && !menu) return;
            pointers.clear();
            publish();
        }

        int mask() { return mask; }

        Set<Target> visuals() {
            Set<Target> all = new LinkedHashSet<>();
            for (Mapped input : pointers.values()) all.addAll(input.visuals);
            return all;
        }

        private void publish() {
            int next = 0;
            boolean nextMenu = false;
            for (Mapped input : pointers.values()) {
                next |= input.mask;
                nextMenu |= input.menu;
            }
            boolean opened = !menu && nextMenu;
            menu = nextMenu;
            if (next != mask) {
                mask = next;
                listener.buttons(next);
            }
            if (opened) listener.menu();
            listener.visuals(visuals());
        }
    }
}
