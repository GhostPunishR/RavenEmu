package com.ravenemu.app.ui.settings;
import com.ravenemu.app.platform.SystemBarInsets;
import com.ravenemu.app.settings.PlaybackSettings;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.ui.common.RavenTheme;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Java Views counterpart of the beta's settings: RetroAchievements, Général,
 * Audio, Vidéo, Console, Interface, Sauvegardes cloud and À propos. The
 * library's ⚙ opens it on its categories. Only settings connected to the
 * native drivers are presented. Each page group has its own file
 * (AudioSettingsPages, SkinSettingsPage…), its rows are SettingsWidgets';
 * this activity holds the page shown and its navigation. */
public final class PlaybackSettingsActivity extends Activity implements RavenTheme.Host {
    public static final String EXTRA_PAGE = "playback.settings.page";
    public static final String HOME = "home";
    public static final String AUDIO = "audio";
    public static final String VIDEO = "video";
    public static final String CONSOLE = "console";
    public static final String INTERFACE = "interface";
    public static final String GENERAL = "general";
    public static final String ABOUT = "about";
    public static final String RETRO = "retroachievements";
    public static final String CLOUD = "cloud";
    /** Each category lists its sub-pages; "category/sub-page" goes back to its category. */
    public static final String GENERAL_EMULATION = "general/emulation";
    public static final String GENERAL_LATENCY = "general/latency";
    public static final String AUDIO_SOUND = "audio/sound";
    public static final String AUDIO_QUALITY = "audio/quality";
    public static final String VIDEO_COLORS = "video/colors";
    public static final String VIDEO_RENDERING = "video/rendering";
    public static final String VIDEO_PICTURE = "video/picture";
    public static final String VIDEO_SHADERS = "video/shaders";
    public static final String VIDEO_SHADER_PARAMETERS = "video/shaders/parameters";
    public static final String CONSOLE_GB = "console/gb";
    public static final String CONSOLE_GBA = "console/gba";
    public static final String INTERFACE_CONTROLS = "interface/controls";
    public static final String INTERFACE_CARTRIDGE = "interface/cartridge";
    public static final String INTERFACE_SKINS = "interface/skins";
    public static final String INTERFACE_THEME = "interface/theme";
    private static final String[] PAGES = {HOME, AUDIO, VIDEO, CONSOLE, INTERFACE, GENERAL, ABOUT,
            GENERAL_EMULATION, GENERAL_LATENCY, AUDIO_SOUND, AUDIO_QUALITY, VIDEO_COLORS, VIDEO_RENDERING,
            VIDEO_PICTURE, CONSOLE_GB, CONSOLE_GBA, INTERFACE_CONTROLS, INTERFACE_CARTRIDGE, INTERFACE_SKINS, RETRO,
            INTERFACE_THEME, CLOUD, VIDEO_SHADERS, VIDEO_SHADER_PARAMETERS};
    private static final String[] TITLES = {"Paramètres", "Audio", "Vidéo", "Console", "Interface",
            "Général", "À propos", "Émulation", "Latence", "Son du jeu", "Qualité du son", "Couleurs",
            "Rendu", "Taille et réglages", "Game Boy et GBC", "Game Boy Advance", "Commandes tactiles",
            "Cartouche", "Skin Delta", "RetroAchievements", "Thème", "Sauvegardes cloud", "Shaders",
            "Paramètres du shader"};
    static final int PICK_BIOS = 1;
    static final int PICK_SKIN = 2;
    static final int PICK_SHADER_ZIP = 3;
    static final int PICK_SHADER_FOLDER = 4;

