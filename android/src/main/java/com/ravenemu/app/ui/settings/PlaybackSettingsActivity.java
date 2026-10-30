package com.ravenemu.app.ui.settings;
import com.ravenemu.app.R;
import com.ravenemu.app.data.saves.GbaBiosRepository;
import com.ravenemu.app.platform.SystemBarInsets;
import com.ravenemu.app.settings.CartridgeSettings;
import com.ravenemu.app.settings.ConsoleSettings;
import com.ravenemu.app.settings.ControlLayout;
import com.ravenemu.app.settings.ControlSettings;
import com.ravenemu.app.settings.LatencySettings;
import com.ravenemu.app.settings.PlaybackSettings;
import com.ravenemu.app.ui.library.HomeActivity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;
import androidx.core.graphics.PathParser;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Java Views counterpart of the beta's settings: Général, Audio, Vidéo,
 * Console, Interface and À propos. The library's ⚙ opens it on its categories.
 * Only settings connected to the native drivers are presented here. */
public final class PlaybackSettingsActivity extends Activity {
    public static final String EXTRA_PAGE = "playback.settings.page";
    public static final String HOME = "home";
    public static final String AUDIO = "audio";
    public static final String VIDEO = "video";
    public static final String CONSOLE = "console";
    public static final String INTERFACE = "interface";
    public static final String GENERAL = "general";
    public static final String ABOUT = "about";
    /** Each category lists its sub-pages; "category/sub-page" goes back to its category. */
    public static final String GENERAL_EMULATION = "general/emulation";
    public static final String GENERAL_LATENCY = "general/latency";
    public static final String AUDIO_SOUND = "audio/sound";
    public static final String AUDIO_QUALITY = "audio/quality";
    public static final String VIDEO_COLORS = "video/colors";
    public static final String VIDEO_RENDERING = "video/rendering";
    public static final String VIDEO_PICTURE = "video/picture";
    public static final String CONSOLE_GB = "console/gb";
    public static final String CONSOLE_GBA = "console/gba";
    public static final String INTERFACE_CONTROLS = "interface/controls";
    public static final String INTERFACE_CARTRIDGE = "interface/cartridge";
    private static final String[] PAGES = {HOME, AUDIO, VIDEO, CONSOLE, INTERFACE, GENERAL, ABOUT,
            GENERAL_EMULATION, GENERAL_LATENCY, AUDIO_SOUND, AUDIO_QUALITY, VIDEO_COLORS, VIDEO_RENDERING,
            VIDEO_PICTURE, CONSOLE_GB, CONSOLE_GBA, INTERFACE_CONTROLS, INTERFACE_CARTRIDGE};
    private static final String[] TITLES = {"Paramètres", "Audio", "Vidéo", "Console", "Interface",
            "Général", "À propos", "Émulation", "Latence", "Son du jeu", "Qualité du son", "Couleurs",
            "Rendu", "Taille et réglages", "Game Boy et GBC", "Game Boy Advance", "Commandes tactiles",
            "Cartouche"};
    private static final String REPOSITORY = "https://github.com/GhostPunishR/RavenEmu";
    private static final int PICK_BIOS = 1;

