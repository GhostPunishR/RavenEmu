package com.ravenemu.app.ui.settings;
import com.ravenemu.app.R;
import com.ravenemu.app.data.saves.GbaBiosRepository;
import com.ravenemu.app.platform.SystemBarInsets;
import com.ravenemu.app.settings.ConsoleSettings;
import com.ravenemu.app.settings.ControlSettings;
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

/** Java Views counterpart of the beta's Audio, Video, Console and Interface
 * settings pages. Only settings connected to the native drivers are presented here. */
public final class PlaybackSettingsActivity extends Activity {
    public static final String EXTRA_PAGE = "playback.settings.page";
    public static final String HOME = "home";
    public static final String AUDIO = "audio";
    public static final String VIDEO = "video";
    public static final String CONSOLE = "console";
    public static final String INTERFACE = "interface";
    private static final int PICK_BIOS = 1;

    private PlaybackSettings settings;
    private ConsoleSettings console;
    private ControlSettings controls;
    private boolean biosInstalled;
    /** The BIOS copy and removal, off the UI thread, one at a time. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private ScrollView scroll;
    private TextView title;
    private Button back;
    private SeekBar volume;
    private String page;
    private final int[] scrollPositions = new int[5];
    private int pageSerial;
    private boolean rendered;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = PlaybackSettings.load(this);
        console = ConsoleSettings.load(this);
        controls = ControlSettings.load(this);
        biosInstalled = GbaBiosRepository.installed(getFilesDir());
        String initialPage = getIntent().getStringExtra(EXTRA_PAGE);
        if (state != null) {
            initialPage = state.getString("settings.page", initialPage);
            scrollPositions[0] = Math.max(0, state.getInt("settings.scroll.home"));
            scrollPositions[1] = Math.max(0, state.getInt("settings.scroll.audio"));
            scrollPositions[2] = Math.max(0, state.getInt("settings.scroll.video"));
            scrollPositions[3] = Math.max(0, state.getInt("settings.scroll.console"));
            scrollPositions[4] = Math.max(0, state.getInt("settings.scroll.interface"));
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
        page = INTERFACE.equals(requestedPage) ? INTERFACE : CONSOLE.equals(requestedPage) ? CONSOLE
                : VIDEO.equals(requestedPage) ? VIDEO : AUDIO.equals(requestedPage) ? AUDIO : HOME;
        rendered = true;
        final int request = ++pageSerial;
        title.setText(INTERFACE.equals(page) ? "Interface" : CONSOLE.equals(page) ? "Console"
                : VIDEO.equals(page) ? "Vidéo" : AUDIO.equals(page) ? "Audio" : "Paramètres");
        back.setContentDescription(HOME.equals(page) ? "Fermer les paramètres" : "Retour aux paramètres");
        content.removeAllViews();
        if (AUDIO.equals(page)) audioPage(); else if (VIDEO.equals(page)) videoPage();
        else if (CONSOLE.equals(page)) consolePage(); else if (INTERFACE.equals(page)) interfacePage();
        else homePage();
        final int restoreScroll = scrollPositions[index(page)];
        scroll.post(() -> {
            if (!isFinishing() && !isDestroyed() && request == pageSerial) scroll.scrollTo(0, restoreScroll);
        });
    }

    private void homePage() {
        category("Audio", "Son du jeu et volume", AUDIO,
                "M3,9H7L12,4V20L7,15H3ZM15,8V16C17,15 17,9 15,8ZM15,3V5C21,7 21,17 15,19V21C24,18 24,6 15,3Z");
        category("Vidéo", "Couleurs, correction LCD, mélange d'images, taille, luminosité et contraste", VIDEO,
                "M3,3H21V17H13V19H17V21H7V19H11V17H3ZM5,5V15H19V5Z");
        // The beta draws Console with its default (About) icon.
        category("Console", "Jeux Game Boy en couleurs, GBC d'une GBA, BIOS GBA", CONSOLE,
                "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z"
                        + "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z");
        // The beta says "Commandes tactiles, retour arrière et style": the
        // native player has no rewind yet, so it is not announced.
        category("Interface", "Commandes tactiles et style", INTERFACE,
                "M3,3H11V11H3ZM13,3H21V11H13ZM3,13H11V21H3ZM13,13H21V21H13Z");
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

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(shape(0x21a78bfa));
        iconBox.addView(new SettingsIcon(this, iconPath),
                new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout description = column();
        TextView name = text(label, 16, HomeActivity.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        description.addView(name);
        add(description, text(summary, 12, HomeActivity.MUTED), 4);
        LinearLayout.LayoutParams details = new LinearLayout.LayoutParams(0, -2, 1);
        details.setMarginStart(dp(13));
        details.setMarginEnd(dp(13));
        row.addView(description, details);
        row.addView(text("›", 23, HomeActivity.ACCENT));
        add(row, content.getChildCount() == 0 ? 0 : 14);
    }

    private void navigateBack() {
        if (HOME.equals(page)) finish(); else showPage(HOME);
    }

    @Override public void onBackPressed() { navigateBack(); }

    private void audioPage() {
        group("Son du jeu");
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

    private void videoPage() {
        // The beta's groups and wording. The colors, the LCD correction and the
        // frame blending are RavenCore's, set through its Libretro options.
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
        Button reset = button("Rétablir l'affichage d'origine");
        reset.setTextColor(HomeActivity.ACCENT);
        reset.setOnClickListener(view -> {
            update(settings.withPicture(false, 0, 100).withImage(PlaybackSettings.PALETTE_ORIGINAL,
                    false, false, PlaybackSettings.BLENDING_OFF));
            showPage(VIDEO);
        });
        add(reset, 14);
    }

    /** The beta's Console page: the console that runs the games. Taken at the
     * game's launch, except the Game Boy colors, which change at once. */
    private void consolePage() {
        group("Game Boy et Game Boy Color");
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
        group("Game Boy Advance");
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

    /** The beta's Interface page, touch controls part. Its rewind, cartridge
     * and Delta skin sections wait for those native features. */
    private void interfacePage() {
        group("Commandes tactiles");
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
        add(text("Le déplacement libre des boutons sera ajouté plus tard.", 12, HomeActivity.MUTED), 8);
        Button reset = textButton("Réinitialiser les commandes", HomeActivity.ACCENT);
        reset.setOnClickListener(view -> {
            updateControls(ControlSettings.DEFAULTS);
            showPage(INTERFACE);
        });
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
            if (CONSOLE.equals(page)) showPage(CONSOLE);
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

    private void settingSwitch(String label, String summary, boolean checked, BooleanChange change) {
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
        row.setOnClickListener(view -> control.performClick());
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        add(row, 14);
    }

    private SeekBar slider(String label, int value, int minimum, int maximum,
                           boolean percent, IntChange change) {
        return slider(label, value, minimum, maximum, percent ? " %" : "", change);
    }

    private SeekBar slider(String label, int value, int minimum, int maximum,
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
        control.setMax(maximum - minimum);
        control.setProgress(value - minimum);
        control.setContentDescription(label + ", " + value + unit);
        control.setProgressTintList(ColorStateList.valueOf(HomeActivity.ACCENT));
        control.setThumbTintList(ColorStateList.valueOf(HomeActivity.ACCENT));
        control.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Android view-state restoration must never save a stale value.
                if (!fromUser) return;
                int current = progress + minimum;
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

    private void add(View view, int top) { add(content, view, top); }

    private void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(top);
        parent.addView(view, size);
    }

    private static int index(String page) {
        return INTERFACE.equals(page) ? 4 : CONSOLE.equals(page) ? 3 : VIDEO.equals(page) ? 2
                : AUDIO.equals(page) ? 1 : 0;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface BooleanChange { void accept(boolean value); }
    private interface IntChange { void accept(int value); }
    private interface StringChange { void accept(String value); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        scrollPositions[index(page)] = scroll.getScrollY();
        state.putString("settings.page", page);
        state.putInt("settings.scroll.home", scrollPositions[0]);
        state.putInt("settings.scroll.audio", scrollPositions[1]);
        state.putInt("settings.scroll.video", scrollPositions[2]);
        state.putInt("settings.scroll.console", scrollPositions[3]);
        state.putInt("settings.scroll.interface", scrollPositions[4]);
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
