package com.ravenemu.app.data.skins;

import com.ravenemu.app.data.skins.DeltaSkin.Console;
import com.ravenemu.app.data.skins.DeltaSkin.Edges;
import com.ravenemu.app.data.skins.DeltaSkin.Frame;
import com.ravenemu.app.data.skins.DeltaSkin.Item;
import com.ravenemu.app.data.skins.DeltaSkin.Kind;
import com.ravenemu.app.data.skins.DeltaSkin.Manifest;
import com.ravenemu.app.data.skins.DeltaSkin.Representation;
import com.ravenemu.app.data.skins.DeltaSkin.Screen;
import com.ravenemu.app.data.skins.DeltaSkin.Size;
import com.ravenemu.app.data.skins.DeltaSkinException.Code;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The beta's DeltaSkinValidator: a GB/GBC or GBA skin with at least one iPhone
 * portrait representation, each frame inside its mapping size (1 % of
 * rounding allowed), finite numbers, and its PDF named at the archive's root.
 * Inputs RavenCore does not have are kept aside, not refused. */
public final class DeltaSkinValidator {
    public static final double MAX_LOGICAL_VALUE = 100_000.0;
    private static final int MAX_TEXT_LENGTH = 512;
    private static final double FRAME_EPSILON = 0.0001;
    private static final double MAX_FRAME_OVERSCAN_RATIO = 0.01;
    private static final Set<String> GB_INPUTS = new HashSet<>(Arrays.asList(
            "up", "down", "left", "right", "a", "b", "start", "select", "menu"));
    private static final Set<String> GBA_INPUTS = new HashSet<>(GB_INPUTS);

    static {
        GBA_INPUTS.add("l");
        GBA_INPUTS.add("r");
    }

    private DeltaSkinValidator() {}

    public static final class Validation {
        public final Manifest manifest;
        public final Console console;
        public final Set<Kind> portraitKinds;
        /** Sorted, as the metadata keeps them. */
        public final List<String> ignoredInputs;

        Validation(Manifest manifest, Console console, Set<Kind> portraitKinds, List<String> ignoredInputs) {
            this.manifest = manifest;
            this.console = console;
            this.portraitKinds = portraitKinds;
            this.ignoredInputs = ignoredInputs;
        }
    }

    public static Validation validate(Manifest manifest) throws DeltaSkinException {
        requireText(manifest.name, "name");
        requireText(manifest.identifier, "identifier");
        Console console = Console.of(manifest.gameType);
        if (console == null) throw new DeltaSkinException(Code.UNSUPPORTED_CONSOLE, "Cette console n'est pas prise en charge");
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        for (Kind kind : Kind.values()) if (manifest.portrait(kind) != null) kinds.add(kind);
        if (!manifest.iphone || kinds.isEmpty())
            throw new DeltaSkinException(Code.PORTRAIT_REPRESENTATION_MISSING,
                    "Aucune représentation iPhone portrait n'est disponible");
        Set<String> ignored = new LinkedHashSet<>();
        for (Representation representation : manifest.representations())
            validateRepresentation(representation, console, ignored);
        List<String> sorted = new ArrayList<>(ignored);
        Collections.sort(sorted);
        return new Validation(manifest, console, Collections.unmodifiableSet(kinds), Collections.unmodifiableList(sorted));
    }

