package com.ravenemu.app.settings;

/** RetroAchievements Hardcore, after the beta's RetroHardcorePolicy.kt and
 * HardcoreGate.kt. It needs RetroAchievements' approval of RavenEmu, asked of
 * its team: until then, as in the beta, no switch is shown and nothing here
 * ever turns on. Once on: no save state is loaded, pauses are as rcheevos
 * allows them, and the game waits for the sign-in and the identification of
 * its ROM before it runs a frame. Free of Android types. */
public final class HardcorePolicy {
    public static final String STATE_LOAD_LOCKED = "Mode Hardcore : le chargement d'un état est désactivé.";
    public static final String PAUSE_REFUSED = "Mode Hardcore : pause indisponible pour le moment.";

    /** Where the running ROM's session stands with RetroAchievements. */
    public enum Stage {
        /** No session: no account, or no game. */
        IDLE,
        SIGNING_IN,
        LOADING,
        /** Sign-in or identification failed for want of the network; retried. */
        WAITING_NETWORK,
        /** rcheevos evaluates the game. */
        TRACKING,
        /** Nothing to evaluate: a ROM the service does not know, or a refusal. */
        UNTRACKED,
    }

    private HardcorePolicy() {}

    /** Not yet: a method, not a constant, so a test can stand in for the approval. */
    public static boolean approved() { return AwardPolicy.HARDCORE_APPROVED; }

    public static boolean enabled(boolean requested) { return requested && approved(); }

    /** In Hardcore, a running game waits while rcheevos cannot evaluate it yet. */
    public static boolean holdFrames(boolean hardcore, boolean gameRunning, Stage stage) {
        return hardcore && gameRunning
                && (stage == Stage.SIGNING_IN || stage == Stage.LOADING || stage == Stage.WAITING_NETWORK);
    }

    /** Before retry number `attempt` (from 0): 5 s, doubled each time, at most 60 s. */
    public static long retryDelayMillis(int attempt) {
        return Math.min(60_000L, 5_000L << Math.max(0, Math.min(4, attempt)));
    }
}
