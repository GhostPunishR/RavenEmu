package com.ravenemu.platform.vibration

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ravenemu.emulation.api.session.EmulationSession

/** Sortie Android du moteur de vibration des cartouches (MBC5 rumble). */
class AndroidRumbleSink(context: Context) : EmulationSession.RumbleSink {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var active = false

    override fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (active) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, PULSE_MILLIS),
                    0,
                ),
            )
        } else {
            vibrator.cancel()
        }
    }

    override fun release() {
        active = false
        vibrator.cancel()
    }

    private companion object {
        const val PULSE_MILLIS = 1_000L
    }
}
