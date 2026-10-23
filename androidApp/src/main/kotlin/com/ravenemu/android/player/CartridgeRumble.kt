package com.ravenemu.android.player

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The cartridge's rumble motor on the phone's vibrator. Games set its strength
 * by switching the motor on and off quickly, so each frame with the motor on
 * extends a short pulse: the felt rhythm follows the game's own.
 * Called from the emulation thread; Vibrator is thread-safe.
 */
internal class CartridgeRumble(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }?.takeIf { it.hasVibrator() }
    @Volatile private var running = false

    /** One frame with the motor on: keep the phone vibrating a little longer. */
    fun pulse() {
        val target = vibrator ?: return
        target.vibrate(VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        running = true
    }

    /** The motor stopped: let the current pulse end on its own (≤ 40 ms). */
    fun release() {
        running = false
    }

    /** Leaving the game: stop at once. */
    fun stop() {
        if (running) vibrator?.cancel()
        running = false
    }

    private companion object {
        // A little over two frames, so a motor held on never gaps.
        const val PULSE_MS = 40L
    }
}
