package com.ravenemu.app.ui.player;

import com.ravenemu.app.data.skins.DeltaSkin.Frame;
import com.ravenemu.app.data.skins.DeltaSkin.Kind;
import com.ravenemu.app.data.skins.DeltaSkin.Size;

import java.util.Set;

/** Where a Delta skin goes in the player, after the beta's
 * DeltaSkinLayoutCalculator and DeltaSkinRepresentationSelector: the skin's
 * panel as wide as the area and at its bottom, the game's area above it, and
 * the picture centred in that area at the console's aspect. Pure geometry. */
final class DeltaSkinLayout {
    /** A tall phone (twice as high as wide) takes the edge-to-edge representation. */
    static final double EDGE_TO_EDGE_ASPECT = 2.0;

    final Box panel, gameArea, gameScreen;
    final double scale;

    private DeltaSkinLayout(Box panel, Box gameArea, Box gameScreen, double scale) {
        this.panel = panel;
        this.gameArea = gameArea;
        this.gameScreen = gameScreen;
        this.scale = scale;
    }

    static final class Box {
        final double left, top, right, bottom;

        Box(double left, double top, double right, double bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        double width() { return right - left; }
        double height() { return bottom - top; }

        boolean contains(double x, double y) { return x >= left && x <= right && y >= top && y <= bottom; }

        Box intersect(Box other) {
            Box result = new Box(Math.max(left, other.left), Math.max(top, other.top),
                    Math.min(right, other.right), Math.min(bottom, other.bottom));
            return result.width() > 0 && result.height() > 0 ? result : null;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Box)) return false;
            Box box = (Box) other;
            return box.left == left && box.top == top && box.right == right && box.bottom == bottom;
        }

        @Override public int hashCode() {
            return Double.hashCode(left) * 31 * 31 * 31 + Double.hashCode(top) * 31 * 31
                    + Double.hashCode(right) * 31 + Double.hashCode(bottom);
        }
    }

    /** The preferred kind for this area, or the other one when the skin lacks it; null without any. */
    static Kind select(Set<Kind> available, double width, double height) {
        if (available.isEmpty()) return null;
        double ratio = width > 0 ? height / width : 0;
        Kind preferred = ratio >= EDGE_TO_EDGE_ASPECT ? Kind.EDGE_TO_EDGE : Kind.STANDARD;
        if (available.contains(preferred)) return preferred;
        Kind other = preferred == Kind.STANDARD ? Kind.EDGE_TO_EDGE : Kind.STANDARD;
        return available.contains(other) ? other : null;
    }

    /** Null when the skin cannot fit: no room left above its panel. */
    static DeltaSkinLayout calculate(double width, double height, Size mapping, double screenWidth,
                                     double screenHeight) {
        if (width <= 0 || height <= 0 || mapping.width <= 0 || mapping.height <= 0 || screenWidth <= 0
                || screenHeight <= 0) return null;
        double scale = width / mapping.width;
        double panelTop = height - mapping.height * scale;
        if (panelTop <= 0) return null;
        Box panel = new Box(0, panelTop, width, height);
        Box area = new Box(0, 0, width, panelTop);
        double gameScale = Math.min(area.width() / screenWidth, area.height() / screenHeight);
        if (Double.isNaN(gameScale) || Double.isInfinite(gameScale) || gameScale <= 0) return null;
        double gameWidth = screenWidth * gameScale, gameHeight = screenHeight * gameScale;
        double left = (area.width() - gameWidth) / 2, top = (area.height() - gameHeight) / 2;
        return new DeltaSkinLayout(panel, area, new Box(left, top, left + gameWidth, top + gameHeight), scale);
    }

    /** A frame of the skin, in the panel's place. */
    static Box map(Frame frame, Box panel, Size mapping) {
        double sx = panel.width() / mapping.width, sy = panel.height() / mapping.height;
        return new Box(panel.left + frame.x * sx, panel.top + frame.y * sy,
                panel.left + (frame.x + frame.width) * sx, panel.top + (frame.y + frame.height) * sy);
    }
}
