package com.ravenemu.app.ui.settings;

import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import com.ravenemu.app.settings.CartridgeSettings;
import com.ravenemu.app.settings.ControlLayout;
import com.ravenemu.app.settings.ControlSettings;
import com.ravenemu.app.ui.common.RavenTheme;

import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.INTERFACE_CARTRIDGE;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.INTERFACE_CONTROLS;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.INTERFACE_SKINS;
import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.INTERFACE_THEME;

/** Settings → Interface: the theme, the touch controls and the cartridge
 * hardware the phone stands in for; the Delta skins have their own page. */
final class InterfaceSettingsPages {
    private final PlaybackSettingsActivity activity;
    private final SettingsWidgets widgets;
    private final RavenTheme theme;
    private ControlSettings controls;
    private CartridgeSettings cartridge;

    InterfaceSettingsPages(PlaybackSettingsActivity activity, SettingsWidgets widgets) {
        this.activity = activity;
        this.widgets = widgets;
        this.theme = activity.theme();
        controls = ControlSettings.load(activity);
        cartridge = CartridgeSettings.load(activity);
    }

    /** The beta's Interface page: the touch controls, where the buttons were
     * moved, the cartridge hardware the phone stands in for, and the Delta
     * skins; rewind is left out at the owner's request. The theme comes first:
     * the owner's addition, the beta had RavenEmu's colours only. */
    void interfacePage() {
        widgets.category("Thème", "RavenEmu ou NVIDIA : écrans, icônes et boutons", INTERFACE_THEME, null);
        widgets.category("Commandes tactiles", "Retour haptique, taille, espacement et style", INTERFACE_CONTROLS, null);
        widgets.category("Cartouche", "Vibration et capteurs du téléphone", INTERFACE_CARTRIDGE, null);
        widgets.category("Skin Delta", "Importer et choisir un skin .deltaskin", INTERFACE_SKINS, null);
    }

    /** RavenEmu's violet on its dark background, or the green on black of
     * RetroArch's "NVIDIA Shield" theme: every screen, icon, sheet and touch
     * control takes it at once; a game already open keeps its colours until it
     * is closed, so that it never restarts for a colour. */
    void interfaceThemePage() {
        RavenTheme[] themes = RavenTheme.all();
        String[] labels = new String[themes.length], values = new String[themes.length];
        for (int i = 0; i < themes.length; i++) {
            labels[i] = themes[i] == RavenTheme.RAVENEMU ? themes[i].name + " (défaut)" : themes[i].name;
            values[i] = themes[i].id;
        }
        widgets.choices(labels, values, theme.id, id -> changeTheme(RavenTheme.forId(id)));
        widgets.add(widgets.text("RavenEmu : violet sur fond sombre, les couleurs de la bêta. NVIDIA : vert sur noir, d'après "
                + "le thème « NVIDIA Shield » de RetroArch. Les écrans, les icônes, les menus et les boutons "
                + "tactiles prennent les couleurs du thème ; un jeu déjà ouvert garde les siennes jusqu'à sa "
                + "fermeture. Les skins Delta gardent leurs propres images.", 12, theme.muted), 8);
    }

    private void changeTheme(RavenTheme chosen) {
        if (chosen == theme) return;
        RavenTheme.store(activity, chosen);
        // Created again on the same page, in the new colours.
        activity.recreate();
    }

    void interfaceControlsPage() {
        widgets.settingSwitch("Retour haptique", "Petite vibration à la pression des boutons.", controls.haptics,
                enabled -> updateControls(controls.withHaptics(enabled)));
        widgets.slider("Taille des commandes", controls.scale, ControlSettings.SCALE_MIN, ControlSettings.SCALE_MAX,
                " %", value -> updateControls(controls.withScale(value)));
        widgets.slider("Espacement vertical", controls.gap, ControlSettings.GAP_MIN, ControlSettings.GAP_MAX,
                " dp", value -> updateControls(controls.withGap(value)));
        widgets.group("Style des boutons");
        widgets.choices(new String[]{"Classique (défaut)", "Contraste élevé"},
                new String[]{ControlSettings.STYLE_CLASSIC, ControlSettings.STYLE_CONTRAST}, controls.style,
                style -> updateControls(controls.withStyle(style)));
        widgets.add(widgets.text("Pour déplacer les boutons, ouvrez le menu du jeu puis « Déplacer les boutons ». Le "
                + "portrait et le paysage gardent chacun leur disposition.", 12, theme.muted), 8);
        Button reset = widgets.textButton("Réinitialiser les commandes", theme.accent);
        reset.setOnClickListener(view -> {
            updateControls(ControlSettings.DEFAULTS);
            ControlLayout.reset(activity);
            activity.showPage(INTERFACE_CONTROLS);
        });
    }

    /** The cartridge hardware the phone stands in for: its motor, and
     * RavenCore's Libretro sensors fed by the native sensor driver. */
    void interfaceCartridgePage() {
        widgets.group("Vibration");
        widgets.settingSwitch("Vibration de la cartouche",
                "Fait vibrer le téléphone avec les jeux équipés d'un moteur de vibration (Drill Dozer, "
                        + "WarioWare: Twisted!, cartouches Game Boy Rumble).",
                cartridge.rumble, enabled -> updateCartridge(cartridge.withRumble(enabled)));
        widgets.group("Capteurs de la cartouche");
        Switch[] solarAuto = new Switch[1];
        SeekBar[] solarLevel = new SeekBar[1];
        widgets.settingSwitch("Capteurs du téléphone",
                "Le gyroscope, l'inclinaison et la lumière du téléphone remplacent ceux de la cartouche : "
                        + "WarioWare: Twisted!, Yoshi Topsy-Turvy, Kirby Tilt 'n' Tumble, Boktai.",
                cartridge.sensors, enabled -> {
                    updateCartridge(cartridge.withSensors(enabled));
                    solarAuto[0].setEnabled(enabled);
                    solarLevel[0].setEnabled(!cartridge.solarFromSensor());
                });
        solarAuto[0] = widgets.settingSwitch("Soleil automatique (Boktai)",
                "Le capteur de lumière du téléphone règle le soleil : il faut la lumière du jour, celle d'une "
                        + "pièce ne suffit pas. Sans ce capteur, le niveau ci-dessous s'applique.",
                cartridge.solarAuto, enabled -> {
                    updateCartridge(cartridge.withSolarAuto(enabled));
                    solarLevel[0].setEnabled(!cartridge.solarFromSensor());
                });
        solarAuto[0].setEnabled(cartridge.sensors);
        solarLevel[0] = widgets.slider("Soleil (Boktai)", cartridge.solarLevel, CartridgeSettings.SOLAR_MIN,
                CartridgeSettings.SOLAR_MAX, CartridgeSettings.SOLAR_STEP, " %",
                value -> updateCartridge(cartridge.withSolarLevel(value)));
        solarLevel[0].setEnabled(!cartridge.solarFromSensor());
    }

    private void updateCartridge(CartridgeSettings updated) {
        cartridge = updated;
        cartridge.save(activity);
    }

    private void updateControls(ControlSettings updated) {
        controls = updated;
        controls.save(activity);
    }
}
