package com.ravenemu.app.ui.player;

import com.ravenemu.app.R;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

/** The crow's cry for an unlock and a completed game: the owner's own
 * recording, brought unchanged from CorvusRetro (res/raw/achievement_unlock.mp3,
 * 3.03 s, SHA-256 f00a367f…c2531). Android decodes and plays it with a
 * SoundPool, apart from the game's sound (AAudio), at the game's volume and
 * while the game's sound is on. Loaded once, with the overlay: a cry asked
 * for before it is loaded plays as soon as it is, unless a second has passed.
 * A new cry starts it over; the pause and the closing game stop it. UI thread. */
final class UnlockCry {
    static final int SOUND = R.raw.achievement_unlock;
    /** Later than this after it was asked for, a cry would no longer go with its popup. */
    static final long LATE_MILLIS = 1_000;
    private SoundPool pool;
    private int sound, stream, askedVolume;
    private boolean loaded;
    /** When a cry was asked for before the sound was loaded; 0 for none. */
    private long askedAt;

    UnlockCry(Context context) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool.setOnLoadCompleteListener((owner, id, status) -> {
                if (owner != pool || id != sound) return;
                loaded = status == 0;
                final long asked = askedAt;
                askedAt = 0;
                if (loaded && asked != 0 && SystemClock.uptimeMillis() - asked <= LATE_MILLIS) start(askedVolume);
            });
            sound = pool.load(context.getApplicationContext(), SOUND, 1);
        } catch (RuntimeException unavailable) {
            // No audio service: the popups still show, silent.
            release();
        }
    }

    /** volume: 0 to 100, as Settings → Audio. */
    void play(int volume) {
        if (pool == null || volume <= 0) return;
        final int clamped = Math.min(100, volume);
        if (!loaded) {
            askedAt = SystemClock.uptimeMillis();
            askedVolume = clamped;
            return;
        }
        start(clamped);
    }

    private void start(int volume) {
        if (pool == null) return;
        if (stream != 0) pool.stop(stream);
        final float gain = volume / 100f;
        stream = pool.play(sound, gain, gain, 1, 0, 1f);
    }

    /** The game pauses or closes: no cry goes on, none waits. */
    void stop() {
        askedAt = 0;
        if (pool != null && stream != 0) pool.stop(stream);
        stream = 0;
    }

    void release() {
        stop();
        if (pool != null) {
            pool.setOnLoadCompleteListener(null);
            pool.release();
        }
        pool = null;
        loaded = false;
    }
}
