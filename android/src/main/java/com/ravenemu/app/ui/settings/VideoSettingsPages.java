package com.ravenemu.app.ui.settings;

import android.widget.Button;
import com.ravenemu.app.settings.PlaybackSettings;
import com.ravenemu.app.ui.common.RavenTheme;

import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.VIDEO;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.VIDEO_COLORS;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.VIDEO_PICTURE;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.VIDEO_RENDERING;

/** Settings → Vidéo: colours, rendering, size and picture adjustments. */
final class VideoSettingsPages {
    private final PlaybackSettingsActivity activity;
    private final SettingsWidgets widgets;
    private final RavenTheme theme;

    VideoSettingsPages(PlaybackSettingsActivity activity, SettingsWidgets widgets) {
        this.activity = activity;
        this.widgets = widgets;
        this.theme = activity.theme();
    }

    /** The beta's Vidéo groups, by theme. Its reset brings every one back. */
    void videoPage() {
        widgets.category("Couleurs", "Profil Game Boy et correction LCD", VIDEO_COLORS, null);
        widgets.category("Rendu", "Mélange d'images et effet d'écran", VIDEO_RENDERING, null);
        widgets.category("Taille et réglages", "Pixels entiers, luminosité et contraste", VIDEO_PICTURE, null);
        Button reset = widgets.button("Rétablir l'affichage d'origine");
        reset.setTextColor(theme.accent);
        reset.setOnClickListener(view -> {
            activity.update(activity.settings.withPicture(false, 0, 100).withImage(PlaybackSettings.PALETTE_ORIGINAL,
                    false, false, PlaybackSettings.BLENDING_OFF).withEffect(PlaybackSettings.EFFECT_NONE));
            activity.showPage(VIDEO);
        });
        widgets.add(reset, 14);
    }

    /** The beta's wording. The colors, the LCD correction and the frame
     * blending are RavenCore's, set through its Libretro options. */
    void videoColorsPage() {
        widgets.group("Écran");
        widgets.description("Profil de couleurs",
                "Original garde le vert de la Game Boy ; Pocket gris imite la Game Boy Pocket ; "
                        + "Gris neutre passe en niveaux de gris purs. Ces profils ne concernent que les jeux "
                        + "Game Boy : les jeux Game Boy Color et Game Boy Advance gardent toujours leurs couleurs.");
        widgets.choices(new String[]{"Original", "Pocket gris", "Gris neutre"},
                new String[]{PlaybackSettings.PALETTE_ORIGINAL, PlaybackSettings.PALETTE_POCKET,
                        PlaybackSettings.PALETTE_GRAY},
                activity.settings.gbPalette, palette -> activity.update(activity.settings.withImage(palette,
                        activity.settings.lcdCorrectionGbc, activity.settings.lcdCorrectionGba, activity.settings.frameBlending)));
        widgets.group("Correction des couleurs LCD");
        widgets.settingSwitch("Game Boy Color",
                "Atténue les couleurs très vives pour retrouver l'aspect plus mat de l'écran d'origine.",
                activity.settings.lcdCorrectionGbc, enabled -> activity.update(activity.settings.withImage(activity.settings.gbPalette,
                        enabled, activity.settings.lcdCorrectionGba, activity.settings.frameBlending)));
        widgets.settingSwitch("Game Boy Advance",
                "Même correction, réglée à part pour la Game Boy Advance.",
                activity.settings.lcdCorrectionGba, enabled -> activity.update(activity.settings.withImage(activity.settings.gbPalette,
                        activity.settings.lcdCorrectionGbc, enabled, activity.settings.frameBlending)));
    }

    void videoRenderingPage() {
        widgets.group("Mélange d'images");
        widgets.description("Rémanence de l'écran",
                "Certains jeux font clignoter un objet d'une image à l'autre pour le rendre transparent, "
                        + "en comptant sur la lenteur de l'écran d'origine (F-Zero, Castlevania). Le mélange le "
                        + "montre transparent ; la rémanence laisse une traîne.");
        widgets.choices(new String[]{"Désactivé (défaut)", "Mélange de deux images", "Rémanence"},
                new String[]{PlaybackSettings.BLENDING_OFF, PlaybackSettings.BLENDING_MIX,
                        PlaybackSettings.BLENDING_GHOSTING},
                activity.settings.frameBlending, blending -> activity.update(activity.settings.withImage(activity.settings.gbPalette,
                        activity.settings.lcdCorrectionGbc, activity.settings.lcdCorrectionGba, blending)));
        // Native additions after two public-domain RetroArch shaders
        // (sharp-bilinear, lcd3x), drawn by the GLES2 driver.
        widgets.group("Effet d'écran");
        widgets.description("Rendu des pixels",
                "Le lissage net garde des pixels nets et réguliers quelle que soit la taille de l'image ; "
                        + "la grille LCD imite la trame de l'écran d'une console portable.");
        widgets.choices(new String[]{"Aucun (défaut)", "Lissage net", "Grille LCD"},
                new String[]{PlaybackSettings.EFFECT_NONE, PlaybackSettings.EFFECT_SHARP, PlaybackSettings.EFFECT_LCD},
                activity.settings.screenEffect, effect -> activity.update(activity.settings.withEffect(effect)));
    }

    void videoPicturePage() {
        widgets.group("Taille de l'image");
        widgets.settingSwitch("Pixels entiers",
                "L'image prend un multiple entier de sa taille : tous les pixels sont identiques, avec des bords noirs autour.",
                activity.settings.integerScale, enabled -> activity.update(activity.settings.withPicture(
                        enabled, activity.settings.brightness, activity.settings.contrast)));
        widgets.group("Ajustements");
        widgets.slider("Luminosité", activity.settings.brightness, -50, 50, false, value ->
                activity.update(activity.settings.withPicture(activity.settings.integerScale, value, activity.settings.contrast)));
        widgets.slider("Contraste", activity.settings.contrast, 50, 150, true, value ->
                activity.update(activity.settings.withPicture(activity.settings.integerScale, activity.settings.brightness, value)));
    }
}
