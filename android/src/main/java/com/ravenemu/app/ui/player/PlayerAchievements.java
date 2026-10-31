package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.platform.AchievementsHttp;
import com.ravenemu.app.settings.AchievementAccount;
import com.ravenemu.app.settings.AwardJournal;
import com.ravenemu.app.settings.AwardPolicy;
import com.ravenemu.app.settings.HardcorePolicy;
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
    /** GL thread: the console's reset, which Hardcore asks for. */
    private final Runnable resetGame;
    /** GL thread: Hardcore as it applies to this game, and where its session stands. */
    private boolean hardcoreWanted;
    private HardcorePolicy.Stage stage = HardcorePolicy.Stage.IDLE;
    private int retryAttempt, console;
    private long retryAtMillis;
    /** Kept only in Hardcore, to sign in again when the network comes back. */
    private byte[] retryRom;
    /** For the UI thread. */
    private volatile boolean hardcoreActive, stateLoadLocked;
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

    PlayerAchievements(Executor ui, Listener listener, Runnable resetGame) {
        this.ui = ui;
        this.listener = listener;
        this.resetGame = resetGame;
    }

    /** RetroAchievements' console of the ROM, whatever core runs it; 0 for none. */
    static int console(String system) {
        if ("gb".equals(system)) return NativeAchievements.GAME_BOY;
        if ("gbc".equals(system)) return NativeAchievements.GAME_BOY_COLOR;
        if ("gba".equals(system)) return NativeAchievements.GAME_BOY_ADVANCE;
        return 0;
    }

    /** GL thread, once the game loaded: signs in with the stored token and
     * identifies the ROM, in Hardcore only once RetroAchievements approved
     * RavenEmu. Without an account, nothing reaches the network. */
    void start(AchievementsHttp http, AchievementAccount account, AwardJournal journal, String system, byte[] rom,
               boolean hardcoreRequested) {
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
        hardcoreWanted = HardcorePolicy.enabled(hardcoreRequested);
        this.console = console;
        retryRom = hardcoreWanted ? rom : null;
        retryAttempt = 0;
        stage = HardcorePolicy.Stage.SIGNING_IN;
        engine = NativeAchievements.startGame(account.user, account.token, console, rom, hardcoreWanted);
        if (engine == null) {
            status = "RetroAchievements indisponible pour ce jeu";
            stage = HardcorePolicy.Stage.IDLE;
        }
        updateGate();
        pump();
    }

    /** GL thread: in Hardcore, no frame while rcheevos cannot evaluate it. */
    boolean holdFrames() { return HardcorePolicy.holdFrames(hardcoreWanted, engine != null, stage); }

    /** UI thread: whether a load must be refused, whether a pause must be asked for. */
    boolean stateLoadLocked() { return stateLoadLocked; }
    boolean hardcoreActive() { return hardcoreActive; }

    /** GL thread: Softcore always may; Hardcore as rcheevos allows, which records it. */
    boolean canPause() { return engine == null || !engine.hardcore() || engine.canPause(); }

    private void updateGate() {
        hardcoreActive = engine != null && engine.hardcore();
        stateLoadLocked = engine != null && (hardcoreWanted || hardcoreActive);
    }

    /** The service is out of reach, which is no refusal: Hardcore holds the
     * game and tries again, Softcore plays on. Said once per wait. */
    private void waitForNetwork() {
        if (retryAttempt == 0 && hardcoreWanted) {
            final NativeAchievements.Event notice = new NativeAchievements.Event("notice", "Mode Hardcore en attente",
                    "RetroAchievements ne répond pas : la partie reprend dès sa réponse. Désactivez le Hardcore pour "
                            + "jouer hors ligne.", "", "", "");
            ui.execute(() -> listener.onEvent(notice));
        }
        stage = HardcorePolicy.Stage.WAITING_NETWORK;
        retryAtMillis = System.nanoTime() / 1_000_000L + HardcorePolicy.retryDelayMillis(retryAttempt++);
    }

    /** Signs in again, the same game in the same mode; answers due to the
     * session given up are dropped. */
    private void retrySession() {
        if (engine != null) engine.close();
        generation++;
        answers.clear();
        replayStarted = false;
        stage = HardcorePolicy.Stage.SIGNING_IN;
        engine = NativeAchievements.startGame(account.user, account.token, console, retryRom, hardcoreWanted);
        if (engine == null) stage = HardcorePolicy.Stage.IDLE;
        updateGate();
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
        updateGate();
        // Last: signing in again replaces the session used above.
        if (stage == HardcorePolicy.Stage.WAITING_NETWORK && holdFrames()
                && System.nanoTime() / 1_000_000L >= retryAtMillis) retrySession();
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
                stage = HardcorePolicy.Stage.LOADING;
                break;
            case "game": case "online":
                status = engine != null && engine.hardcore() ? "Connecté • Mode Hardcore"
                        : "Connecté • Mode normal (Softcore)";
                if (event.kind.equals("game")) {
                    stage = HardcorePolicy.Stage.TRACKING;
                    retryAttempt = 0;
                }
                break;
            case "login_error": case "game_unknown": case "game_offline": case "game_error": case "offline": {
                AchievementNotices.Notice notice = AchievementNotices.of(event);
                if (notice != null) status = notice.title.isEmpty() ? notice.heading : notice.title;
                // Nothing to evaluate: the game runs without achievements. No answer: wait in Hardcore.
                if (event.kind.equals("game_offline") || event.kind.equals("login_error") && "offline".equals(event.extra))
                    waitForNetwork();
                else if (!event.kind.equals("offline")) stage = HardcorePolicy.Stage.UNTRACKED;
                break;
            }
            // Required by rcheevos before Hardcore resumes: the game starts over.
            case "hardcore_reset":
                resetGame.run();
                break;
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
                scoresAround, scoresLoading, scoresError, 0, current.hardcore());
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
        stage = HardcorePolicy.Stage.IDLE;
        hardcoreWanted = false;
        retryRom = null;
        updateGate();
        account = null;
        journal = null;
        status = AchievementsSnapshot.SIGNED_OUT.status;
        closed.set(true);
        generation++;
        answers.clear();
    }
}
