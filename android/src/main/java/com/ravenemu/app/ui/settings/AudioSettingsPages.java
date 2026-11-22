package com.ravenemu.app.ui.settings;

import android.widget.SeekBar;
import com.ravenemu.app.settings.PlaybackSettings;
import com.ravenemu.app.ui.common.RavenTheme;

import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.AUDIO_QUALITY;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.AUDIO_SOUND;

/** Settings → Audio: the game's sound, its volume and the resampler. */
final class AudioSettingsPages {
    private final PlaybackSettingsActivity activity;
    private final SettingsWidgets widgets;
    private final RavenTheme theme;
    private SeekBar volume;

    AudioSettingsPages(PlaybackSettingsActivity activity, SettingsWidgets widgets) {
        this.activity = activity;
        this.widgets = widgets;
        this.theme = activity.theme();
    }

    void audioPage() {
        widgets.category("Son du jeu", "Activer le son et volume", AUDIO_SOUND, null);
        widgets.category("Qualité du son", "Rééchantillonnage sinc ou linéaire", AUDIO_QUALITY, null);
    }

    void audioSoundPage() {
        widgets.settingSwitch("Activer le son",
                "Conserve l'horloge d'émulation et coupe uniquement la sortie audio.",
                activity.settings.audioEnabled, enabled -> {
                    activity.update(activity.settings.withAudio(enabled, activity.settings.audioVolume));
                    volume.setEnabled(enabled);
                });
        volume = widgets.slider("Volume", activity.settings.audioVolume, 0, 100, true, value ->
                activity.update(activity.settings.withAudio(activity.settings.audioEnabled, value)));
        volume.setEnabled(activity.settings.audioEnabled);
        // The beta's note: the native audio driver fades in the same way.
        widgets.add(widgets.text("Le fondu de démarrage de 180 ms reste inchangé.", 12, theme.muted), 8);
    }

    /** A native addition: the beta's player had linear interpolation only. */
    void audioQualityPage() {
        widgets.description("Rééchantillonnage",
                "Le son du jeu est converti à la fréquence du téléphone. Le filtre sinc garde les notes "
                        + "aiguës nettes, sans le sifflement métallique de l'interpolation linéaire, pour moins "
                        + "de 1 % d'un cœur du processeur ; l'interpolation linéaire reste au choix pour comparer.");
        widgets.choices(new String[]{"Sinc (défaut)", "Linéaire"},
                new String[]{PlaybackSettings.RESAMPLER_SINC, PlaybackSettings.RESAMPLER_LINEAR},
                activity.settings.resampler, value -> activity.update(activity.settings.withResampler(value)));
    }
}
