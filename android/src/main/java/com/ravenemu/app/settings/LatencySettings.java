package com.ravenemu.app.settings;


import android.content.Context;
import java.util.Map;

/** Settings → Général → Latence: preemptive frames, a native addition after
 * RetroArch's (the beta has none). Stored with the playback preferences of
 * the native preview; no beta data is read. */
public final class LatencySettings {
    /** RAVEN_FRONTEND_PREEMPT_MAX in the C frontend. */
    public static final int PREEMPTIVE_MAX = 3;

    public static final LatencySettings DEFAULTS = new LatencySettings(0);

    /** Frames replayed when a button changes; 0, the default, is off. */
    public final int preemptiveFrames;

    public LatencySettings(int preemptiveFrames) {
        this.preemptiveFrames = Math.max(0, Math.min(PREEMPTIVE_MAX, preemptiveFrames));
    }

    public static LatencySettings load(Context context) {
        Object frames = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE)
                .getAll().get("latency.preemptive_frames");
        return new LatencySettings(frames instanceof Integer ? (Integer)frames : DEFAULTS.preemptiveFrames);
    }

    public void save(Context context) {
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
                .putInt("latency.preemptive_frames", preemptiveFrames).apply();
    }
}
