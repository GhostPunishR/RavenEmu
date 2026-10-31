package com.ravenemu.app.platform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** RetroAchievements' service, scripted: no test reaches the network. Each
 * request sent is logged; the next scripted answer, or none, comes back. */
@Implements(value = AchievementsHttp.class, isInAndroidSdk = false)
public class FakeHttp {
    /** "url post" of each request, in the order sent. */
    public static final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    private static final ArrayDeque<AchievementsHttp.Answer> answers = new ArrayDeque<>();
    /** Called with url and post as each request leaves, on its network thread. */
    public static volatile java.util.function.BiConsumer<String, String> onSend;

    public static synchronized void reset() {
        sent.clear();
        answers.clear();
        onSend = null;
    }

    public static synchronized void answer(int status, String body) {
        answers.add(new AchievementsHttp.Answer(body.getBytes(StandardCharsets.UTF_8), status));
    }

    @Implementation protected AchievementsHttp.Answer send(String url, String postData, String contentType) {
        java.util.function.BiConsumer<String, String> hook = onSend;
        if (hook != null) hook.accept(url, postData);
        sent.add(url + " " + postData);
        synchronized (FakeHttp.class) {
            AchievementsHttp.Answer next = answers.poll();
            return next != null ? next : new AchievementsHttp.Answer(null, AchievementsHttp.NO_ANSWER);
        }
    }

    @Implementation protected byte[] image(String url) { return null; }
}
