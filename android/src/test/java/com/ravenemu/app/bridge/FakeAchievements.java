package com.ravenemu.app.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Stands in for the C module behind NativeAchievements (tested on its own by
 * tests/jni): each login asks one request, each answer raises the events the
 * test scripted. Its requests go to a host AchievementsHttp refuses, so no
 * test reaches the network. */
@Implements(value = NativeAchievements.class, isInAndroidSdk = false)
public class FakeAchievements {
    public static final String BLOCKED_URL = "https://blocked.invalid/dorequest.php";
    public static final List<String> log = new ArrayList<>();
    /** {handle, id, status} of each answer given back. */
    public static final List<int[]> answers = new ArrayList<>();
    /** What the next answer leads to: kind, title, detail, extra, image, id. */
    public static final ArrayDeque<String[]> afterAnswer = new ArrayDeque<>();
    /** The game's lists, as the module would copy them: entries of ten
     * fields, boards of six; scores: board, total, around, then five per line. */
    public static final List<String[]> entries = new ArrayList<>(), boards = new ArrayList<>();
    public static String[] scores = {"0", "0", "0"};
    public static String richPresence = "";
    /** user, display name, game, hardcore, softcore, game id, unlocked, total, points unlocked, points total. */
    public static String[] summary = {"Raven", "Raven Rouge", "", "12", "34", "0", "0", "0", "0", "0"};
    /** "board around" of each scores query. */
    public static final List<String> fetched = new ArrayList<>();
    public static String startUser, startToken, lastUser, lastPassword, lastToken;
    public static int startConsole, startRomLength;
    /** Hardcore as the game started, and as it is now; whether a pause is allowed. */
    public static boolean startHardcore, hardcoreOn, pauseAllowed = true;
    public static int pauseRequests;
    public static Runnable onStart;
    private static final ArrayDeque<String[]> requests = new ArrayDeque<>();
    private static final ArrayDeque<String[]> events = new ArrayDeque<>();
    private static final Set<Long> live = new HashSet<>();
    private static long lastHandle, player;

    public static synchronized void reset() {
        log.clear(); answers.clear(); afterAnswer.clear(); requests.clear(); events.clear(); live.clear();
        startUser = startToken = lastUser = lastPassword = lastToken = null;
        startConsole = startRomLength = 0;
        onStart = null;
        entries.clear(); boards.clear(); fetched.clear();
        startHardcore = hardcoreOn = false;
        pauseAllowed = true;
        pauseRequests = 0;
        scores = new String[]{"0", "0", "0"};
        richPresence = "";
        summary = new String[]{"Raven", "Raven Rouge", "", "12", "34", "0", "0", "0", "0", "0"};
        player = 0;
    }

    /** An event raised without a request, as during frames. */
    public static synchronized void raise(String... fields) { events.add(fields); }

    /** A request the module asks to send next, such as an award. */
    public static synchronized void request(int id, String url, String post) {
        requests.add(new String[]{String.valueOf(id), url, post, "application/x-www-form-urlencoded"});
    }

    @Implementation protected static void __staticInitializer__() {}

    @Implementation protected static synchronized long createAccount() {
        long handle = ++lastHandle;
        live.add(handle);
        log.add("account " + handle);
        return handle;
    }

    @Implementation protected static synchronized long startGame(byte[] user, byte[] token, int console, byte[] rom,
                                                                  boolean hardcore) {
        if (onStart != null) onStart.run();
        hardcoreOn = hardcore;
        startHardcore = hardcore;
        live.remove(player);
        startUser = text(user); startToken = text(token); startConsole = console; startRomLength = rom.length;
        player = ++lastHandle;
        live.add(player);
        log.add("start " + player);
        requests.add(new String[]{"1", BLOCKED_URL, "r=login2&u=" + startUser + "&t=" + startToken, ""});
        return player;
    }

    @Implementation protected static synchronized void stopGame(long handle) {
        if (handle != player || !live.remove(handle)) return;
        player = 0;
        log.add("stop " + handle);
    }

    @Implementation protected static synchronized void destroy(long handle) {
        if (handle != player && live.remove(handle)) log.add("destroy " + handle);
    }

    @Implementation protected static synchronized void loginPassword(long handle, byte[] user, byte[] password) {
        if (!live.contains(handle)) return;
        lastUser = text(user); lastPassword = text(password);
        requests.add(new String[]{"1", BLOCKED_URL, "r=login2&u=" + lastUser, ""});
    }

    @Implementation protected static synchronized void loginToken(long handle, byte[] user, byte[] token) {
        if (!live.contains(handle)) return;
        lastUser = text(user); lastToken = text(token);
        requests.add(new String[]{"1", BLOCKED_URL, "r=login2&u=" + lastUser + "&t=" + lastToken, ""});
    }

    @Implementation protected static void idle(long handle) {}

    @Implementation protected static synchronized byte[][] nextRequest(long handle) {
        return live.contains(handle) ? bytes(requests.poll()) : null;
    }

    @Implementation protected static synchronized void respond(long handle, int id, byte[] body, int status) {
        if (!live.contains(handle)) return;
        answers.add(new int[]{(int)handle, id, status});
        for (String[] event; (event = afterAnswer.poll()) != null; ) events.add(event);
    }

    @Implementation protected static synchronized byte[][] nextEvent(long handle) {
        return live.contains(handle) ? bytes(events.poll()) : null;
    }

    @Implementation protected static synchronized byte[][] summary(long handle) {
        return live.contains(handle) ? bytes(summary) : null;
    }

    @Implementation protected static synchronized byte[][] entries(long handle) {
        return live.contains(handle) ? bytes(flatten(entries)) : new byte[0][];
    }

    @Implementation protected static synchronized byte[][] boards(long handle) {
        return live.contains(handle) ? bytes(flatten(boards)) : new byte[0][];
    }

    @Implementation protected static synchronized void fetchScores(long handle, int board, boolean around) {
        if (live.contains(handle)) fetched.add(board + " " + around);
    }

    @Implementation protected static synchronized byte[][] scores(long handle) {
        return live.contains(handle) ? bytes(scores) : null;
    }

    @Implementation protected static synchronized byte[] richPresence(long handle) {
        return (live.contains(handle) ? richPresence : "").getBytes(StandardCharsets.UTF_8);
    }

    private static String[] flatten(List<String[]> rows) {
        List<String> all = new ArrayList<>();
        for (String[] row : rows) java.util.Collections.addAll(all, row);
        return all.toArray(new String[0]);
    }

    @Implementation protected static synchronized void setHardcore(long handle, boolean enabled) {
        if (live.contains(handle)) hardcoreOn = enabled;
    }

    @Implementation protected static synchronized boolean hardcore(long handle) {
        return live.contains(handle) && hardcoreOn;
    }

    @Implementation protected static synchronized boolean canPause(long handle) {
        pauseRequests++;
        return !live.contains(handle) || pauseAllowed;
    }

    @Implementation protected static String sdkVersion() { return "rcheevos/12.5"; }
    @Implementation protected static String engineVersion() { return "0.14.0"; }

    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    private static byte[][] bytes(String[] fields) {
        if (fields == null) return null;
        byte[][] result = new byte[fields.length][];
        for (int i = 0; i < fields.length; i++) result[i] = fields[i].getBytes(StandardCharsets.UTF_8);
        return result;
    }
}
