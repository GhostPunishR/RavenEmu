package com.ravenemu.android

/**
 * Hardcore start barrier, free of Android types. In Hardcore the game must not
 * run a frame the SDK cannot evaluate: it waits for the sign-in and for the
 * identification of the ROM. A ROM the service does not know has nothing to
 * evaluate, so it runs.
 */
internal object HardcoreGate {
    /** Where the running ROM's game session stands with RetroAchievements. */
    enum class Stage {
        /** No game session: no account, or no ROM running. */
        IDLE,
        SIGNING_IN,
        LOADING,
        /** Sign-in or identification failed for want of the network; retried. */
        WAITING_NETWORK,
        /** The SDK evaluates the game. */
        TRACKING,
        /** No achievements here: a ROM the service does not know, or a refusal. */
        UNTRACKED,
    }

    /** Only a running game is held, so without one nothing waits or retries. */
    fun holdFrames(hardcoreWanted: Boolean, gameRunning: Boolean, stage: Stage): Boolean =
        hardcoreWanted && gameRunning &&
            (stage == Stage.SIGNING_IN || stage == Stage.LOADING || stage == Stage.WAITING_NETWORK)

    /** Wait before retry number [attempt] (from 0): 5 s, doubled each time, at most 60 s. */
    fun retryDelayMillis(attempt: Int): Long =
        (5_000L shl attempt.coerceIn(0, 4)).coerceAtMost(60_000L)
}
