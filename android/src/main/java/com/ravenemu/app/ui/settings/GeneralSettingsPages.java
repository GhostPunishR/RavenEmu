package com.ravenemu.app.ui.settings;

import com.ravenemu.app.settings.LatencySettings;

import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.GENERAL_EMULATION;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.GENERAL_LATENCY;

/** Settings → Général: the automatic pause and the latency. */
final class GeneralSettingsPages {
    private final PlaybackSettingsActivity activity;
    private final SettingsWidgets widgets;

    GeneralSettingsPages(PlaybackSettingsActivity activity, SettingsWidgets widgets) {
        this.activity = activity;
        this.widgets = widgets;
    }

    /** The beta's Général page; its link cable, online covers and diagnostics
     * wait for those native features, as the beta hides them without them. */
    void generalPage() {
        widgets.category("Émulation", "Pause automatique", GENERAL_EMULATION, null);
        widgets.category("Latence", "Images préventives", GENERAL_LATENCY, null);
    }

    void generalEmulationPage() {
        widgets.description("Pause automatique",
                "Le jeu et son audio sont suspendus lorsque RavenEmu passe en arrière-plan ou lorsque le "
                        + "panneau de jeu est ouvert. Ce comportement est fixe pour protéger la partie.");
    }

    /** A native addition after RetroArch's preemptive frames (the C frontend). */
    void generalLatencyPage() {
        widgets.description("Images préventives",
                "Quand une touche change, RavenEmu rejoue aussitôt les dernières images avec elle : le jeu "
                        + "répond une à trois images plus tôt (environ 17 ms chacune). Chaque appui fait émuler "
                        + "ces images en plus ; si le téléphone ne suit pas, le réglage baisse de lui-même pour "
                        + "le jeu en cours. Au-delà du retard propre au jeu, l'image saute : commencez par une.");
        widgets.choices(new String[]{"Désactivées (défaut)", "1 image", "2 images", "3 images"},
                new String[]{"0", "1", "2", "3"}, String.valueOf(LatencySettings.load(activity).preemptiveFrames),
                value -> new LatencySettings(Integer.parseInt(value)).save(activity));
    }
}
