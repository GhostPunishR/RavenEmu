package com.ravenemu.app.settings;

import android.content.Context;
import java.util.Map;

/** The beta's Settings → RetroAchievements notifications: unlock and
 * progress popups, their chime, where they show and for how long. Stored
 * with the playback preferences of the native preview; no beta data is read. */
public final class AchievementSettings {
    public static final String TOP = "top", BOTTOM = "bottom";
    public static final int POPUP_MIN = 2, POPUP_MAX = 10, PROGRESS_MIN = 1, PROGRESS_MAX = 5;

    public static final AchievementSettings DEFAULTS = new AchievementSettings(true, true, true, TOP, 5, 2);

    /** Unlocks and the completed game. */
    public final boolean unlockPopups;
    /** Measured progress of an achievement. */
    public final boolean progressPopups;
    /** A short chime by RavenEmu for each unlock and the completed game. */
    public final boolean sound;
    public final String position;
    public final int popupSeconds, progressSeconds;

    public AchievementSettings(boolean unlockPopups, boolean progressPopups, boolean sound, String position,
                               int popupSeconds, int progressSeconds) {
        this.unlockPopups = unlockPopups;
        this.progressPopups = progressPopups;
        this.sound = sound;
        this.position = BOTTOM.equals(position) ? BOTTOM : TOP;
        this.popupSeconds = Math.max(POPUP_MIN, Math.min(POPUP_MAX, popupSeconds));
        this.progressSeconds = Math.max(PROGRESS_MIN, Math.min(PROGRESS_MAX, progressSeconds));
    }

    public AchievementSettings withUnlockPopups(boolean value) {
        return new AchievementSettings(value, progressPopups, sound, position, popupSeconds, progressSeconds);
    }
    public AchievementSettings withProgressPopups(boolean value) {
        return new AchievementSettings(unlockPopups, value, sound, position, popupSeconds, progressSeconds);
    }
    public AchievementSettings withSound(boolean value) {
        return new AchievementSettings(unlockPopups, progressPopups, value, position, popupSeconds, progressSeconds);
    }
    public AchievementSettings withPosition(String value) {
        return new AchievementSettings(unlockPopups, progressPopups, sound, value, popupSeconds, progressSeconds);
    }
    public AchievementSettings withPopupSeconds(int value) {
        return new AchievementSettings(unlockPopups, progressPopups, sound, position, value, progressSeconds);
    }
    public AchievementSettings withProgressSeconds(int value) {
        return new AchievementSettings(unlockPopups, progressPopups, sound, position, popupSeconds, value);
    }

    public static AchievementSettings load(Context context) {
        Map<String, ?> values = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE)
                .getAll();
        Object unlock = values.get("achievements.unlock_popups");
        Object progress = values.get("achievements.progress_popups");
        Object sound = values.get("achievements.sound");
        Object position = values.get("achievements.position");
        Object popup = values.get("achievements.popup_seconds");
        Object progressTime = values.get("achievements.progress_seconds");
        return new AchievementSettings(unlock instanceof Boolean ? (Boolean)unlock : DEFAULTS.unlockPopups,
                progress instanceof Boolean ? (Boolean)progress : DEFAULTS.progressPopups,
                sound instanceof Boolean ? (Boolean)sound : DEFAULTS.sound,
                position instanceof String ? (String)position : DEFAULTS.position,
                popup instanceof Integer ? (Integer)popup : DEFAULTS.popupSeconds,
                progressTime instanceof Integer ? (Integer)progressTime : DEFAULTS.progressSeconds);
    }

    public void save(Context context) {
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("achievements.unlock_popups", unlockPopups)
                .putBoolean("achievements.progress_popups", progressPopups)
                .putBoolean("achievements.sound", sound)
                .putString("achievements.position", position)
                .putInt("achievements.popup_seconds", popupSeconds)
                .putInt("achievements.progress_seconds", progressSeconds)
                .apply();
    }
}
