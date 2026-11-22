package com.ravenemu.app.ui.player;

import android.opengl.GLSurfaceView;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.data.library.LinkCable;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.data.saves.CheatList;
import com.ravenemu.app.data.saves.CheatRepository;
import com.ravenemu.app.data.saves.GbaBiosRepository;
import com.ravenemu.app.data.saves.SaveRepository;
import com.ravenemu.app.data.saves.SaveTransfer;
import com.ravenemu.app.platform.AchievementsHttp;
import com.ravenemu.app.platform.RetroUserAgent;
import com.ravenemu.app.settings.AchievementAccount;
import com.ravenemu.app.settings.AchievementSettings;
import com.ravenemu.app.settings.AwardJournal;
import com.ravenemu.app.settings.CartridgeSettings;
import com.ravenemu.app.settings.ConsoleSettings;
import com.ravenemu.app.settings.CoreOptions;
import com.ravenemu.app.settings.PlaybackSettings;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Opening the library's game, or two games joined by the link cable: the
 * files read on the saves' executor, the core opened on the GL thread. */
final class PlayerRomLoader {
    private final PlayerActivity activity;

    PlayerRomLoader(PlayerActivity activity) {
        this.activity = activity;
    }

    void loadFromLibrary(RomLibrary.Entry entry) {
        activity.submitIo(() -> {
            try {
                byte[] rom = RomLibrary.readRom(activity.getFilesDir(), entry);
                byte[] battery = SaveRepository.read(activity.getFilesDir(), entry.key, "srm");
                List<CheatList.Cheat> stored;
                try {
                    stored = CheatRepository.read(activity.getFilesDir(), entry.key);
                } catch (IOException | RuntimeException unreadable) {
                    stored = Collections.emptyList();
                    activity.postStatus("Codes de triche illisibles : " + unreadable.getMessage());
                }
                final List<CheatList.Cheat> storedCheats = stored;
                // Settings → Console picks the console the core emulates; it
                // and the image options apply as the game loads. The SRAM
                // stays the game's own.
                ConsoleSettings console = ConsoleSettings.load(activity);
                String core = console.coreFor(entry.system);
                String hardware = console.hardwareFor(entry.system);
                byte[] bios = null;
                if ("gba".equals(core) && console.gbaBios != ConsoleSettings.BIOS_RAVENCORE) {
                    try { bios = GbaBiosRepository.read(activity.getFilesDir()); }
                    catch (IOException unreadable) { bios = null; }
                }
                final boolean biosMissing = "gba".equals(core)
                        && console.gbaBios != ConsoleSettings.BIOS_RAVENCORE && bios == null;
                String[] coreOptions = CoreOptions.atLoad(core, hardware, PlaybackSettings.load(activity), console,
                        CartridgeSettings.load(activity), bios != null);
                String stateVariant = console.stateVariant(entry.system,
                        bios == null ? null : GbaBiosRepository.digest(bios));
                // Signed in from Settings → RetroAchievements: the game's
                // achievements follow it. Without an account, no network.
                final AchievementAccount account = AchievementAccount.load(activity);
                final AchievementsHttp http = account == null ? null : new AchievementsHttp(RetroUserAgent.of(activity));
                final AwardJournal journal = account == null ? null : AwardJournal.open(activity);
                final boolean hardcore = AchievementSettings.load(activity).hardcore;
                if (activity.disposed.get()) return;
                String nativeDir = activity.getApplicationInfo().nativeLibraryDir;
                String dataDir = activity.getFilesDir().getAbsolutePath();
                queueRomLoad(() -> {
                    if (activity.disposed.get()) return;
                    activity.undoState = null;
                    String failure = NativeBridge.openRom(nativeDir, dataDir,
                            core, rom, entry.title, coreOptions);
                    String notice = null;
                    if (failure == null && battery != null && !NativeBridge.restoreBattery(battery)) {
                        // A file of another size, from another emulator or an
                        // import: read as RavenCore reads one, its first bytes
                        // over the core's fresh SRAM (SaveTransfer.fit).
                        byte[] fitted = SaveTransfer.fit(battery, NativeBridge.saveBattery());
                        if (fitted == null) {
                            notice = "Ce jeu n'a pas de mémoire de sauvegarde : fichier conservé, non chargé";
                        } else if (fitted != battery && NativeBridge.restoreBattery(fitted)) {
                            notice = "Sauvegarde de " + battery.length + " octets lue sur les "
                                    + fitted.length + " octets du jeu";
                        } else {
                            activity.achievements.stop();
                            NativeBridge.closeRom();
                            failure = "SRAM incompatible : fichier original conservé";
                        }
                    }
                    if (failure == null && notice != null) activity.postStatus(notice);
                    // Once the SRAM is in: the ROM is identified by its hash.
                    if (failure == null) activity.achievements.start(http, account, journal, entry.system, rom, hardcore);
                    else activity.achievements.stop();
                    if (failure == null) {
                        // The game's cheats, none while Hardcore is asked for or runs.
                        activity.cheatsLockApplied = activity.achievements.cheatsLocked();
                        if (NativeBridge.setCheats(activity.cheatsLockApplied ? new String[0]
                                : CheatList.active(storedCheats)) >= 0) {
                            String reason = NativeBridge.cheatRefusal();
                            if (reason != null) activity.postStatus(reason);
                        }
                        final boolean locked = activity.cheatsLockApplied;
                        activity.runOnUiThread(() -> {
                            if (activity.disposed.get()) return;
                            activity.cheats = storedCheats;
                            activity.cheatsKey = entry.key;
                            if (activity.playerMenu != null) activity.playerMenu.setCheats(storedCheats, !locked);
                        });
                    } else {
                        activity.runOnUiThread(() -> { activity.cheats = Collections.emptyList(); activity.cheatsKey = null; });
                    }
                    activity.activeEntry = failure == null ? entry : null;
                    activity.activeAccount = failure == null ? activity.achievementPart.accountKey(account, hardcore) : null;
                    final double fps = failure == null ? NativeBridge.frameRate() : 0.0;
                    if (failure == null) {
                        activity.activeSaveKey = entry.key;
                        activity.activeCore = core;
                        String identity = NativeBridge.stateCompatibility();
                        activity.stateCompatibility = identity == null ? null : identity + stateVariant;
                        activity.nextBatterySaveNanos = 0;
                    } else { activity.activeSaveKey = null; activity.stateCompatibility = null; activity.activeCore = null; }
                    final String result = failure;
                    activity.runOnUiThread(() -> {
                        if (!activity.disposed.get()) {
                            activity.achievementOverlay.clear();
                            activity.badges.setHttp(http);
                        }
                        if (!activity.disposed.get() && result == null && activity.gameTitle != null) {
                            activity.gameFps = fps;
                            activity.displayPart.applyDisplayMode();
                            activity.inputPart.releaseInput();
                            activity.gameControls.setShoulders("gba".equals(entry.system));
                            activity.skinPart.updateHeaderMenu();
                            activity.loadedSystem = entry.system;
                            activity.skinPart.showPictureTo(activity.achievementOverlay, entry.system);
                            activity.skinPart.refreshSkin();
                            activity.gameTitle.setText(RomLibrary.displayTitle(entry.title));
                            if (biosMissing) android.widget.Toast.makeText(activity,
                                    "Vrai BIOS GBA absent : RavenCore utilise le sien. Choisissez son fichier "
                                            + "dans Paramètres → Console.", android.widget.Toast.LENGTH_LONG).show();
                            if (activity.restoreMenu) { activity.restoreMenu = false; activity.menuPart.togglePlayerMenu(); }
                        }
                    });
                    if (result != null) activity.postStatus("Échec : " + result);
                });
            } catch (Exception error) {
                activity.postStatus("Chargement impossible : " + error.getMessage());
            }
        });
    }

