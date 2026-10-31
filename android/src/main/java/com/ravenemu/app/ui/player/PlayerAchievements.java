package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.platform.AchievementsHttp;
import com.ravenemu.app.settings.AchievementAccount;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** RetroAchievements during a game: the C frontend's instance on the GL
 * thread, which the bridge gives each frame shown; its requests sent by two
 * network threads, as in the beta; their answers fed back at the next frame;
 * what happens handed to the UI thread. */
final class PlayerAchievements {
    interface Listener { void onEvent(NativeAchievements.Event event); }

    /** Shared by the players: a request still out when one closes ends there. */
    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(2, work -> {
        Thread thread = new Thread(work, "RavenEmu-RetroAchievements");
        thread.setDaemon(true);
        return thread;
    });

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
    private int generation;

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
    void start(AchievementsHttp http, AchievementAccount account, String system, byte[] rom) {
        stop();
        final int console = console(system);
        if (http == null || account == null || console == 0 || rom == null) return;
        this.http = http;
        engine = NativeAchievements.startGame(account.user, account.token, console, rom);
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
        for (NativeAchievements.Request request; (request = current.nextRequest()) != null; ) {
            final NativeAchievements.Request sent = request;
            final int sentGeneration = generation;
            NETWORK.execute(() -> answers.add(new Answer(sentGeneration, sent.id,
                    network.send(sent.url, sent.postData, sent.contentType))));
        }
        for (NativeAchievements.Event event; (event = current.nextEvent()) != null; ) {
            final NativeAchievements.Event shown = event;
            ui.execute(() -> listener.onEvent(shown));
        }
    }

    /** GL thread, or the UI thread once the GL thread paused: requests still
     * out are settled locally; their late answers are dropped. */
    void stop() {
        if (engine != null) engine.close();
        engine = null;
        generation++;
        answers.clear();
    }
}
