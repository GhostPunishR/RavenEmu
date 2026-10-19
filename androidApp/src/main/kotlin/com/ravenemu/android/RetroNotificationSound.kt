package com.ravenemu.android

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Locally generated tone, no bundled recording, network or new permission. */
internal object RetroNotificationSound {
    fun play() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45) }.getOrNull() ?: return
        runCatching { tone.startTone(ToneGenerator.TONE_PROP_ACK, 180) }
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 300)
    }
}
