package com.ravenemu.app.ui.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.ravenemu.app.data.saves.GbaBiosRepository;
import com.ravenemu.app.settings.ConsoleSettings;
import com.ravenemu.app.ui.common.RavenTheme;
import java.io.IOException;
import java.io.InputStream;

import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.CONSOLE_GB;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.CONSOLE_GBA;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.PICK_BIOS;

/** Settings → Console: the Game Boy's colours, the GBC of a GBA and the GBA BIOS. */
final class ConsoleSettingsPages {
    private final PlaybackSettingsActivity activity;
    private final SettingsWidgets widgets;
    private final RavenTheme theme;
    private ConsoleSettings console;
    private boolean biosInstalled;

    ConsoleSettingsPages(PlaybackSettingsActivity activity, SettingsWidgets widgets) {
        this.activity = activity;
        this.widgets = widgets;
        this.theme = activity.theme();
        console = ConsoleSettings.load(activity);
        biosInstalled = GbaBiosRepository.installed(activity.getFilesDir());
    }

    /** The beta's Console page: the console that runs the games. Taken at the
     * game's launch, except the Game Boy colors, which change at once. */
    void consolePage() {
        widgets.category("Game Boy et GBC", "Jeux Game Boy en couleurs et GBC d'une GBA", CONSOLE_GB, null);
        widgets.category("Game Boy Advance", "BIOS et son fichier", CONSOLE_GBA, null);
    }

    void consoleGameBoyPage() {
        LinearLayout[] colors = new LinearLayout[1];
        widgets.settingSwitch("Jeux Game Boy en couleurs",
                "Les jeux Game Boy tournent sur une Game Boy Color, qui leur donne des couleurs : celles que sa "
                        + "boot ROM choisit pour chaque jeu (Pokémon Rouge en rouge, Bleu en bleu…), ou celles "
                        + "choisies ci-dessous. Un état pris dans l'autre mode ne se recharge pas. Pris en compte "
                        + "au lancement du jeu.",
                console.gbInColor, enabled -> {
                    updateConsole(console.withGbInColor(enabled));
                    widgets.setChoicesEnabled(colors[0], enabled);
                });
        widgets.description("Couleurs des jeux Game Boy",
                "Les combinaisons sont celles que l'on tenait pendant le logo de la console. Changées aussitôt, "
                        + "même en jeu.");
        String[] indices = new String[ConsoleSettings.GB_COLORS.length];
        for (int i = 0; i < indices.length; i++) indices[i] = String.valueOf(i);
        colors[0] = widgets.choices(ConsoleSettings.GB_COLORS, indices, String.valueOf(console.gbColors),
                value -> updateConsole(console.withGbColors(Integer.parseInt(value))));
        widgets.setChoicesEnabled(colors[0], console.gbInColor);
        widgets.settingSwitch("Game Boy Color d'une Game Boy Advance",
                "La GBC intégrée à une Game Boy Advance : des jeux comme Zelda Oracle ou Shantae débloquent alors "
                        + "leurs bonus. Un état pris avec l'autre console ne se recharge pas. Pris en compte au "
                        + "lancement du jeu.",
                console.gbcOnGba, enabled -> updateConsole(console.withGbcOnGba(enabled)));
    }

    void consoleAdvancePage() {
        widgets.description("BIOS",
                "RavenCore répond lui-même aux appels du BIOS. Le vrai BIOS, celui de votre console, joue son "
                        + "animation de démarrage (sauf si vous la sautez) : choisissez son fichier (16 Kio), aucun "
                        + "BIOS n'est fourni. Un état ne se recharge qu'avec le BIOS qui l'a créé. Pris en compte au "
                        + "lancement du jeu.");
        widgets.choices(new String[]{"BIOS de RavenCore (défaut)", "Vrai BIOS", "Vrai BIOS, sans animation"},
                new String[]{String.valueOf(ConsoleSettings.BIOS_RAVENCORE), String.valueOf(ConsoleSettings.BIOS_REAL),
                        String.valueOf(ConsoleSettings.BIOS_REAL_SKIP_INTRO)},
                String.valueOf(console.gbaBios), value -> updateConsole(console.withGbaBios(Integer.parseInt(value))));
        TextView file = widgets.text(biosInstalled ? "Fichier du BIOS : installé" : "Fichier du BIOS : absent", 13,
                biosInstalled ? theme.text : theme.muted);
        widgets.add(file, 10);
        Button choose = widgets.textButton(biosInstalled ? "Remplacer le fichier du BIOS" : "Choisir le fichier du BIOS",
                theme.accent);
        choose.setOnClickListener(view -> pickBios());
        if (biosInstalled) {
            Button remove = widgets.textButton("Retirer le fichier du BIOS", theme.muted);
            remove.setOnClickListener(view -> activity.io.execute(() -> {
                GbaBiosRepository.remove(activity.getFilesDir());
                biosChanged(null);
            }));
        }
    }

    private void updateConsole(ConsoleSettings updated) {
        console = updated;
        console.save(activity);
    }

    private void pickBios() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        try {
            activity.startActivityForResult(pick, PICK_BIOS);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(activity, "Aucune application pour choisir un fichier", Toast.LENGTH_LONG).show();
        }
    }

    /** The file picked for the BIOS, copied off the UI thread. */
    void biosPicked(Uri uri) {
        activity.io.execute(() -> {
            String message = "BIOS GBA installé";
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                GbaBiosRepository.install(activity.getFilesDir(), input);
            } catch (IOException | RuntimeException error) {
                message = error.getMessage() != null ? error.getMessage() : "BIOS GBA non installé";
            }
            biosChanged(message);
        });
    }

    /** IO thread: shows the file's new state, and the message if any. */
    private void biosChanged(String message) {
        boolean installed = GbaBiosRepository.installed(activity.getFilesDir());
        activity.runOnUiThread(() -> {
            if (activity.isDestroyed()) return;
            biosInstalled = installed;
            if (message != null) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            if (CONSOLE_GBA.equals(activity.page())) activity.showPage(CONSOLE_GBA);
        });
    }
}
