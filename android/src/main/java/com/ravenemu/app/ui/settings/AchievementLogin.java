package com.ravenemu.app.ui.settings;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.platform.AchievementsHttp;
import com.ravenemu.app.settings.AchievementAccount;

/** Settings → RetroAchievements signs in through the C frontend's module
 * too, on an instance without a game: rcheevos builds the login request and
 * reads the answer; Java only carries it over HTTPS. The password goes into
 * that one request and is kept nowhere. Worker thread: it blocks. */
final class AchievementLogin {
    static final class Result {
        final AchievementAccount account;
        final String error;
        Result(AchievementAccount account, String error) { this.account = account; this.error = error; }
    }

    /** login2 is one request; more would be the SDK retrying on its own. */
    private static final int MAX_REQUESTS = 4;

    private AchievementLogin() {}

    /** With a password: the account to keep. With the stored token (password
     * null): the same account, its name and points refreshed. */
    static Result run(AchievementsHttp http, String user, String password, String token) {
        NativeAchievements engine = NativeAchievements.account();
        if (engine == null) return new Result(null, "Mémoire insuffisante pour RetroAchievements.");
        try {
            if (password != null) engine.loginPassword(user, password);
            else engine.loginToken(user, token);
            for (int sent = 0; sent < MAX_REQUESTS; sent++) {
                Result result = outcome(engine);
                if (result != null) return result;
                NativeAchievements.Request request = engine.nextRequest();
                if (request == null) break;
                AchievementsHttp.Answer answer = http.send(request.url, request.postData, request.contentType);
                engine.respond(request.id, answer.body, answer.status);
            }
            Result result = outcome(engine);
            return result != null ? result : new Result(null, "Réponse inattendue de RetroAchievements.");
        } finally {
            engine.close();
        }
    }

    private static Result outcome(NativeAchievements engine) {
        for (NativeAchievements.Event event; (event = engine.nextEvent()) != null; ) {
            if ("login".equals(event.kind)) {
                NativeAchievements.Summary summary = engine.summary();
                // user name, display name, token, avatar.
                return new Result(new AchievementAccount(event.title, event.extra, event.detail, event.image,
                        summary == null ? 0 : summary.softcorePoints,
                        summary == null ? 0 : summary.hardcorePoints), null);
            }
            if ("login_error".equals(event.kind)) {
                if ("offline".equals(event.extra))
                    return new Result(null, "RetroAchievements est injoignable. Vérifiez la connexion puis réessayez.");
                return new Result(null, event.title.isEmpty() ? "Connexion refusée par RetroAchievements." : event.title);
            }
        }
        return null;
    }
}