    private PlaybackSettings settings;
    private ConsoleSettings console;
    private ControlSettings controls;
    private CartridgeSettings cartridge;
    private boolean biosInstalled;
    /** The BIOS copy and removal, off the UI thread, one at a time. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private ScrollView scroll;
    private TextView title;
    private Button back;
    private SeekBar volume;
    private String page;
    private final int[] scrollPositions = new int[PAGES.length];
    private int pageSerial;
    private boolean rendered;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = PlaybackSettings.load(this);
        console = ConsoleSettings.load(this);
        controls = ControlSettings.load(this);
        cartridge = CartridgeSettings.load(this);
        biosInstalled = GbaBiosRepository.installed(getFilesDir());
        String initialPage = getIntent().getStringExtra(EXTRA_PAGE);
        if (state != null) {
            initialPage = state.getString("settings.page", initialPage);
            for (int i = 0; i < PAGES.length; i++)
                scrollPositions[i] = Math.max(0, state.getInt("settings.scroll." + PAGES[i]));
        }

        LinearLayout root = column();
        root.setTag("playback.settings.root");
        root.setBackgroundColor(HomeActivity.BACKGROUND);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(HomeActivity.SURFACE);
        back = button("‹");
        back.setTextSize(30);
        back.setContentDescription("Retour aux paramètres");
        back.setOnClickListener(view -> navigateBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), -1));
        title = text("", 20, HomeActivity.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(54)));

        scroll = new ScrollView(this);
        scroll.setTag("playback.settings.scroll");
        scroll.setSaveEnabled(false);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(dp(18), dp(12), dp(18), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        SystemBarInsets.install(this, root);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        showPage(initialPage);
    }

    private void showPage(String requestedPage) {
        if (rendered) scrollPositions[index(page)] = scroll.getScrollY();
        page = PAGES[index(requestedPage)];
        rendered = true;
        final int request = ++pageSerial;
        title.setText(TITLES[index(page)]);
        final String parent = parent(page);
        back.setContentDescription(HOME.equals(page) ? "Fermer les paramètres"
                : HOME.equals(parent) ? "Retour aux paramètres"
                : "Retour aux réglages " + TITLES[index(parent)].toLowerCase(Locale.ROOT));
        content.removeAllViews();
        switch (page) {
            case AUDIO: audioPage(); break;
            case VIDEO: videoPage(); break;
            case CONSOLE: consolePage(); break;
            case INTERFACE: interfacePage(); break;
            case GENERAL: generalPage(); break;
            case ABOUT: aboutPage(); break;
            case GENERAL_EMULATION: generalEmulationPage(); break;
            case GENERAL_LATENCY: generalLatencyPage(); break;
            case AUDIO_SOUND: audioSoundPage(); break;
            case AUDIO_QUALITY: audioQualityPage(); break;
            case VIDEO_COLORS: videoColorsPage(); break;
            case VIDEO_RENDERING: videoRenderingPage(); break;
            case VIDEO_PICTURE: videoPicturePage(); break;
            case CONSOLE_GB: consoleGameBoyPage(); break;
            case CONSOLE_GBA: consoleAdvancePage(); break;
            case INTERFACE_CONTROLS: interfaceControlsPage(); break;
            case INTERFACE_CARTRIDGE: interfaceCartridgePage(); break;
            default: homePage();
        }
        final int restoreScroll = scrollPositions[index(page)];
        scroll.post(() -> {
            if (!isFinishing() && !isDestroyed() && request == pageSerial) scroll.scrollTo(0, restoreScroll);
        });
    }

    /** The beta's categories, in its order; RetroAchievements waits for its native port. */
    private void homePage() {
        category("Général", "Pause automatique et latence", GENERAL,
                "M3,5H13V7H3ZM17,5H21V7H17ZM13,3H17V9H13ZM3,11H7V13H3ZM11,11H21V13H11ZM7,9H11V15H7Z"
                        + "M3,17H13V19H3ZM17,17H21V19H17ZM13,15H17V21H13Z");
        category("Audio", "Son du jeu, volume et qualité", AUDIO,
                "M3,9H7L12,4V20L7,15H3ZM15,8V16C17,15 17,9 15,8ZM15,3V5C21,7 21,17 15,19V21C24,18 24,6 15,3Z");
        category("Vidéo", "Couleurs, rendu, taille, luminosité et contraste", VIDEO,
                "M3,3H21V17H13V19H17V21H7V19H11V17H3ZM5,5V15H19V5Z");
        // The beta draws Console with its default (About) icon.
        category("Console", "Jeux Game Boy en couleurs, GBC d'une GBA, BIOS GBA", CONSOLE,
                "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z"
                        + "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z");
        // The beta says "Vibrations, capteurs, commandes, retour arrière, style et
        // Skin Delta": the owner left rewind out of the native player, and the
        // Delta skins wait for their port, so neither is announced.
        category("Interface", "Vibrations, capteurs, commandes et style", INTERFACE,
                "M3,3H11V11H3ZM13,3H21V11H13ZM3,13H11V21H3ZM13,13H21V21H13Z");
        category("À propos", "Version, auteur, confidentialité et licences", ABOUT,
                "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z"
                        + "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z");
    }

    /** The beta's Général page; its link cable, online covers and diagnostics
     * wait for those native features, as the beta hides them without them. */
    private void generalPage() {
        category("Émulation", "Pause automatique", GENERAL_EMULATION, null);
        category("Latence", "Images préventives", GENERAL_LATENCY, null);
    }