    /** Shared by the Audio and Vidéo pages. */
    PlaybackSettings settings;
    /** The BIOS copy and removal, and the account file, off the UI thread, one at a time. */
    final ExecutorService io = Executors.newSingleThreadExecutor();
    /** RetroAchievements' sign-in and the avatar: they wait for the network. */
    final ExecutorService network = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private ScrollView scroll;
    private TextView title;
    private View back;
    private String page;
    private final int[] scrollPositions = new int[PAGES.length];
    private int pageSerial;
    private boolean rendered;
    /** Settings → Interface → Thème, as it was when the page opened; choosing
     * another one creates the settings again in its colours. */
    private RavenTheme theme;
    private SettingsWidgets widgets;
    private AchievementsSettingsPage achievements;
    private GeneralSettingsPages general;
    private AudioSettingsPages audio;
    private VideoSettingsPages video;
    private ConsoleSettingsPages consoles;
    private InterfaceSettingsPages interfaces;
    private SkinSettingsPage skinPage;
    private AboutSettingsPage about;
    private CloudSettingsPage cloud;
    private ShaderSettingsPage shaders;

    @Override public RavenTheme theme() { return theme; }

    @Override public void onCreate(Bundle state) {
        theme = RavenTheme.apply(this);
        super.onCreate(state);
        settings = PlaybackSettings.load(this);
        widgets = new SettingsWidgets(this, theme, new SettingsWidgets.Navigator() {
            @Override public String page() { return page; }
            @Override public void showPage(String requested) { PlaybackSettingsActivity.this.showPage(requested); }
        });
        achievements = new AchievementsSettingsPage(this, widgets);
        general = new GeneralSettingsPages(this, widgets);
        audio = new AudioSettingsPages(this, widgets);
        video = new VideoSettingsPages(this, widgets);
        consoles = new ConsoleSettingsPages(this, widgets);
        interfaces = new InterfaceSettingsPages(this, widgets);
        skinPage = new SkinSettingsPage(this, widgets);
        about = new AboutSettingsPage(this, widgets);
        cloud = new CloudSettingsPage(this, widgets);
        shaders = new ShaderSettingsPage(this, widgets);
        achievements.start();
        cloud.start();
        String initialPage = getIntent().getStringExtra(EXTRA_PAGE);
        if (state != null) {
            initialPage = state.getString("settings.page", initialPage);
            for (int i = 0; i < PAGES.length; i++)
                scrollPositions[i] = Math.max(0, state.getInt("settings.scroll." + PAGES[i]));
        }

        LinearLayout root = widgets.column();
        root.setTag("playback.settings.root");
        root.setBackgroundColor(theme.background);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(theme.surface);
        back = RavenIcon.button(this, RavenIcon.Glyph.BACK, theme.accent, 24, "Retour aux paramètres");
        back.setOnClickListener(view -> navigateBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(widgets.dp(48), -1));
        title = widgets.text("", 20, theme.text);
        title.setTypeface(null, Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, widgets.dp(54)));

        scroll = new ScrollView(this);
        scroll.setTag("playback.settings.scroll");
        scroll.setSaveEnabled(false);
        scroll.setFillViewport(true);
        content = widgets.column();
        widgets.content = content;
        content.setPadding(widgets.dp(18), widgets.dp(12), widgets.dp(18), widgets.dp(24));
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

