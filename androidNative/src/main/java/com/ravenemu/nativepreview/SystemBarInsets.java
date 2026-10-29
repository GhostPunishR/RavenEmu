package com.ravenemu.nativepreview;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.graphics.Insets;

/**
 * Protects RavenEmu's Java layouts from Android's status/navigation bars,
 * display cutouts and the keyboard. Android 15 enforces edge-to-edge for
 * targetSdk 35, so hard-coded top/bottom margins are not sufficient.
 *
 * The padding is always recomputed from the original values: dispatching
 * insets repeatedly must not shift the toolbar or footer twice.
 */
final class SystemBarInsets {
    private SystemBarInsets() {}

    static int[] contentPadding(int[] base, int[] bars, int[] ime) {
        if (base == null || bars == null || ime == null ||
                base.length != 4 || bars.length != 4 || ime.length != 4)
            throw new IllegalArgumentException("Expected left/top/right/bottom insets");
        return new int[]{
                base[0] + Math.max(0, bars[0]),
                base[1] + Math.max(0, bars[1]),
                base[2] + Math.max(0, bars[2]),
                base[3] + Math.max(Math.max(0, bars[3]), Math.max(0, ime[3]))
        };
    }

    /** Must be called before setContentView, once per Activity root. */
    static void install(Activity activity, View root) {
        Window window = activity.getWindow();
        // install runs before setContentView: create the decor before asking
        // PhoneWindow for its insets controller (API 30+).
        View decor = window.getDecorView();
        window.setStatusBarColor(0xff000000);
        window.setNavigationBarColor(0xff000000);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            // Consistent edge-to-edge measurement on Android 8-10, too.
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        final int[] original = {
                root.getPaddingLeft(), root.getPaddingTop(),
                root.getPaddingRight(), root.getPaddingBottom()
        };
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            final int[] bars;
            final int[] ime;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets system = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                bars = new int[]{system.left, system.top, system.right, system.bottom};
                ime = new int[]{keyboard.left, keyboard.top, keyboard.right, keyboard.bottom};
            } else {
                bars = new int[]{
                        insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()
                };
                ime = new int[]{0, 0, 0, 0};
            }
            int[] p = contentPadding(original, bars, ime);
            view.setPadding(p[0], p[1], p[2], p[3]);
            return insets; // Preserve Insets for children and Android system views.
        });
        root.requestApplyInsets();
    }
}
