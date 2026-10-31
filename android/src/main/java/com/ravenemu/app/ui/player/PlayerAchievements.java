package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.platform.AchievementsHttp;
import com.ravenemu.app.settings.AchievementAccount;
import com.ravenemu.app.settings.AwardJournal;
import com.ravenemu.app.settings.AwardPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** RetroAchievements during a game: the C frontend's instance on the GL
 * thread, which the bridge gives each frame shown; its requests sent by two
 * network threads, as in the beta; their answers fed back at the next frame;
 * what happens handed to the UI thread. Each award request enters the offline
 * journal before it is sent and leaves it once the service accepted it; what
 * an earlier session left is replayed once this one is signed in. */
final class PlayerAchievements {
    interface Listener { void onEvent(NativeAchievements.Event event); }

    /** Shared by the players: a request still out when one closes ends there. */
    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(2, work -> {
        Thread thread = new Thread(work, "RavenEmu-RetroAchievements");
        thread.setDaemon(true);
        return thread;
    });
    /** The replay of earlier sessions' unlocks: one worker, with its own delays. */
    private static final ScheduledExecutorService REPLAY = Executors.newSingleThreadScheduledExecutor(work -> {
        Thread thread = new Thread(work, "RavenEmu-RetroAchievements-replay");
        thread.setDaemon(true);
        return thread;
    });
    static final long REPLAY_FIRST_DELAY_MILLIS = 5_000, REPLAY_MAX_DELAY_MILLIS = 120_000;
    static final String STORAGE_NOTICE = "Le stockage sécurisé est indisponible ou plein : gardez le jeu ouvert "
            + "jusqu'à la synchronisation.";

    private static final class Answer {
        final int generation, id, status;
        final byte[] body;
        Answer(int generation, int id, AchievementsHttp.Answer answer) {
            this.generation = generation; this.id = id; this.body = answer.body; this.status = answer.status;
        }
    }

    private final Executor ui;
    private final Listener listener;
    private final ConcurrentLinkedQueue<Answer> answers = new ConcurrentLinkedQueue<>();
    /** GL thread. */
    private NativeAchievements engine;
    private AchievementsHttp http;
    private AwardJournal journal;
    private AchievementAccount account;
    private int generation;
    private boolean replayStarted;
    /** This session's: never closed by another, never reopened. */
    private AtomicBoolean closed = new AtomicBoolean(true);
    /** The unlocks this session journaled itself: rcheevos owns their retries. */
    private Set<String> ownAwards = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean storageWarned = new AtomicBoolean();
    /** For the game menu (GL thread, but sync from the network threads too). */
    private String status = AchievementsSnapshot.SIGNED_OUT.status, avatarUrl = "";
    private Map<Integer, Integer> sync = new ConcurrentHashMap<>();
    private int scoresBoard;
    private boolean scoresAround, scoresLoading;
    private String scoresError = "";

    PlayerAchievements(Executor ui, Listener listener) {
        this.ui = ui;
        this.listener = listener;
    }

    /** RetroAchievements' console of the ROM, whatever core runs it; 0 for none. */
    static int console(String system) {
        if ("gb".equals(system)) return NativeAchievements.GAME_BOY;
        if ("gbc".equals(system)) return NativeAchievements.GAME_BOY_COLOR;
        if ("gba".equals(system)) return NativeAchievements.GAME_BOY_ADVANCE;
        return 0;
    }

    /** GL thread, once the game loaded: signs in with the stored token and
     * identifies the ROM. Without an account, nothing reaches the network. */
    void start(AchievementsHttp http, AchievementAccount account, AwardJournal journal, String system, byte[] rom) {
        stop();
        final int console = console(system);
        if (http == null || account == null || console == 0 || rom == null) return;
        this.http = http;
        this.account = account;
        this.journal = journal;
        closed = new AtomicBoolean(false);
        ownAwards = ConcurrentHashMap.newKeySet();
        sync = new ConcurrentHashMap<>();
        replayStarted = false;
        avatarUrl = account.avatarUrl;
        scoresBoard = 0;
        scoresLoading = false;
        scoresError = "";
        status = "Connexion en cours…";
        engine = NativeAchievements.startGame(account.user, account.token, console, rom);
        if (engine == null) status = "RetroAchievements indisponible pour ce jeu";
        pump();
    }