    /** A name at the archive's root: no separator of any script, no control
     * character, not "." or "..". */
    public static boolean isSafeRootAssetName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > MAX_TEXT_LENGTH) return false;
        if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '∕' || c == '⁄' || c == '⧸' || c == '／'
                    || c == '＼' || c == '﹨' || c < 0x20) return false;
        }
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
        return !normalized.equals(".") && !normalized.equals("..");
    }

    public static boolean supported(Console console, String input) {
        return (console == Console.GBA ? GBA_INPUTS : GB_INPUTS).contains(input.trim().toLowerCase(Locale.ROOT));
    }

    private static void validateRepresentation(Representation representation, Console console, Set<String> ignored)
            throws DeltaSkinException {
        Size mapping = representation.mapping;
        positive(mapping.width, Code.INVALID_MAPPING_SIZE, "La largeur mappingSize est invalide");
        positive(mapping.height, Code.INVALID_MAPPING_SIZE, "La hauteur mappingSize est invalide");
        String asset = representation.resizable;
        if (asset == null || asset.trim().isEmpty() || !isSafeRootAssetName(asset)
                || !asset.toLowerCase(Locale.ROOT).endsWith(".pdf"))
            throw new DeltaSkinException(Code.RESIZABLE_ASSET_MISSING,
                    "La représentation portrait ne référence pas un PDF resizable valide");
        validateEdges(representation.extendedEdges);
        for (Item item : representation.items) {
            validateFrame(item.frame, mapping, true);
            validateEdges(item.extendedEdges);
            validateInputs(item, console, ignored);
        }
        if (representation.gameScreenFrame != null) validateFrame(representation.gameScreenFrame, mapping, true);
        for (Screen screen : representation.screens) {
            if (screen.inputFrame != null) validateFrame(screen.inputFrame, null, false);
            validateFrame(screen.outputFrame, mapping, true);
        }
    }

    private static void validateInputs(Item item, Console console, Set<String> ignored) throws DeltaSkinException {
        List<String> values;
        if (item.directional()) {
            if (!item.directions.keySet().containsAll(Arrays.asList("up", "down", "left", "right")))
                throw new DeltaSkinException(Code.MISSING_REQUIRED_PROPERTY,
                        "Un D-pad doit déclarer up, down, left et right");
            values = new ArrayList<>(item.directions.values());
        } else {
            if (item.buttons.isEmpty())
                throw new DeltaSkinException(Code.MISSING_REQUIRED_PROPERTY, "Un item doit contenir au moins une entrée");
            values = item.buttons;
        }
        for (String raw : values) if (!supported(console, raw)) ignored.add(raw);
    }

    private static void validateEdges(Edges edges) throws DeltaSkinException {
        for (Double value : new Double[]{edges.top, edges.bottom, edges.left, edges.right})
            if (value != null) nonNegative(value, Code.INVALID_NUMBER, "Une valeur extendedEdges est invalide");
    }

    private static void validateFrame(Frame frame, Size mapping, boolean mustFit) throws DeltaSkinException {
        nonNegative(frame.x, Code.INVALID_FRAME, "La coordonnée x d'une frame est invalide");
        nonNegative(frame.y, Code.INVALID_FRAME, "La coordonnée y d'une frame est invalide");
        positive(frame.width, Code.INVALID_FRAME, "La largeur d'une frame est invalide");
        positive(frame.height, Code.INVALID_FRAME, "La hauteur d'une frame est invalide");
        if (mustFit && mapping != null && (frame.x >= mapping.width || frame.y >= mapping.height
                || frame.x + frame.width > extent(mapping.width) || frame.y + frame.height > extent(mapping.height)))
            throw new DeltaSkinException(Code.INVALID_FRAME, "Une frame dépasse mappingSize");
    }

    /** Some skins round an edge frame a little past their mapping size; the
     * hit box is then bounded by the panel. */
    private static double extent(double mapping) {
        return mapping + Math.max(FRAME_EPSILON, mapping * MAX_FRAME_OVERSCAN_RATIO);
    }

    private static void requireText(String value, String property) throws DeltaSkinException {
        if (value == null || value.trim().isEmpty() || value.length() > MAX_TEXT_LENGTH || value.indexOf('\u0000') >= 0)
            throw new DeltaSkinException(Code.MISSING_REQUIRED_PROPERTY,
                    "La propriété " + property + " est vide ou excessive");
    }

    private static void positive(double value, Code code, String message) throws DeltaSkinException {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0 || value > MAX_LOGICAL_VALUE)
            throw new DeltaSkinException(code, message);
    }

    private static void nonNegative(double value, Code code, String message) throws DeltaSkinException {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0 || value > MAX_LOGICAL_VALUE)
            throw new DeltaSkinException(code, message);
    }
}