    /** IO thread: the beta's launchLinked. Both games run in RavenCore's link
     * subsystem, each with its own save; no achievements, cheats or states. */
    void loadLinkedFromLibrary(RomLibrary.Entry first, RomLibrary.Entry second) {
        try {
            byte[] firstRom = RomLibrary.readRom(activity.getFilesDir(), first);
            byte[] secondRom = RomLibrary.readRom(activity.getFilesDir(), second);
            final byte[][] batteries = {SaveRepository.read(activity.getFilesDir(), first.key, "srm"),
                    SaveRepository.read(activity.getFilesDir(), second.key, "srm")};
            ConsoleSettings console = ConsoleSettings.load(activity);
            final String core = LinkCable.core(console.coreFor(first.system), console.coreFor(second.system));
            final String hardware = LinkCable.hardware(console.hardwareFor(first.system),
                    console.hardwareFor(second.system));
            if (core == null) {
                activity.postStatus("Ces deux jeux ne se relient pas : Game Boy et Game Boy Color ensemble, "
                        + "ou deux Game Boy Advance.");
                return;
            }
            byte[] bios = null;
            if ("gba".equals(core) && console.gbaBios != ConsoleSettings.BIOS_RAVENCORE) {
                try { bios = GbaBiosRepository.read(activity.getFilesDir()); }
                catch (IOException unreadable) { bios = null; }
            }
            String[] coreOptions = CoreOptions.atLoad(core, hardware, PlaybackSettings.load(activity), console,
                    CartridgeSettings.load(activity), bios != null);
            if (activity.disposed.get()) return;
            String nativeDir = activity.getApplicationInfo().nativeLibraryDir;
            String dataDir = activity.getFilesDir().getAbsolutePath();
            final int focus = activity.linkFocus;
            queueRomLoad(() -> {
                if (activity.disposed.get()) return;
                activity.undoState = null;
                activity.achievements.stop();
                String failure = NativeBridge.openLinked(nativeDir, dataDir, core, firstRom, first.title,
                        secondRom, second.title, coreOptions);
                String notice = null;
                for (int player = 0; failure == null && player < 2; player++) {
                    byte[] battery = batteries[player];
                    if (battery == null || NativeBridge.restorePlayerBattery(player, battery)) continue;
                    // As for a game alone: a save of another size, read as RavenCore reads one.
                    byte[] fitted = SaveTransfer.fit(battery, NativeBridge.savePlayerBattery(player));
                    if (fitted == null) {
                        notice = "Joueur " + (player + 1) + " : ce jeu n'a pas de mémoire de sauvegarde, "
                                + "fichier conservé";
                    } else if (fitted != battery && NativeBridge.restorePlayerBattery(player, fitted)) {
                        notice = "Joueur " + (player + 1) + " : sauvegarde de " + battery.length
                                + " octets lue sur les " + fitted.length + " octets du jeu";
                    } else {
                        NativeBridge.closeRom();
                        failure = "SRAM incompatible (joueur " + (player + 1) + ") : fichier original conservé";
                    }
                }
                if (failure == null && notice != null) activity.postStatus(notice);
                if (failure == null) NativeBridge.setLinkFocus(focus);
                activity.activeEntry = null;
                activity.activeAccount = null;
                activity.activeSaveKey = null;
                activity.stateCompatibility = null;
                activity.activeCore = failure == null ? core : null;
                activity.linkKeys = failure == null ? new String[]{first.key, second.key} : null;
                activity.nextBatterySaveNanos = 0;
                final double fps = failure == null ? NativeBridge.frameRate() : 0.0;
                final String result = failure;
                activity.runOnUiThread(() -> {
                    activity.cheats = Collections.emptyList();
                    activity.cheatsKey = null;
                    if (activity.disposed.get()) return;
                    activity.achievementOverlay.clear();
                    if (result != null || activity.gameTitle == null) return;
                    activity.gameFps = fps;
                    activity.displayPart.applyDisplayMode();
                    activity.inputPart.releaseInput();
                    activity.gameControls.setShoulders("gba".equals(first.system));
                    activity.skinPart.updateHeaderMenu();
                    activity.skinPart.showPictureTo(activity.achievementOverlay, first.system);
                    activity.linkEntries = new RomLibrary.Entry[]{first, second};
                    showLinkTitle();
                });
                if (result != null) activity.postStatus("Échec : " + result);
            });
        } catch (Exception error) {
            activity.postStatus("Chargement impossible : " + error.getMessage());
        }
    }

