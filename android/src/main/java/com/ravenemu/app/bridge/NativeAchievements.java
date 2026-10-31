package com.ravenemu.app.bridge;

import java.nio.charset.StandardCharsets;

/** JNI API of the C frontend's RetroAchievements (frontend/src/achievements.c,
 * over the official rcheevos SDK). An instance is used on one thread: the
 * player's on the GL thread, the account page's on its worker. Java sends
 * each request over HTTPS and gives the answer back; texts cross as UTF-8. */
public final class NativeAchievements {
    static {
        System.loadLibrary("raven_android");
    }

    /** RetroAchievements' console ids. */
    public static final int GAME_BOY = 4, GAME_BOY_COLOR = 6, GAME_BOY_ADVANCE = 5;
    /** The status of a request that got no answer (no network). */
    public static final int NO_ANSWER = -1;

    /** A request for retroachievements.org. */
    public static final class Request {
        public final int id;
        public final String url, postData, contentType;
        Request(int id, String url, String postData, String contentType) {
            this.id = id; this.url = url; this.postData = postData; this.contentType = contentType;
        }
    }

    /** What happened, as frontend/include/ravenemu/achievements.h lists it. */
    public static final class Event {
        public final String kind, title, detail, extra, image, id;
        public Event(String kind, String title, String detail, String extra, String image, String id) {
            this.kind = kind; this.title = title; this.detail = detail;
            this.extra = extra; this.image = image; this.id = id;
        }
    }

    /** The account and its game. Hardcore points are the account's score. */
    public static final class Summary {
        public final String user, displayName, gameTitle;
        public final long hardcorePoints, softcorePoints, gameId, unlocked, total, pointsUnlocked, pointsTotal;
        Summary(String[] fields) {
            user = fields[0]; displayName = fields[1]; gameTitle = fields[2];
            hardcorePoints = number(fields[3]); softcorePoints = number(fields[4]); gameId = number(fields[5]);
            unlocked = number(fields[6]); total = number(fields[7]);
            pointsUnlocked = number(fields[8]); pointsTotal = number(fields[9]);
        }
    }

    private final long handle;
    private final boolean player;

    private NativeAchievements(long handle, boolean player) {
        this.handle = handle;
        this.player = player;
    }

    /** For the account page: a login without a game; null without memory. */
    public static NativeAchievements account() {
        long handle = createAccount();
        return handle == 0 ? null : new NativeAchievements(handle, false);
    }

    /** GL thread, once the game loaded: signs in with the stored token and
     * identifies the ROM. Replaces the previous game's; null if impossible. */
    public static NativeAchievements startGame(String user, String token, int console, byte[] rom) {
        long handle = startGame(utf8(user), utf8(token), console, rom);
        return handle == 0 ? null : new NativeAchievements(handle, true);
    }

    public void loginPassword(String user, String password) {
        byte[] secret = utf8(password);
        loginPassword(handle, utf8(user), secret);
        java.util.Arrays.fill(secret, (byte)0);
    }

    public void loginToken(String user, String token) { loginToken(handle, utf8(user), utf8(token)); }

    /** Without frames (the account page): retries what is due. The player's
     * is kept alive by the bridge's frames and pauses. */
    public void idle() { idle(handle); }

    public Request nextRequest() {
        byte[][] fields = nextRequest(handle);
        if (fields == null || fields.length != 4) return null;
        return new Request((int)number(text(fields[0])), text(fields[1]), text(fields[2]), text(fields[3]));
    }

    /** status: the HTTP status, NO_ANSWER when none came. */
    public void respond(int id, byte[] body, int status) { respond(handle, id, body, status); }

    public Event nextEvent() {
        byte[][] fields = nextEvent(handle);
        if (fields == null || fields.length != 6) return null;
        return new Event(text(fields[0]), text(fields[1]), text(fields[2]),
                text(fields[3]), text(fields[4]), text(fields[5]));
    }

    public Summary summary() {
        byte[][] fields = summary(handle);
        if (fields == null || fields.length != 10) return null;
        String[] texts = new String[fields.length];
        for (int i = 0; i < fields.length; i++) texts[i] = text(fields[i]);
        return new Summary(texts);
    }

    /** Requests still out are answered locally first. Then unusable. */
    public void close() {
        if (player) stopGame(handle); else destroy(handle);
    }

    /** "rcheevos/x.y", as RetroAchievements asks each client to say. */
    public static native String sdkVersion();
    /** RavenCore's version, built into the library. */
    public static native String engineVersion();

    private static native long createAccount();
    private static native long startGame(byte[] user, byte[] token, int console, byte[] rom);
    private static native void stopGame(long handle);
    private static native void destroy(long handle);
    private static native void loginPassword(long handle, byte[] user, byte[] password);
    private static native void loginToken(long handle, byte[] user, byte[] token);
    private static native void idle(long handle);
    private static native byte[][] nextRequest(long handle);
    private static native void respond(long handle, int id, byte[] body, int status);
    private static native byte[][] nextEvent(long handle);
    private static native byte[][] summary(long handle);

    private static byte[] utf8(String text) {
        return (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static long number(String text) {
        try { return Long.parseLong(text); }
        catch (NumberFormatException malformed) { return 0; }
    }
}
