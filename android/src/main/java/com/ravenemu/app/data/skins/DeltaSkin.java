package com.ravenemu.app.data.skins;

import com.ravenemu.app.data.skins.DeltaSkinException.Code;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A Delta skin's info.json, after the beta's DeltaSkinModels: the iPhone
 * representations, standard and edge to edge, each with its PDF, its mapping
 * size and its items. Unknown keys are ignored; a missing required property
 * or a value of the wrong type refuses the skin. Immutable. */
public final class DeltaSkin {
    private DeltaSkin() {}

    /** The consoles this version takes; GB and GBC share their skins, as in Delta. */
    public enum Console {
        GB_GBC("com.rileytestut.delta.game.gbc"),
        GBA("com.rileytestut.delta.game.gba");

        public final String gameType;

        Console(String gameType) { this.gameType = gameType; }

        public static Console of(String gameType) {
            for (Console console : values()) if (console.gameType.equals(gameType)) return console;
            return null;
        }
    }

    public enum Kind { STANDARD, EDGE_TO_EDGE }

    public static final class Manifest {
        public final String name, identifier, gameType;
        public final boolean debug;
        /** Portrait and landscape, standard then edge to edge; null when absent. */
        public final Representation standardPortrait, standardLandscape, edgePortrait, edgeLandscape;
        /** iPhone representations were declared at all. */
        public final boolean iphone;

        Manifest(String name, String identifier, String gameType, boolean debug, boolean iphone,
                 Representation standardPortrait, Representation standardLandscape,
                 Representation edgePortrait, Representation edgeLandscape) {
            this.name = name;
            this.identifier = identifier;
            this.gameType = gameType;
            this.debug = debug;
            this.iphone = iphone;
            this.standardPortrait = standardPortrait;
            this.standardLandscape = standardLandscape;
            this.edgePortrait = edgePortrait;
            this.edgeLandscape = edgeLandscape;
        }

        public Representation portrait(Kind kind) {
            return kind == Kind.STANDARD ? standardPortrait : edgePortrait;
        }

        /** Every representation the skin declares, landscape kept for later. */
        public List<Representation> representations() {
            List<Representation> all = new ArrayList<>();
            for (Representation one : new Representation[]{standardPortrait, standardLandscape, edgePortrait,
                    edgeLandscape})
                if (one != null) all.add(one);
            return all;
        }
    }

    public static final class Representation {
        /** The PDF that scales to any size; the bitmaps of other sizes are ignored. */
        public final String resizable;
        public final Size mapping;
        public final List<Item> items;
        public final Edges extendedEdges;
        public final boolean translucent;
        public final Frame gameScreenFrame;
        public final List<Screen> screens;

        Representation(String resizable, Size mapping, List<Item> items, Edges extendedEdges, boolean translucent,
                       Frame gameScreenFrame, List<Screen> screens) {
            this.resizable = resizable;
            this.mapping = mapping;
            this.items = items;
            this.extendedEdges = extendedEdges;
            this.translucent = translucent;
            this.gameScreenFrame = gameScreenFrame;
            this.screens = screens;
        }
    }

    public static final class Size {
        public final double width, height;

        public Size(double width, double height) {
            this.width = width;
            this.height = height;
        }
    }

    public static final class Frame {
        public final double x, y, width, height;

        public Frame(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /** Each side may be absent: an item's side falls back on its representation's. */
    public static final class Edges {
        public static final Edges NONE = new Edges(null, null, null, null);
        public final Double top, bottom, left, right;

        public Edges(Double top, Double bottom, Double left, Double right) {
            this.top = top;
            this.bottom = bottom;
            this.left = left;
            this.right = right;
        }

        public double top(Edges parent) { return pick(top, parent.top); }
        public double bottom(Edges parent) { return pick(bottom, parent.bottom); }
        public double left(Edges parent) { return pick(left, parent.left); }
        public double right(Edges parent) { return pick(right, parent.right); }

        private static double pick(Double own, Double parent) {
            return own != null ? own : parent != null ? parent : 0.0;
        }
    }

    /** A button or a combination is a list; a D-pad maps up, down, left and
     * right to inputs, exactly as Delta writes them. */
    public static final class Item {
        public final List<String> buttons;
        public final Map<String, String> directions;
        public final Frame frame;
        public final Edges extendedEdges;

        Item(List<String> buttons, Map<String, String> directions, Frame frame, Edges extendedEdges) {
            this.buttons = buttons;
            this.directions = directions;
            this.frame = frame;
            this.extendedEdges = extendedEdges;
        }

        public boolean directional() { return directions != null; }
    }

    public static final class Screen {
        public final Frame inputFrame, outputFrame;

        Screen(Frame inputFrame, Frame outputFrame) {
            this.inputFrame = inputFrame;
            this.outputFrame = outputFrame;
        }
    }

    public static Manifest parse(String source) throws DeltaSkinException {
        String text = source.startsWith("﻿") ? source.substring(1) : source;
        try {
            JSONObject root = new JSONObject(text);
            String name = string(root, "name", true);
            String identifier = string(root, "identifier", true);
            String gameType = string(root, "gameTypeIdentifier", true);
            boolean debug = bool(root, "debug", true, false);
            JSONObject representations = object(root, "representations", true);
            JSONObject iphone = object(representations, "iphone", false);
            Representation standardPortrait = null, standardLandscape = null, edgePortrait = null,
                    edgeLandscape = null;
            if (iphone != null) {
                JSONObject standard = object(iphone, "standard", false);
                if (standard != null) {
                    standardPortrait = representation(object(standard, "portrait", false));
                    standardLandscape = representation(object(standard, "landscape", false));
                }
                JSONObject edge = object(iphone, "edgeToEdge", false);
                if (edge != null) {
                    edgePortrait = representation(object(edge, "portrait", false));
                    edgeLandscape = representation(object(edge, "landscape", false));
                }
            }
            return new Manifest(name, identifier, gameType, debug, iphone != null, standardPortrait,
                    standardLandscape, edgePortrait, edgeLandscape);
        } catch (JSONException malformed) {
            throw new DeltaSkinException(Code.MALFORMED_JSON, "Le manifeste info.json est malformé", malformed);
        }
    }

    private static Representation representation(JSONObject json) throws DeltaSkinException {
        if (json == null) return null;
        JSONObject assets = object(json, "assets", true);
        String resizable = string(assets, "resizable", false);
        Size mapping = size(object(json, "mappingSize", true));
        JSONArray rawItems = array(json, "items", true);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < rawItems.length(); i++) items.add(item(element(rawItems, i)));
        Edges edges = edges(object(json, "extendedEdges", false));
        boolean translucent = bool(json, "translucent", false, false);
        JSONObject screenFrame = object(json, "gameScreenFrame", false);
        JSONArray rawScreens = array(json, "screens", false);
        List<Screen> screens = new ArrayList<>();
        if (rawScreens != null) {
            for (int i = 0; i < rawScreens.length(); i++) {
                JSONObject screen = element(rawScreens, i);
                JSONObject input = object(screen, "inputFrame", false);
                screens.add(new Screen(input == null ? null : frame(input), frame(object(screen, "outputFrame", true))));
            }
        }
        return new Representation(resizable, mapping, Collections.unmodifiableList(items), edges, translucent,
                screenFrame == null ? null : frame(screenFrame), Collections.unmodifiableList(screens));
    }