    boolean active() { return engine != null; }

    /** GL thread, after each drawn frame: the answers come in first, then
     * the requests they lead to go out, then what happened goes to the UI. */
    void pump() {
        final NativeAchievements current = engine;
        if (current == null) return;
        for (Answer answer; (answer = answers.poll()) != null; )
            if (answer.generation == generation) current.respond(answer.id, answer.body, answer.status);
        final AchievementsHttp network = http;
        final AwardJournal queue = journal;
        for (NativeAchievements.Request request; (request = current.nextRequest()) != null; ) {
            final NativeAchievements.Request sent = request;
            final int sentGeneration = generation;
            final AwardJournal.Award award = queue != null && AwardPolicy.isSdkAward(sent.url, sent.postData)
                    ? new AwardJournal.Award(account.user, sent.url, sent.postData, sent.contentType) : null;
            final Set<String> own = ownAwards;
            final Map<Integer, Integer> states = sync;
            NETWORK.execute(() -> {
                // Kept before it is sent: the app closing cannot lose it.
                AwardPolicy.Key key = award == null ? null : AwardPolicy.requestKey(award.post);
                if (award != null) {
                    if (key != null) {
                        own.add(key.id + ":" + key.hardcore);
                        promote(states, key.id, AchievementsSnapshot.SYNC_PENDING);
                    }
                    if (!queue.remember(award)) warnStorage();
                }
                AchievementsHttp.Answer answer = network.send(sent.url, sent.postData, sent.contentType);
                if (award != null && AwardPolicy.accepted(answer.status, answer.body)) {
                    queue.acknowledge(award);
                    if (key != null) states.put(key.id, AchievementsSnapshot.SYNC_CONFIRMED);
                }
                answers.add(new Answer(sentGeneration, sent.id, answer));
            });
        }
        for (NativeAchievements.Event event; (event = current.nextEvent()) != null; ) {
            if ("login".equals(event.kind)) replayEarlierAwards(event.title, event.extra);
            follow(event);
            final NativeAchievements.Event shown = event;
            ui.execute(() -> listener.onEvent(shown));
        }
    }

    /** Once per session, for the account signed in: what earlier sessions
     * left, sent again with this session's token until the service accepts. */
    private void replayEarlierAwards(String user, String token) {
        final AwardJournal queue = journal;
        if (replayStarted || queue == null || token == null || token.isEmpty()) return;
        replayStarted = true;
        final AtomicBoolean done = closed;
        final Set<String> own = ownAwards;
        final Map<Integer, Integer> states = sync;
        final AchievementsHttp network = http;
        REPLAY.execute(new Runnable() {
            List<AwardJournal.Award> pending;
            long delay = REPLAY_FIRST_DELAY_MILLIS;
            @Override public void run() {
                if (done.get() || !queue.isCurrent()) return;
                if (pending == null) {
                    pending = new ArrayList<>(queue.forAccount(user));
                    for (AwardJournal.Award entry : pending) {
                        AwardPolicy.Key key = AwardPolicy.requestKey(entry.post);
                        if (key != null) promote(states, key.id, AchievementsSnapshot.SYNC_PENDING);
                    }
                }
                List<AwardJournal.Award> left = new ArrayList<>();
                for (AwardJournal.Award entry : pending) {
                    if (done.get() || !queue.isCurrent()) return;
                    AwardPolicy.Key key = AwardPolicy.requestKey(entry.post);
                    if (key != null && own.contains(key.id + ":" + key.hardcore)) continue;
                    String post = AwardPolicy.authenticatedReplay(entry.url, entry.post, user, token);
                    if (post == null) continue;
                    AchievementsHttp.Answer answer = network.send(entry.url, post, entry.contentType);
                    if (AwardPolicy.accepted(answer.status, answer.body)) {
                        queue.acknowledge(entry);
                        if (key != null) states.put(key.id, AchievementsSnapshot.SYNC_CONFIRMED);
                    } else {
                        left.add(entry);
                    }
                }
                pending = left;
                if (pending.isEmpty() || done.get() || !queue.isCurrent()) return;
                final long wait = delay;
                delay = Math.min(delay * 2, REPLAY_MAX_DELAY_MILLIS);
                REPLAY.schedule(this, wait, TimeUnit.MILLISECONDS);
            }
        });
    }

