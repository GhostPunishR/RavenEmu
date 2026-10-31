package com.ravenemu.app.bridge;

import java.nio.charset.StandardCharsets;

/** achievements_bridge.c on the host JVM, with the app's NativeAchievements:
 * handles, UTF-8 both ways, the player's instance on RavenCore's Game Boy
 * core. Canned answers in the pinned rcheevos' formats; no network. */
public final class AchievementsBridgeCheck {
    static native boolean open(String core, byte[] rom);
    static native boolean frame();
    static native void close();

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    static byte[] counterRom() {
        byte[] rom = new byte[32 * 1024];
        byte[] entry = {0x00, (byte)0xc3, 0x50, 0x01};
        System.arraycopy(entry, 0, rom, 0x100, 4);
        byte[] title = "RAVENCOUNT".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, 0x134, title.length);
        int[] code = {0xf0, 0x44, 0xfe, 0x90, 0x20, 0xfa, 0x21, 0x01, 0xc0, 0x34,
                      0xf0, 0x44, 0xfe, 0x90, 0x28, 0xfa, 0x18, 0xee};
        for (int i = 0; i < code.length; i++) rom[0x150 + i] = (byte)code[i];
        int checksum = 0;
        for (int i = 0x134; i <= 0x14c; i++) checksum = (checksum - rom[i] - 1) & 0xff;
        rom[0x14d] = (byte)checksum;
        return rom;
    }

    static final String LOGIN_OK = "{\"Success\":true,\"User\":\"Joueur Été ★\","
        + "\"Token\":\"SyntheticToken\",\"Score\":12,\"SoftcoreScore\":34,\"Messages\":0}";
    static final String SETS = "{\"Success\":true,\"GameId\":1234,\"Title\":\"Jeu synthétique ★\",\"ConsoleId\":4,"
        + "\"ImageIconUrl\":\"https://media.retroachievements.org/Images/000001.png\",\"RichPresenceGameId\":1234,"
        + "\"RichPresencePatch\":\"\",\"Sets\":[{\"AchievementSetId\":1111,\"GameId\":1234,\"Title\":null,"
        + "\"Type\":\"core\",\"ImageIconUrl\":\"https://media.retroachievements.org/Images/000001.png\","
        + "\"Achievements\":[{\"ID\":5501,\"Title\":\"Soixante images 😀\",\"Description\":\"Compter à soixante\","
        + "\"Flags\":3,\"Points\":5,\"MemAddr\":\"0xHc001=60\",\"Author\":\"Test\",\"BadgeName\":\"00234\","
        + "\"Created\":1367266583,\"Modified\":1376929305}],\"Leaderboards\":[{\"ID\":4401,\"Title\":\"Course ★\","
        + "\"Description\":\"Le plus haut\",\"Mem\":\"STA:0=1::CAN:0=1::SUB:0=1::VAL:0xHc001\",\"Format\":\"SCORE\","
        + "\"LowerIsBetter\":false,\"Hidden\":false}]}]}";
    static final String SCORES = "{\"Success\":true,\"LeaderboardData\":{\"LBID\":4401,\"LBFormat\":\"SCORE\","
        + "\"LowerIsBetter\":0,\"LBTitle\":\"Course ★\",\"LBDesc\":\"Le plus haut\",\"LBMem\":\"STA:0=1\","
        + "\"GameID\":1234,\"LBAuthor\":\"Test\",\"LBCreated\":\"2013-10-20 22:12:21\","
        + "\"LBUpdated\":\"2013-10-20 22:12:21\",\"Entries\":[{\"User\":\"Corbeau ★\",\"Rank\":1,\"Index\":1,"
        + "\"Score\":120,\"DateSubmitted\":1615654895},{\"User\":\"Joueur Été ★\",\"Rank\":2,\"Index\":2,"
        + "\"Score\":60,\"DateSubmitted\":1615634566}],\"TotalEntries\":2}}";

    static NativeAchievements.Event take(NativeAchievements a, String kind) {
        for (NativeAchievements.Event e; (e = a.nextEvent()) != null; ) if (kind.equals(e.kind)) return e;
        return null;
    }

    public static void main(String[] args) {
        System.loadLibrary("raven_android");
        check(NativeAchievements.sdkVersion().startsWith("rcheevos/"), "sdk " + NativeAchievements.sdkVersion());
        check(NativeAchievements.engineVersion().matches("\\d+\\.\\d+\\.\\d+"), "engine");

        // The account page's instance: a non-ASCII password reaches rcheevos as real UTF-8.
        NativeAchievements account = NativeAchievements.account();
        check(account != null, "account");
        account.loginPassword("Joueur", "pässwörd😀");
        NativeAchievements.Request login = account.nextRequest();
        check(login != null && login.url.endsWith("/dorequest.php"), "login request");
        check(login.postData.contains("p=p%c3%a4ssw%c3%b6rd%f0%9f%98%80") || login.postData.contains("p=p%C3%A4ssw%C3%B6rd%F0%9F%98%80"),
              "UTF-8 password: " + login.postData);
        account.respond(login.id, LOGIN_OK.getBytes(StandardCharsets.UTF_8), 200);
        NativeAchievements.Event in = take(account, "login");
        check(in != null && in.title.equals("Joueur Été ★") && in.detail.equals("Joueur Été ★")
              && in.extra.equals("SyntheticToken"), "login event " + (in == null ? null : in.detail));
        NativeAchievements.Summary summary = account.summary();
        check(summary.hardcorePoints == 12 && summary.softcorePoints == 34, "points");
        // No answer at all: offline.
        account.loginPassword("Joueur", "x");
        NativeAchievements.Request again = account.nextRequest();
        account.respond(again.id, null, NativeAchievements.NO_ANSWER);
        NativeAchievements.Event offline = take(account, "login_error");
        check(offline != null && offline.extra.equals("offline"), "offline");
        account.close();
        account.close();  // a destroyed handle finds nothing
        check(account.nextRequest() == null && account.nextEvent() == null && account.summary() == null, "closed");

        // Without a game, the player's instance cannot start.
        check(NativeAchievements.startGame("SyntheticUser", "SyntheticToken", 4, counterRom(), false) == null, "no game");
        byte[] rom = counterRom();
        check(open(args[0], rom), "core");
        check(NativeAchievements.startGame("u", "t", 99, rom, false) == null, "bad console");
        // Started in Hardcore, the mode holds from the login on; then back to Softcore.
        NativeAchievements strict = NativeAchievements.startGame("SyntheticUser", "SyntheticToken", 4, rom, true);
        check(strict != null && strict.hardcore(), "hardcore start");
        strict.setHardcore(false);
        check(!strict.hardcore() && strict.canPause(), "softcore again");
        NativeAchievements player = NativeAchievements.startGame("SyntheticUser", "SyntheticToken", 4, rom, false);
        check(player != null, "player");
        NativeAchievements.Request first = player.nextRequest();
        check(first.postData.contains("r=login2") && first.postData.contains("t=SyntheticToken"), "token login " + first.postData);
        player.respond(first.id, LOGIN_OK.getBytes(StandardCharsets.UTF_8), 200);
        int frames = 0;
        NativeAchievements.Event game = null, award = null;
        NativeAchievements.Request awardRequest = null;
        for (; frames < 600 && award == null; frames++) {
            for (NativeAchievements.Request r; (r = player.nextRequest()) != null; ) {
                if (r.postData.startsWith("r=awardachievement")) { awardRequest = r; continue; }
                String body = r.postData.startsWith("r=achievementsets") ? SETS : "{\"Success\":true}";
                player.respond(r.id, body.getBytes(StandardCharsets.UTF_8), 200);
            }
            for (NativeAchievements.Event e; (e = player.nextEvent()) != null; ) {
                if (e.kind.equals("game")) game = e;
                if (e.kind.equals("achievement")) award = e;
            }
            check(frame(), "frame");
        }
        check(game != null && game.title.equals("Jeu synthétique ★") && game.detail.equals("1"), "game " + (game == null ? null : game.title));
        // The lists, as the game menu reads them once the award came: UTF-8
        // intact, numbers in place, the badge in colour.
        NativeAchievements.Entry[] listed = player.entries();
        check(listed.length == 1 && listed[0].id == 5501 && listed[0].title.equals("Soixante images 😀")
              && listed[0].description.equals("Compter à soixante") && listed[0].points == 5 && listed[0].unlocked()
              && listed[0].unlockedAt > 0 && listed[0].badge.contains("00234") && !listed[0].badge.contains("_lock"),
              "entries");
        NativeAchievements.Board[] boards = player.boards();
        check(boards.length == 1 && boards[0].id == 4401 && boards[0].title.equals("Course ★") && !boards[0].lowerIsBetter,
              "boards");
        player.fetchScores(4401, false);
        NativeAchievements.Request lbinfo = player.nextRequest();
        check(lbinfo != null && lbinfo.postData.contains("r=lbinfo"), "scores request");
        player.respond(lbinfo.id, SCORES.getBytes(StandardCharsets.UTF_8), 200);
        NativeAchievements.Event came = take(player, "scores");
        check(came != null && came.id.equals("4401") && came.detail.equals("2") && came.extra.equals("top"), "scores event");
        NativeAchievements.Scores board = player.scores();
        check(board.board == 4401 && board.total == 2 && !board.around && board.scores.length == 2
              && board.scores[0].user.equals("Corbeau ★") && board.scores[0].rank == 1 && !board.scores[0].player
              && board.scores[1].player && board.scores[1].submitted == 1615634566L, "scores");
        check(player.richPresence().length() < 256, "rich presence");
        check(award != null && award.title.equals("Soixante images 😀") && award.extra.equals("5") && award.id.equals("5501"),
              "award " + (award == null ? null : award.title));
        for (NativeAchievements.Request r; awardRequest == null && (r = player.nextRequest()) != null; )
            if (r.postData.startsWith("r=awardachievement")) awardRequest = r;
        check(awardRequest != null && awardRequest.postData.contains("a=5501") && awardRequest.postData.contains("h=0"),
              "award request");
        // A busy service (503): rcheevos asks again at once, with nothing to show.
        player.respond(awardRequest.id, "{\"Success\":false,\"Error\":\"Busy\"}".getBytes(StandardCharsets.UTF_8), 503);
        NativeAchievements.Request retry = player.nextRequest();
        check(retry != null && retry.postData.startsWith("r=awardachievement"), "retry after 503");
        check(take(player, "server_error") == null, "a 503 is no error to show");
        player.respond(retry.id, ("{\"Success\":true,\"Score\":12,\"SoftcoreScore\":39,\"AchievementID\":5501,"
                + "\"AchievementsRemaining\":0}").getBytes(StandardCharsets.UTF_8), 200);
        check(player.nextRequest() == null, "awarded once");
        NativeAchievements.Summary progress = player.summary();
        check(progress.gameId == 1234 && progress.unlocked == 1 && progress.total == 1, "summary");
        // Another account instance cannot close the player's; the game's close does.
        NativeAchievements other = NativeAchievements.account();
        other.close();
        check(player.summary() != null, "player kept");
        // The next game's instance replaces it; the old handle cannot close the new one.
        NativeAchievements next = NativeAchievements.startGame("SyntheticUser", "SyntheticToken", 4, rom, false);
        check(next != null && player.summary() == null && player.nextEvent() == null, "replaced by the next game");
        check(player.entries().length == 0 && player.boards().length == 0 && player.scores().scores.length == 0
              && player.richPresence().isEmpty(), "an old handle lists nothing");
        player.close();
        check(next.summary() != null, "an old handle leaves the new game's instance");
        close();
        check(next.summary() == null && next.nextRequest() == null, "gone with its game");
        next.close();
        System.out.println("RetroAchievements JNI: all checks passed, award at frame " + frames);
    }
}