    private void generalEmulationPage() {
        description("Pause automatique",
                "Le jeu et son audio sont suspendus lorsque RavenEmu passe en arrière-plan ou lorsque le "
                        + "panneau de jeu est ouvert. Ce comportement est fixe pour protéger la partie.");
    }

    /** A native addition after RetroArch's preemptive frames (the C frontend). */
    private void generalLatencyPage() {
        description("Images préventives",
                "Quand une touche change, RavenEmu rejoue aussitôt les dernières images avec elle : le jeu "
                        + "répond une à trois images plus tôt (environ 17 ms chacune). Chaque appui fait émuler "
                        + "ces images en plus ; si le téléphone ne suit pas, le réglage baisse de lui-même pour "
                        + "le jeu en cours. Au-delà du retard propre au jeu, l'image saute : commencez par une.");
        choices(new String[]{"Désactivées (défaut)", "1 image", "2 images", "3 images"},
                new String[]{"0", "1", "2", "3"}, String.valueOf(LatencySettings.load(this).preemptiveFrames),
                value -> new LatencySettings(Integer.parseInt(value)).save(this));
    }

    /** The beta's À propos: who makes RavenEmu, its licences and its source.
     * The terms and the privacy policy are read online until the native
     * reader carries them. */
    private void aboutPage() {
        TextView name = text("RavenEmu", 18, HomeActivity.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        add(name, 0);
        add(text("Par GhostPunishR · version native expérimentale Java / C / C++", 13, HomeActivity.MUTED), 4);
        group("Informations légales");
        aboutRow("Conditions d'utilisation", "Ce que RavenEmu fournit, et votre responsabilité", "↗",
                view -> openLink(REPOSITORY + "/blob/main/TERMS.md"));
        aboutRow("Politique de confidentialité", "Données échangées et services tiers", "↗",
                view -> openLink(REPOSITORY + "/blob/main/PRIVACY.md"));
        group("Licences et crédits");
        aboutRow("Licences et crédits", "Crédits, GPLv3, AGPLv3, MIT et Apache 2, lus dans l'application", "›",
                view -> startActivity(new Intent(this, LicensesActivity.class)));
        group("Code source");
        aboutRow("RavenEmu", "github.com/GhostPunishR/RavenEmu", "↗", view -> openLink(REPOSITORY));
        aboutRow("RavenCore", "github.com/GhostPunishR/RavenCore", "↗",
                view -> openLink("https://github.com/GhostPunishR/RavenCore"));
    }

    private void aboutRow(String label, String summary, String trailing, View.OnClickListener open) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout description = column();
        description.addView(text(label, 15, HomeActivity.TEXT));
        add(description, text(summary, 12, HomeActivity.MUTED), 3);
        row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mark = text(trailing, 18, HomeActivity.ACCENT);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark);
        row.setContentDescription(label + ". " + summary);
        row.setOnClickListener(open);
        add(row, 6);
    }

    private void openLink(String address) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, "Aucune application pour ouvrir ce lien", Toast.LENGTH_LONG).show();
        }
    }

    private void category(String label, String summary, String destination, String iconPath) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(15), dp(14), dp(15));
        row.setMinimumHeight(dp(72));
        GradientDrawable surface = shape(HomeActivity.SURFACE);
        surface.setCornerRadius(dp(16));
        surface.setStroke(dp(1), 0xb33c4052);
        row.setBackground(surface);
        row.setContentDescription("Ouvrir les réglages " + label.toLowerCase(Locale.ROOT));
        row.setOnClickListener(view -> showPage(destination));

        // The categories carry the beta's icons; their sub-pages, text alone.
        if (iconPath != null) {
            FrameLayout iconBox = new FrameLayout(this);
            iconBox.setBackground(shape(0x21a78bfa));
            iconBox.addView(new SettingsIcon(this, iconPath),
                    new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            row.addView(iconBox, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }
        LinearLayout description = column();
        TextView name = text(label, 16, HomeActivity.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        description.addView(name);
        add(description, text(summary, 12, HomeActivity.MUTED), 4);
        LinearLayout.LayoutParams details = new LinearLayout.LayoutParams(0, -2, 1);
        details.setMarginStart(iconPath != null ? dp(13) : dp(4));
        details.setMarginEnd(dp(13));
        row.addView(description, details);
        row.addView(text("›", 23, HomeActivity.ACCENT));
        add(row, content.getChildCount() == 0 ? 0 : 14);
    }

    /** A sub-page goes back to its category, a category to the categories. */
    private void navigateBack() {
        if (HOME.equals(page)) finish(); else showPage(parent(page));
    }

    private static String parent(String page) {
        final int slash = page.indexOf('/');
        return slash < 0 ? HOME : page.substring(0, slash);
    }

    @Override public void onBackPressed() { navigateBack(); }

    private void audioPage() {
        category("Son du jeu", "Activer le son et volume", AUDIO_SOUND, null);
        category("Qualité du son", "Rééchantillonnage sinc ou linéaire", AUDIO_QUALITY, null);
    }

    private void audioSoundPage() {
        settingSwitch("Activer le son",
                "Conserve l'horloge d'émulation et coupe uniquement la sortie audio.",
                settings.audioEnabled, enabled -> {
                    update(settings.withAudio(enabled, settings.audioVolume));
                    volume.setEnabled(enabled);
                });
        volume = slider("Volume", settings.audioVolume, 0, 100, true, value ->
                update(settings.withAudio(settings.audioEnabled, value)));
        volume.setEnabled(settings.audioEnabled);
    }

    /** A native addition: the beta's player had linear interpolation only. */
    private void audioQualityPage() {
        description("Rééchantillonnage",
                "Le son du jeu est converti à la fréquence du téléphone. Le filtre sinc garde les notes "
                        + "aiguës nettes, sans le sifflement métallique de l'interpolation linéaire, pour moins "
                        + "de 1 % d'un cœur du processeur ; l'interpolation linéaire reste au choix pour comparer.");
        choices(new String[]{"Sinc (défaut)", "Linéaire"},
                new String[]{PlaybackSettings.RESAMPLER_SINC, PlaybackSettings.RESAMPLER_LINEAR},
                settings.resampler, value -> update(settings.withResampler(value)));
    }

    /** The beta's Vidéo groups, by theme. Its reset brings every one back. */
    private void videoPage() {
        category("Couleurs", "Profil Game Boy et correction LCD", VIDEO_COLORS, null);
        category("Rendu", "Mélange d'images et effet d'écran", VIDEO_RENDERING, null);
        category("Taille et réglages", "Pixels entiers, luminosité et contraste", VIDEO_PICTURE, null);
        Button reset = button("Rétablir l'affichage d'origine");
        reset.setTextColor(HomeActivity.ACCENT);
        reset.setOnClickListener(view -> {
            update(settings.withPicture(false, 0, 100).withImage(PlaybackSettings.PALETTE_ORIGINAL,
                    false, false, PlaybackSettings.BLENDING_OFF).withEffect(PlaybackSettings.EFFECT_NONE));
            showPage(VIDEO);
        });
        add(reset, 14);
    }

    /** The beta's wording. The colors, the LCD correction and the frame
     * blending are RavenCore's, set through its Libretro options. */
    private void videoColorsPage() {
        group("Écran");
        description("Profil de couleurs",
                "Original garde le vert de la Game Boy ; Pocket gris imite la Game Boy Pocket ; "
                        + "Gris neutre passe en niveaux de gris purs. Ces profils ne concernent que les jeux "
                        + "Game Boy : les jeux Game Boy Color et Game Boy Advance gardent toujours leurs couleurs.");
        choices(new String[]{"Original", "Pocket gris", "Gris neutre"},
                new String[]{PlaybackSettings.PALETTE_ORIGINAL, PlaybackSettings.PALETTE_POCKET,
                        PlaybackSettings.PALETTE_GRAY},
                settings.gbPalette, palette -> update(settings.withImage(palette,
                        settings.lcdCorrectionGbc, settings.lcdCorrectionGba, settings.frameBlending)));
        group("Correction des couleurs LCD");
        settingSwitch("Game Boy Color",
                "Atténue les couleurs très vives pour retrouver l'aspect plus mat de l'écran d'origine.",
                settings.lcdCorrectionGbc, enabled -> update(settings.withImage(settings.gbPalette,
                        enabled, settings.lcdCorrectionGba, settings.frameBlending)));
        settingSwitch("Game Boy Advance",
                "Même correction, réglée à part pour la Game Boy Advance.",
                settings.lcdCorrectionGba, enabled -> update(settings.withImage(settings.gbPalette,
                        settings.lcdCorrectionGbc, enabled, settings.frameBlending)));
    }

    private void videoRenderingPage() {
        group("Mélange d'images");
        description("Rémanence de l'écran",
                "Certains jeux font clignoter un objet d'une image à l'autre pour le rendre transparent, "
                        + "en comptant sur la lenteur de l'écran d'origine (F-Zero, Castlevania). Le mélange le "
                        + "montre transparent ; la rémanence laisse une traîne.");
        choices(new String[]{"Désactivé (défaut)", "Mélange de deux images", "Rémanence"},
                new String[]{PlaybackSettings.BLENDING_OFF, PlaybackSettings.BLENDING_MIX,
                        PlaybackSettings.BLENDING_GHOSTING},
                settings.frameBlending, blending -> update(settings.withImage(settings.gbPalette,
                        settings.lcdCorrectionGbc, settings.lcdCorrectionGba, blending)));
        // Native additions after two public-domain RetroArch shaders
        // (sharp-bilinear, lcd3x), drawn by the GLES2 driver.
        group("Effet d'écran");
        description("Rendu des pixels",
                "Le lissage net garde des pixels nets et réguliers quelle que soit la taille de l'image ; "
                        + "la grille LCD imite la trame de l'écran d'une console portable.");
        choices(new String[]{"Aucun (défaut)", "Lissage net", "Grille LCD"},
                new String[]{PlaybackSettings.EFFECT_NONE, PlaybackSettings.EFFECT_SHARP, PlaybackSettings.EFFECT_LCD},
                settings.screenEffect, effect -> update(settings.withEffect(effect)));
    }

    private void videoPicturePage() {
        group("Taille de l'image");
        settingSwitch("Pixels entiers",
                "L'image prend un multiple entier de sa taille : tous les pixels sont identiques, avec des bords noirs autour.",
                settings.integerScale, enabled -> update(settings.withPicture(
                        enabled, settings.brightness, settings.contrast)));
        group("Ajustements");
        slider("Luminosité", settings.brightness, -50, 50, false, value ->
                update(settings.withPicture(settings.integerScale, value, settings.contrast)));
        slider("Contraste", settings.contrast, 50, 150, true, value ->
                update(settings.withPicture(settings.integerScale, settings.brightness, value)));
    }

    /** The beta's Console page: the console that runs the games. Taken at the
     * game's launch, except the Game Boy colors, which change at once. */
    private void consolePage() {
        category("Game Boy et GBC", "Jeux Game Boy en couleurs et GBC d'une GBA", CONSOLE_GB, null);
        category("Game Boy Advance", "BIOS et son fichier", CONSOLE_GBA, null);
    }

    private void consoleGameBoyPage() {
        LinearLayout[] colors = new LinearLayout[1];
        settingSwitch("Jeux Game Boy en couleurs",
                "Les jeux Game Boy tournent sur une Game Boy Color, qui leur donne des couleurs : celles que sa "
                        + "boot ROM choisit pour chaque jeu (Pokémon Rouge en rouge, Bleu en bleu…), ou celles "
                        + "choisies ci-dessous. Un état pris dans l'autre mode ne se recharge pas. Pris en compte "
                        + "au lancement du jeu.",
                console.gbInColor, enabled -> {
                    updateConsole(console.withGbInColor(enabled));
                    setChoicesEnabled(colors[0], enabled);
                });
        description("Couleurs des jeux Game Boy",
                "Les combinaisons sont celles que l'on tenait pendant le logo de la console. Changées aussitôt, "
                        + "même en jeu.");
        String[] indices = new String[ConsoleSettings.GB_COLORS.length];
        for (int i = 0; i < indices.length; i++) indices[i] = String.valueOf(i);
        colors[0] = choices(ConsoleSettings.GB_COLORS, indices, String.valueOf(console.gbColors),
                value -> updateConsole(console.withGbColors(Integer.parseInt(value))));
        setChoicesEnabled(colors[0], console.gbInColor);
        settingSwitch("Game Boy Color d'une Game Boy Advance",
                "La GBC intégrée à une Game Boy Advance : des jeux comme Zelda Oracle ou Shantae débloquent alors "
                        + "leurs bonus. Un état pris avec l'autre console ne se recharge pas. Pris en compte au "
                        + "lancement du jeu.",
                console.gbcOnGba, enabled -> updateConsole(console.withGbcOnGba(enabled)));
    }

    private void consoleAdvancePage() {
        description("BIOS",
                "RavenCore répond lui-même aux appels du BIOS. Le vrai BIOS, celui de votre console, joue son "
                        + "animation de démarrage (sauf si vous la sautez) : choisissez son fichier (16 Kio), aucun "
                        + "BIOS n'est fourni. Un état ne se recharge qu'avec le BIOS qui l'a créé. Pris en compte au "
                        + "lancement du jeu.");
        choices(new String[]{"BIOS de RavenCore (défaut)", "Vrai BIOS", "Vrai BIOS, sans animation"},
                new String[]{String.valueOf(ConsoleSettings.BIOS_RAVENCORE), String.valueOf(ConsoleSettings.BIOS_REAL),
                        String.valueOf(ConsoleSettings.BIOS_REAL_SKIP_INTRO)},
                String.valueOf(console.gbaBios), value -> updateConsole(console.withGbaBios(Integer.parseInt(value))));
        TextView file = text(biosInstalled ? "Fichier du BIOS : installé" : "Fichier du BIOS : absent", 13,
                biosInstalled ? HomeActivity.TEXT : HomeActivity.MUTED);
        add(file, 10);
        Button choose = textButton(biosInstalled ? "Remplacer le fichier du BIOS" : "Choisir le fichier du BIOS",
                HomeActivity.ACCENT);
        choose.setOnClickListener(view -> pickBios());
        if (biosInstalled) {
            Button remove = textButton("Retirer le fichier du BIOS", HomeActivity.MUTED);
            remove.setOnClickListener(view -> io.execute(() -> {
                GbaBiosRepository.remove(getFilesDir());
                biosChanged(null);
            }));
        }
    }

    /** The beta's Interface page: the touch controls, where the buttons were
     * moved, and the cartridge hardware the phone stands in for. Its Delta skin
     * section waits for that native feature; rewind is left out at the
     * owner's request. */
    private void interfacePage() {
        category("Commandes tactiles", "Retour haptique, taille, espacement et style", INTERFACE_CONTROLS, null);
        category("Cartouche", "Vibration et capteurs du téléphone", INTERFACE_CARTRIDGE, null);
    }

    private void interfaceControlsPage() {
        settingSwitch("Retour haptique", "Petite vibration à la pression des boutons.", controls.haptics,
                enabled -> updateControls(controls.withHaptics(enabled)));
        slider("Taille des commandes", controls.scale, ControlSettings.SCALE_MIN, ControlSettings.SCALE_MAX,
                " %", value -> updateControls(controls.withScale(value)));
        slider("Espacement vertical", controls.gap, ControlSettings.GAP_MIN, ControlSettings.GAP_MAX,
                " dp", value -> updateControls(controls.withGap(value)));
        group("Style des boutons");
        choices(new String[]{"Classique (défaut)", "Contraste élevé"},
                new String[]{ControlSettings.STYLE_CLASSIC, ControlSettings.STYLE_CONTRAST}, controls.style,
                style -> updateControls(controls.withStyle(style)));
        add(text("Pour déplacer les boutons, ouvrez le menu du jeu puis « Déplacer les boutons ». Le "
                + "portrait et le paysage gardent chacun leur disposition.", 12, HomeActivity.MUTED), 8);
        Button reset = textButton("Réinitialiser les commandes", HomeActivity.ACCENT);
        reset.setOnClickListener(view -> {
            updateControls(ControlSettings.DEFAULTS);
            ControlLayout.reset(this);
            showPage(INTERFACE_CONTROLS);
        });
    }

    /** The cartridge hardware the phone stands in for: its motor, and
     * RavenCore's Libretro sensors fed by the native sensor driver. */
    private void interfaceCartridgePage() {
        group("Vibration");
        settingSwitch("Vibration de la cartouche",
                "Fait vibrer le téléphone avec les jeux équipés d'un moteur de vibration (Drill Dozer, "
                        + "WarioWare: Twisted!, cartouches Game Boy Rumble).",
                cartridge.rumble, enabled -> updateCartridge(cartridge.withRumble(enabled)));
        group("Capteurs de la cartouche");
        Switch[] solarAuto = new Switch[1];
        SeekBar[] solarLevel = new SeekBar[1];
        settingSwitch("Capteurs du téléphone",
                "Le gyroscope, l'inclinaison et la lumière du téléphone remplacent ceux de la cartouche : "
                        + "WarioWare: Twisted!, Yoshi Topsy-Turvy, Kirby Tilt 'n' Tumble, Boktai.",
                cartridge.sensors, enabled -> {
                    updateCartridge(cartridge.withSensors(enabled));
                    solarAuto[0].setEnabled(enabled);
                    solarLevel[0].setEnabled(!cartridge.solarFromSensor());
                });
        solarAuto[0] = settingSwitch("Soleil automatique (Boktai)",
                "Le capteur de lumière du téléphone règle le soleil : il faut la lumière du jour, celle d'une "
                        + "pièce ne suffit pas. Sans ce capteur, le niveau ci-dessous s'applique.",
                cartridge.solarAuto, enabled -> {
                    updateCartridge(cartridge.withSolarAuto(enabled));
                    solarLevel[0].setEnabled(!cartridge.solarFromSensor());
                });
        solarAuto[0].setEnabled(cartridge.sensors);
        solarLevel[0] = slider("Soleil (Boktai)", cartridge.solarLevel, CartridgeSettings.SOLAR_MIN,
                CartridgeSettings.SOLAR_MAX, CartridgeSettings.SOLAR_STEP, " %",
                value -> updateCartridge(cartridge.withSolarLevel(value)));
        solarLevel[0].setEnabled(!cartridge.solarFromSensor());
    }

    private void updateCartridge(CartridgeSettings updated) {
        cartridge = updated;
        cartridge.save(this);
    }

    private void updateControls(ControlSettings updated) {
        controls = updated;
        controls.save(this);
    }

    private void updateConsole(ConsoleSettings updated) {
        console = updated;
        console.save(this);
    }

    private void pickBios() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        try {
            startActivityForResult(pick, PICK_BIOS);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, "Aucune application pour choisir un fichier", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_BIOS || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        io.execute(() -> {
            String message = "BIOS GBA installé";
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                GbaBiosRepository.install(getFilesDir(), input);
            } catch (IOException | RuntimeException error) {
                message = error.getMessage() != null ? error.getMessage() : "BIOS GBA non installé";
            }
            biosChanged(message);
        });
    }

    /** IO thread: shows the file's new state, and the message if any. */
    private void biosChanged(String message) {
        boolean installed = GbaBiosRepository.installed(getFilesDir());
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            biosInstalled = installed;
            if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (CONSOLE_GBA.equals(page)) showPage(CONSOLE_GBA);
        });
    }

    private void description(String label, String summary) {
        LinearLayout block = column();
        block.addView(text(label, 15, HomeActivity.TEXT));
        add(block, text(summary, 13, HomeActivity.MUTED), 4);
        add(block, 14);
    }

    /** The beta's ChoiceRow: ● or ○ then the label, the chosen row on the surface color. */
    private LinearLayout choices(String[] labels, String[] values, String selected, StringChange change) {
        LinearLayout list = column();
        for (int i = 0; i < labels.length; i++) {
            final String value = values[i];
            final boolean chosen = value.equals(selected);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(48));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(chosen ? HomeActivity.SURFACE : HomeActivity.BACKGROUND);
            TextView mark = text(chosen ? "●" : "○", 17, HomeActivity.ACCENT);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(mark);
            TextView name = text(labels[i], 14, HomeActivity.TEXT);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, -2, 1);
            gap.setMarginStart(dp(12));
            row.addView(name, gap);
            row.setSelected(chosen);
            row.setContentDescription(labels[i] + (chosen ? ", choisi" : ""));
            row.setOnClickListener(view -> {
                if (chosen || !view.isEnabled()) return;
                change.accept(value);
                showPage(page);
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        add(list, 6);
        return list;
    }

    /** Like the beta's ChoiceRow, a disabled row looks the same and ignores taps. */
    private static void setChoicesEnabled(LinearLayout list, boolean enabled) {
        for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setEnabled(enabled);
    }

    private void update(PlaybackSettings updated) {
        settings = updated;
        settings.save(this);
    }

    private Switch settingSwitch(String label, String summary, boolean checked, BooleanChange change) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        LinearLayout description = column();
        TextView name = text(label, 15, HomeActivity.TEXT);
        description.addView(name);
        TextView detail = text(summary, 13, HomeActivity.MUTED);
        add(description, detail, 4);
        row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(this);
        control.setSaveEnabled(false);
        control.setId(View.generateViewId());
        control.setContentDescription(label + ". " + summary);
        control.setMinimumHeight(dp(48));
        control.setMinWidth(dp(48));
        int[][] states = {{android.R.attr.state_checked}, {}};
        control.setThumbTintList(new ColorStateList(states,
                new int[]{HomeActivity.ACCENT, HomeActivity.SECONDARY}));
        control.setTrackTintList(new ColorStateList(states,
                new int[]{0x66a78bfa, HomeActivity.OUTLINE}));
        control.setChecked(checked);
        control.setOnCheckedChangeListener((button, value) -> change.accept(value));
        name.setLabelFor(control.getId());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.leftMargin = dp(12);
        row.addView(control, size);
        // A switch greyed out by another setting stays as it is.
        row.setOnClickListener(view -> { if (control.isEnabled()) control.performClick(); });
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        add(row, 14);
        return control;
    }

    private SeekBar slider(String label, int value, int minimum, int maximum,
                           boolean percent, IntChange change) {
        return slider(label, value, minimum, maximum, percent ? " %" : "", change);
    }

    private SeekBar slider(String label, int value, int minimum, int maximum,
                           String unit, IntChange change) {
        return slider(label, value, minimum, maximum, 1, unit, change);
    }

    /** Steps of `step` from minimum: Boktai's sun moves by tens. */
    private SeekBar slider(String label, int value, int minimum, int maximum, int step,
                           String unit, IntChange change) {
        LinearLayout block = column();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(label, 15, HomeActivity.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        TextView valueText = text(value + unit, 14, HomeActivity.ACCENT);
        heading.addView(valueText);
        block.addView(heading);
        SeekBar control = new SeekBar(this);
        control.setSaveEnabled(false);
        control.setMax((maximum - minimum) / step);
        control.setProgress((value - minimum) / step);
        control.setContentDescription(label + ", " + value + unit);
        control.setProgressTintList(ColorStateList.valueOf(HomeActivity.ACCENT));
        control.setThumbTintList(ColorStateList.valueOf(HomeActivity.ACCENT));
        control.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Android view-state restoration must never save a stale value.
                if (!fromUser) return;
                int current = minimum + progress * step;
                valueText.setText(current + unit);
                control.setContentDescription(label + ", " + current + unit);
                change.accept(current);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        block.addView(control, new LinearLayout.LayoutParams(-1, dp(48)));
        add(block, 14);
        return control;
    }

    private void group(String label) {
        TextView heading = text(label, 14, HomeActivity.ACCENT);
        heading.setTypeface(null, Typeface.BOLD);
        add(heading, content.getChildCount() == 0 ? 0 : 14);
        View divider = new View(this);
        divider.setBackgroundColor(HomeActivity.OUTLINE);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
        line.topMargin = dp(6);
        content.addView(divider, line);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setTextColor(HomeActivity.TEXT);
        button.setBackground(shape(HomeActivity.SURFACE));
        return button;
    }

    /** The beta's TextButton: no background, the label in its color. */
    private Button textButton(String label, int color) {
        Button button = button(label);
        button.setTextColor(color);
        button.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.topMargin = dp(4);
        content.addView(button, size);
        return button;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    /** Nothing above a page's first block. */
    private void add(View view, int top) { add(content, view, content.getChildCount() == 0 ? 0 : top); }

    private void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(top);
        parent.addView(view, size);
    }

    /** The page's place in PAGES; an unknown page is the categories. */
    private static int index(String page) {
        for (int i = 0; i < PAGES.length; i++) if (PAGES[i].equals(page)) return i;
        return 0;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface BooleanChange { void accept(boolean value); }
    private interface IntChange { void accept(int value); }
    private interface StringChange { void accept(String value); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        scrollPositions[index(page)] = scroll.getScrollY();
        state.putString("settings.page", page);
        for (int i = 0; i < PAGES.length; i++) state.putInt("settings.scroll." + PAGES[i], scrollPositions[i]);
    }

    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    /** Exact 24x24 paths from the beta's theme/RavenIcons.kt. */
    private static final class SettingsIcon extends View {
        private final Path path;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SettingsIcon(Context context, String pathData) {
            super(context);
            path = PathParser.createPathFromPathData(pathData);
            paint.setColor(HomeActivity.ACCENT);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 24f, getHeight() / 24f);
            canvas.drawPath(path, paint);
            canvas.restore();
        }
    }
}