    void showPage(String requestedPage) {
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
            case AUDIO: audio.audioPage(); break;
            case VIDEO: video.videoPage(); break;
            case CONSOLE: consoles.consolePage(); break;
            case INTERFACE: interfaces.interfacePage(); break;
            case GENERAL: general.generalPage(); break;
            case ABOUT: about.aboutPage(); break;
            case GENERAL_EMULATION: general.generalEmulationPage(); break;
            case GENERAL_LATENCY: general.generalLatencyPage(); break;
            case AUDIO_SOUND: audio.audioSoundPage(); break;
            case AUDIO_QUALITY: audio.audioQualityPage(); break;
            case VIDEO_COLORS: video.videoColorsPage(); break;
            case VIDEO_RENDERING: video.videoRenderingPage(); break;
            case VIDEO_PICTURE: video.videoPicturePage(); break;
            case CONSOLE_GB: consoles.consoleGameBoyPage(); break;
            case CONSOLE_GBA: consoles.consoleAdvancePage(); break;
            case INTERFACE_CONTROLS: interfaces.interfaceControlsPage(); break;
            case INTERFACE_CARTRIDGE: interfaces.interfaceCartridgePage(); break;
            case INTERFACE_SKINS: skinPage.interfaceSkinsPage(); break;
            case INTERFACE_THEME: interfaces.interfaceThemePage(); break;
            case RETRO: achievements.retroPage(); break;
            case CLOUD: cloud.cloudPage(); break;
            case VIDEO_SHADERS: shaders.shadersPage(); break;
            case VIDEO_SHADER_PARAMETERS: shaders.parametersPage(); break;
            default: homePage();
        }
        final int restoreScroll = scrollPositions[index(page)];
        scroll.post(() -> {
            if (!isFinishing() && !isDestroyed() && request == pageSerial) scroll.scrollTo(0, restoreScroll);
        });
    }

    /** The page shown. */
    String page() { return page; }

    /** The beta's categories, in its order. */
    private void homePage() {
        widgets.category("RetroAchievements", "Compte, succès et progression", RETRO, RavenIcon.Glyph.TROPHY);
        widgets.category("Général", "Pause automatique et latence", GENERAL, RavenIcon.Glyph.SLIDERS);
        widgets.category("Audio", "Son du jeu, volume et qualité", AUDIO, RavenIcon.Glyph.SPEAKER);
        widgets.category("Vidéo", "Couleurs, rendu, taille, luminosité et contraste", VIDEO, RavenIcon.Glyph.MONITOR);
        // The beta drew Console with About's icon; the owner wanted its own: a handheld.
        widgets.category("Console", "Jeux Game Boy en couleurs, GBC d'une GBA, BIOS GBA", CONSOLE, RavenIcon.Glyph.GB);
        // The beta says "Vibrations, capteurs, commandes, retour arrière, style et
        // Skin Delta": the owner left rewind out of the native player, and the
        // Delta skins wait for their port, so neither is announced.
        widgets.category("Interface", "Thème, vibrations, capteurs, commandes, style et skin Delta", INTERFACE,
                RavenIcon.Glyph.GAMEPAD);
        // The owner's addition: the beta had no cloud.
        widgets.category("Sauvegardes cloud", "WebDAV : Nextcloud, kDrive, NAS…", CLOUD, RavenIcon.Glyph.CLOUD);
        widgets.category("À propos", "Version, auteur, confidentialité et licences", ABOUT, RavenIcon.Glyph.INFO);
    }

    /** A sub-page goes back to its category, a category to the categories. */
    private void navigateBack() {
        if (HOME.equals(page)) finish(); else showPage(parent(page));
    }

    private static String parent(String page) {
        // "video/shaders/parameters" goes back to "video/shaders".
        final int slash = page.lastIndexOf('/');
        return slash < 0 ? HOME : page.substring(0, slash);
    }

    @Override public void onBackPressed() { navigateBack(); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_SKIN) {
            skinPage.skinPicked(uri);
            return;
        }
        if (request == PICK_SHADER_ZIP || request == PICK_SHADER_FOLDER) {
            shaders.picked(request, uri);
            return;
        }
        if (request != PICK_BIOS) return;
        consoles.biosPicked(uri);
    }

    void update(PlaybackSettings updated) {
        settings = updated;
        settings.save(this);
    }

    /** The page's place in PAGES; an unknown page is the categories. */
    private static int index(String page) {
        for (int i = 0; i < PAGES.length; i++) if (PAGES[i].equals(page)) return i;
        return 0;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        scrollPositions[index(page)] = scroll.getScrollY();
        state.putString("settings.page", page);
        for (int i = 0; i < PAGES.length; i++) state.putInt("settings.scroll." + PAGES[i], scrollPositions[i]);
    }

    @Override protected void onDestroy() {
        skinPage.dismiss();
        achievements.dismiss();
        cloud.dismiss();
        shaders.dismiss();
        io.shutdown();
        network.shutdown();
        super.onDestroy();
    }
}