    private static Item item(JSONObject json) throws DeltaSkinException {
        Object inputs = json.opt("inputs");
        if (inputs == null || inputs == JSONObject.NULL) throw missing("inputs");
        List<String> buttons = null;
        Map<String, String> directions = null;
        if (inputs instanceof JSONArray) {
            JSONArray list = (JSONArray) inputs;
            List<String> values = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                Object value = list.opt(i);
                if (!(value instanceof String)) throw malformed("Une entrée de bouton doit être une chaîne");
                values.add((String) value);
            }
            buttons = Collections.unmodifiableList(values);
        } else if (inputs instanceof JSONObject) {
            JSONObject map = (JSONObject) inputs;
            Map<String, String> values = new LinkedHashMap<>();
            for (Iterator<String> keys = map.keys(); keys.hasNext(); ) {
                String key = keys.next();
                Object value = map.opt(key);
                if (!(value instanceof String)) throw malformed("Une direction de D-pad doit référencer une chaîne");
                values.put(key, (String) value);
            }
            directions = Collections.unmodifiableMap(values);
        } else {
            throw malformed("inputs doit être un tableau de boutons ou un dictionnaire de directions");
        }
        return new Item(buttons, directions, frame(object(json, "frame", true)),
                edges(object(json, "extendedEdges", false)));
    }

    private static Frame frame(JSONObject json) throws DeltaSkinException {
        return new Frame(number(json, "x"), number(json, "y"), number(json, "width"), number(json, "height"));
    }

    private static Size size(JSONObject json) throws DeltaSkinException {
        return new Size(number(json, "width"), number(json, "height"));
    }

    private static Edges edges(JSONObject json) throws DeltaSkinException {
        if (json == null) return Edges.NONE;
        return new Edges(optionalNumber(json, "top"), optionalNumber(json, "bottom"),
                optionalNumber(json, "left"), optionalNumber(json, "right"));
    }

    private static String string(JSONObject json, String key, boolean required) throws DeltaSkinException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) {
            if (required) throw missing(key);
            return null;
        }
        if (!(value instanceof String)) throw malformed("La propriété " + key + " doit être une chaîne");
        return (String) value;
    }

    private static boolean bool(JSONObject json, String key, boolean required, boolean fallback)
            throws DeltaSkinException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) {
            if (required) throw missing(key);
            return fallback;
        }
        if (!(value instanceof Boolean)) throw malformed("La propriété " + key + " doit être un booléen");
        return (Boolean) value;
    }

    private static double number(JSONObject json, String key) throws DeltaSkinException {
        Double value = optionalNumber(json, key);
        if (value == null) throw missing(key);
        return value;
    }

    private static Double optionalNumber(JSONObject json, String key) throws DeltaSkinException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof Number)) throw malformed("La propriété " + key + " doit être un nombre");
        return ((Number) value).doubleValue();
    }

    private static JSONObject object(JSONObject json, String key, boolean required) throws DeltaSkinException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) {
            if (required) throw missing(key);
            return null;
        }
        if (!(value instanceof JSONObject)) throw malformed("La propriété " + key + " doit être un objet");
        return (JSONObject) value;
    }

    private static JSONArray array(JSONObject json, String key, boolean required) throws DeltaSkinException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) {
            if (required) throw missing(key);
            return null;
        }
        if (!(value instanceof JSONArray)) throw malformed("La propriété " + key + " doit être un tableau");
        return (JSONArray) value;
    }

    private static JSONObject element(JSONArray array, int index) throws DeltaSkinException {
        Object value = array.opt(index);
        if (!(value instanceof JSONObject)) throw malformed("Un élément de tableau doit être un objet");
        return (JSONObject) value;
    }

    private static DeltaSkinException missing(String key) {
        return new DeltaSkinException(Code.MISSING_REQUIRED_PROPERTY,
                "Une propriété obligatoire du manifeste est absente : " + key);
    }

    private static DeltaSkinException malformed(String message) {
        return new DeltaSkinException(Code.MALFORMED_JSON, message);
    }
}