    /** UI thread: the bar of linked games, as the beta wrote it. */
    void showLinkTitle() {
        if (activity.gameTitle == null || activity.linkSwitch == null) return;
        android.text.SpannableStringBuilder title =
                new android.text.SpannableStringBuilder("Câble link · Joueur " + (activity.linkFocus + 1));
        if (activity.linkEntries != null) {
            int start = title.length();
            title.append('\n').append(RomLibrary.displayTitle(activity.linkEntries[activity.linkFocus].title));
            title.setSpan(new android.text.style.RelativeSizeSpan(0.72f), start, title.length(), 0);
            title.setSpan(new android.text.style.ForegroundColorSpan(activity.theme.muted), start, title.length(), 0);
        }
        activity.gameTitle.setText(title);
        activity.linkSwitch.setText("Joueur " + (2 - activity.linkFocus));
        activity.linkSwitch.setContentDescription("Passer au joueur " + (2 - activity.linkFocus));
    }

    /** The other player shows and plays; the one left keeps running. */
    void switchLinkedPlayer() {
        if (activity.linkKeys == null || activity.disposed.get()) return;
        activity.linkFocus = 1 - activity.linkFocus;
        final int focus = activity.linkFocus;
        activity.inputPart.releaseInput();
        activity.surface.queueEvent(() -> {
            if (!activity.disposed.get()) NativeBridge.setLinkFocus(focus);
        });
        showLinkTitle();
    }

    void queueRomLoad(Runnable load) {
        activity.runOnUiThread(() -> {
            if (activity.disposed.get()) return;
            activity.pendingRomLoad = load;
            dispatchRomLoad();
        });
    }

    /** A storage callback may arrive after GLSurfaceView acknowledged pause.
     * Defer opening a core so it cannot race the paused-session cleanup. */
    void dispatchRomLoad() {
        if (activity.disposed.get() || !activity.foreground || activity.pendingRomLoad == null) return;
        Runnable load = activity.pendingRomLoad;
        activity.pendingRomLoad = null;
        activity.surface.queueEvent(() -> {
            if (activity.disposed.get()) return;
            if (!activity.foreground) { queueRomLoad(load); return; }
            load.run();
        });
    }
}