    /** A state only moves forward: confirmed stays confirmed. */
    private static void promote(Map<Integer, Integer> states, int id, int state) {
        states.merge(id, state, Math::max);
    }

    /** The status line, the avatar, unlocks and scores, as the beta's
     * AchievementClient follows them. */
    private void follow(NativeAchievements.Event event) {
        switch (event.kind) {
            case "login":
                status = "Chargement des succès…";
                if (!event.image.isEmpty()) avatarUrl = event.image;
                break;
            case "game": case "online":
                status = "Connecté • Mode normal (Softcore)";
                break;
            case "login_error": case "game_unknown": case "game_offline": case "game_error": case "offline": {
                AchievementNotices.Notice notice = AchievementNotices.of(event);
                if (notice != null) status = notice.title.isEmpty() ? notice.heading : notice.title;
                break;
            }
            case "achievement":
                try { promote(sync, Integer.parseInt(event.id), AchievementsSnapshot.SYNC_LOCAL); }
                catch (NumberFormatException malformed) { /* no id, nothing to follow */ }
                break;
            case "scores":
                scoresLoading = false;
                scoresError = "";
                break;
            case "scores_error":
                scoresLoading = false;
                scoresError = "Impossible de charger les scores. Réessayez.";
                break;
            default:
                break;
        }
    }

    /** GL thread: a leaderboard's best scores, or those around the player. */
    void fetchScores(int board, boolean around) {
        if (engine == null) return;
        scoresBoard = board;
        scoresAround = around;
        scoresLoading = true;
        scoresError = "";
        engine.fetchScores(board, around);
    }

    /** GL thread: what the game menu shows now; without a session, the state
     * it is in. The pending unlocks are counted elsewhere (disk). */
    AchievementsSnapshot snapshot() {
        final NativeAchievements current = engine;
        if (current == null || account == null)
            return account == null ? AchievementsSnapshot.SIGNED_OUT
                    : new AchievementsSnapshot(true, status, account.user, account.displayName, avatarUrl,
                            account.softcorePoints, account.hardcorePoints, "", 0, 0, 0, 0, 0, "",
                            new NativeAchievements.Entry[0], new int[0], new NativeAchievements.Board[0],
                            null, 0, false, false, "", 0);
        NativeAchievements.Summary summary = current.summary();
        NativeAchievements.Entry[] entries = current.entries();
        int[] states = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            Integer state = sync.get(entries[i].id);
            states[i] = state == null ? AchievementsSnapshot.SYNC_NONE
                    : entries[i].unlocked() || state == AchievementsSnapshot.SYNC_CONFIRMED ? state
                    : AchievementsSnapshot.SYNC_NONE;
        }
        boolean known = summary != null && !summary.user.isEmpty();
        return new AchievementsSnapshot(true, status, known ? summary.user : account.user,
                known ? summary.displayName : account.displayName, avatarUrl,
                known ? summary.softcorePoints : account.softcorePoints,
                known ? summary.hardcorePoints : account.hardcorePoints,
                summary == null ? "" : summary.gameTitle, summary == null ? 0 : summary.gameId,
                summary == null ? 0 : summary.unlocked, summary == null ? 0 : summary.total,
                summary == null ? 0 : summary.pointsUnlocked, summary == null ? 0 : summary.pointsTotal,
                current.richPresence(), entries, states, current.boards(), current.scores(), scoresBoard,
                scoresAround, scoresLoading, scoresError, 0);
    }

    /** For the pending unlocks of the snapshot's account. */
    AwardJournal journal() { return journal; }

    /** Once per session, as in the beta. */
    private void warnStorage() {
        if (!storageWarned.compareAndSet(false, true)) return;
        final NativeAchievements.Event notice = new NativeAchievements.Event("notice", "Succès hors ligne",
                STORAGE_NOTICE, "", "", "");
        ui.execute(() -> listener.onEvent(notice));
    }

    /** GL thread, or the UI thread once the GL thread paused: requests still
     * out are settled locally; their late answers are dropped; the replay
     * stops. The journal keeps whatever the service has not accepted. */
    void stop() {
        if (engine != null) engine.close();
        engine = null;
        account = null;
        journal = null;
        status = AchievementsSnapshot.SIGNED_OUT.status;
        closed.set(true);
        generation++;
        answers.clear();
    }
}
