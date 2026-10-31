package com.ravenemu.app.ui.player;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** The beta's unlock chime (UnlockChime.kt), made by RavenEmu: G5, C6, E6
 * then G6, each struck 80 ms after the previous one, ringing out over
 * 0.55 s at a quarter of full scale. Played on its own short track, at the
 * game's volume, while the game's sound is on. */
final class UnlockChime {
    static final int RATE = 48_000;
    private AudioTrack track;

    /** Interleaved stereo PCM16 of the chime at `rate`; empty for none. */
    static short[] pcm(int rate) {
        if (rate <= 0) return new short[0];
        final double[] notes = {783.99, 1046.50, 1318.51, 1567.98};
        final double step = 0.08, ring = 0.55, attack = 0.004, release = 0.05;
        final int frames = (int)((step * 3 + ring) * rate);
        final double[] mono = new double[frames];
        for (int n = 0; n < notes.length; n++) {
            final int start = (int)(n * step * rate);
            for (int i = start; i < frames; i++) {
                final double t = (double)(i - start) / rate;
                if (t >= ring) break;
                // Struck, then ringing out; a short release so it ends without a click.
                final double envelope = Math.min(1.0, t / attack) * Math.exp(-t / 0.16)
                        * Math.min(1.0, (ring - t) / release);
                final double f = notes[n];
                mono[i] += envelope * (Math.sin(2 * Math.PI * f * t) + 0.35 * Math.sin(2 * Math.PI * 2 * f * t)
                        + 0.12 * Math.sin(2 * Math.PI * 3 * f * t));
            }
        }
        double peak = 0;
        for (double sample : mono) peak = Math.max(peak, Math.abs(sample));
        // A quarter of full scale: heard over the game, never harsh.
        final double gain = peak > 0 ? 8000.0 / peak : 0.0;
        final short[] pcm = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            final short sample = (short)Math.round(mono[i] * gain);
            pcm[2 * i] = sample;
            pcm[2 * i + 1] = sample;
        }
        return pcm;
    }

    /** UI thread. volume: 0 to 100, as Settings → Audio. */
    void play(int volume) {
        if (volume <= 0) return;
        try {
            if (track == null) {
                final short[] pcm = pcm(RATE);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.length * 2)
                        .build();
                if (track.write(pcm, 0, pcm.length) != pcm.length) { release(); return; }
            } else {
                track.stop();
                track.reloadStaticData();
            }
            track.setVolume(Math.min(100, volume) / 100f);
            track.play();
        } catch (RuntimeException unavailable) {
            // No output (a device being switched): the banner still shows.
            release();
        }
    }

    void release() {
        if (track != null) track.release();
        track = null;
    }
}
