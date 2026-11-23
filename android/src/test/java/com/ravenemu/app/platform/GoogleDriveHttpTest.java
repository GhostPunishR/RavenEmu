package com.ravenemu.app.platform;

import static org.junit.Assert.*;

import com.ravenemu.app.data.saves.CloudStore;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The Drive API requests of the cloud saves, against a Drive in memory that
 * answers the searches, uploads and downloads RavenEmu sends. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class GoogleDriveHttpTest {
    /** A Drive's files, with Google's versions, ids and error answers. */
    private static final class FakeDrive implements GoogleDriveHttp.Transport {
        static final class File {
            final String id, name, parent, mimeType;
            byte[] content;
            long version = 1;

            File(String id, String name, String parent, String mimeType, byte[] content) {
                this.id = id;
                this.name = name;
                this.parent = parent;
                this.mimeType = mimeType;
                this.content = content;
            }
        }

        final Map<String, File> files = new LinkedHashMap<>();
        final List<String> log = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        int next = 1;
        /** Tokens Drive accepts. */
        String validToken = "token-1";
        /** The answer to the next request whose log line starts with overrideFor, then cleared. */
        GoogleDriveHttp.Response override;
        String overrideFor = "";

        @Override public GoogleDriveHttp.Response send(String method, String url, Map<String, String> sent,
                                                       byte[] body, int maxBytes) {
            String line = method + " " + url.replace(GoogleDriveHttp.API, "API/").replace(GoogleDriveHttp.UPLOAD, "UPLOAD/");
            log.add(line);
            headers.add(new LinkedHashMap<>(sent));
            if (!("Bearer " + validToken).equals(sent.get("Authorization"))) return error(401, "authError");
            if (override != null && line.startsWith(overrideFor)) {
                GoogleDriveHttp.Response answer = override;
                override = null;
                return answer;
            }
            try {
                return answer(method, url, sent, body);
            } catch (Exception broken) {
                throw new AssertionError(broken);
            }
        }

        private GoogleDriveHttp.Response answer(String method, String url, Map<String, String> sent, byte[] body)
                throws Exception {
            String path = url.startsWith(GoogleDriveHttp.UPLOAD) ? "UPLOAD/" + url.substring(GoogleDriveHttp.UPLOAD.length())
                    : url.substring(GoogleDriveHttp.API.length());
            if (path.startsWith("about")) return json(new JSONObject().put("user",
                    new JSONObject().put("emailAddress", "player@example.com")));
            if (method.equals("GET") && path.startsWith("files?")) return search(url);
            if (method.equals("POST") && path.startsWith("files?")) {
                JSONObject metadata = new JSONObject(new String(body, StandardCharsets.UTF_8));
                File folder = add(metadata.getString("name"), metadata.getJSONArray("parents").getString(0),
                        metadata.getString("mimeType"), null);
                return json(new JSONObject().put("id", folder.id));
            }
            if (method.equals("POST") && path.startsWith("UPLOAD/files?uploadType=multipart")) {
                String text = new String(body, StandardCharsets.ISO_8859_1);
                String boundary = sent.get("Content-Type").replace("multipart/related; boundary=", "");
                String[] parts = text.split("--" + Pattern.quote(boundary));
                JSONObject metadata = new JSONObject(parts[1].substring(parts[1].indexOf("\r\n\r\n") + 4).trim());
                String media = parts[2].substring(parts[2].indexOf("\r\n\r\n") + 4);
                media = media.substring(0, media.length() - 2);
                File file = add(metadata.getString("name"), metadata.getJSONArray("parents").getString(0),
                        "application/octet-stream", media.getBytes(StandardCharsets.ISO_8859_1));
                return json(new JSONObject().put("id", file.id));
            }
            Matcher media = Pattern.compile("UPLOAD/files/([^?]+)\\?uploadType=media.*").matcher(path);
            if (method.equals("POST") && media.matches()) {
                assertEquals("PATCH", sent.get("X-HTTP-Method-Override"));
                File file = files.get(media.group(1));
                if (file == null) return error(404, "notFound");
                file.content = body;
                file.version++;
                return json(new JSONObject().put("id", file.id));
            }
            Matcher one = Pattern.compile("files/([^?]+)(\\?alt=media)?").matcher(path);
            if (one.matches()) {
                File file = files.get(one.group(1));
                if (file == null) return error(404, "notFound");
                if (method.equals("DELETE")) {
                    files.remove(file.id);
                    return new GoogleDriveHttp.Response(204, null);
                }
                return new GoogleDriveHttp.Response(200, file.content);
            }
            throw new AssertionError("Unexpected request " + method + " " + url);
        }

        /** The searches RavenEmu makes: a name, a parent, a type, not trashed. */
        private GoogleDriveHttp.Response search(String url) throws Exception {
            String query = URLDecoder.decode(url.replaceAll(".*[?&]q=([^&]*).*", "$1"), "UTF-8");
            Matcher name = Pattern.compile("name = '([^']*)'").matcher(query);
            Matcher parent = Pattern.compile("'([^']*)' in parents").matcher(query);
            assertTrue(query, name.find() && parent.find());
            assertTrue(query, query.contains("trashed = false"));
            boolean folders = query.contains("mimeType = '" + GoogleDriveHttp.FOLDER_TYPE + "'");
            JSONArray found = new JSONArray();
            for (File file : files.values())
                if (file.name.equals(name.group(1)) && file.parent.equals(parent.group(1))
                        && (!folders || file.mimeType.equals(GoogleDriveHttp.FOLDER_TYPE)))
                    found.put(new JSONObject().put("id", file.id).put("version", String.valueOf(file.version))
                            .put("size", String.valueOf(file.content == null ? 0 : file.content.length)));
            return json(new JSONObject().put("files", found));
        }

        File add(String name, String parent, String mimeType, byte[] content) {
            File file = new File("id" + next++, name, parent, mimeType, content);
            files.put(file.id, file);
            return file;
        }

        File named(String name) {
            for (File file : files.values()) if (file.name.equals(name)) return file;
            return null;
        }

        static GoogleDriveHttp.Response json(JSONObject json) {
            return new GoogleDriveHttp.Response(200, json.toString().getBytes(StandardCharsets.UTF_8));
        }

        static GoogleDriveHttp.Response error(int status, String reason) {
            try {
                JSONObject error = new JSONObject().put("code", status).put("errors",
                        new JSONArray().put(new JSONObject().put("reason", reason)));
                return new GoogleDriveHttp.Response(status,
                        new JSONObject().put("error", error).toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    /** Hands out "token-1", then "token-2" once the first was refused. */
    private static final class Tokens implements GoogleDriveHttp.Tokens {
        int issued = 1;
        final List<String> refused = new ArrayList<>();

        @Override public String token() { return "token-" + issued; }

        @Override public void refused(String token) {
            refused.add(token);
            issued++;
        }
    }

    private final FakeDrive drive = new FakeDrive();
    private final Tokens tokens = new Tokens();
    private final GoogleDriveHttp store = new GoogleDriveHttp(tokens, drive, "RavenEmu/test");

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test public void readingAnEmptyDriveCreatesNothing() throws IOException {
        CloudStore.Fetched fetched = store.get("ravenemu-saves.json", 1024);
        assertEquals(404, fetched.status);
        assertEquals(404, store.delete("ravenemu-saves.json"));
        assertTrue("No folder is created to read", drive.files.isEmpty());
    }

    @Test public void theFirstWriteCreatesTheRavenEmuFolderAtTheRootOnce() throws IOException {
        assertEquals(201, store.put("ravenemu-saves.json", bytes("{}"), null, true));
        assertEquals(201, store.put("ravenemu-abc-0123.srm", bytes("save"), null, false));
        FakeDrive.File folder = drive.named(GoogleDriveHttp.FOLDER);
        assertNotNull(folder);
        assertEquals("root", folder.parent);
        assertEquals(GoogleDriveHttp.FOLDER_TYPE, folder.mimeType);
        assertEquals(folder.id, drive.named("ravenemu-saves.json").parent);
        assertEquals(folder.id, drive.named("ravenemu-abc-0123.srm").parent);
        assertArrayEquals(bytes("save"), drive.named("ravenemu-abc-0123.srm").content);
        int folders = 0;
        for (FakeDrive.File file : drive.files.values()) if (file.mimeType.equals(GoogleDriveHttp.FOLDER_TYPE)) folders++;
        assertEquals(1, folders);
        // Another sync, another store: the folder is found, not created again.
        GoogleDriveHttp later = new GoogleDriveHttp(tokens, drive, "RavenEmu/test");
        assertEquals(200, later.get("ravenemu-abc-0123.srm", 1024).status);
        assertEquals(3, drive.files.size());
    }

    @Test public void writesAreConditionalAsWebDavOnes() throws IOException {
        assertEquals(201, store.put("ravenemu-saves.json", bytes("{\"v\":1}"), null, true));
        assertEquals("The list exists: creating it again is refused",
                412, store.put("ravenemu-saves.json", bytes("{}"), null, true));
        CloudStore.Fetched first = store.get("ravenemu-saves.json", 1024);
        assertEquals(200, first.status);
        assertArrayEquals(bytes("{\"v\":1}"), first.body);
        assertNotNull(first.etag);
        // Another device replaces the list meanwhile: the old ETag is refused.
        FakeDrive.File list = drive.named("ravenemu-saves.json");
        list.content = bytes("{\"v\":2}");
        list.version++;
        assertEquals(412, store.put("ravenemu-saves.json", bytes("{\"v\":3}"), first.etag, false));
        assertArrayEquals(bytes("{\"v\":2}"), list.content);
        CloudStore.Fetched second = store.get("ravenemu-saves.json", 1024);
        assertEquals(200, store.put("ravenemu-saves.json", bytes("{\"v\":3}"), second.etag, false));
        assertArrayEquals(bytes("{\"v\":3}"), list.content);
        assertEquals("A file that disappeared does not match either",
                412, store.put("ravenemu-other.json", bytes("{}"), second.etag, false));
    }

    @Test public void deletingRemovesOnlyThatFile() throws IOException {
        store.put("ravenemu-a-1.srm", bytes("a"), null, false);
        store.put("ravenemu-b-2.srm", bytes("b"), null, false);
        assertEquals(204, store.delete("ravenemu-a-1.srm"));
        assertNull(drive.named("ravenemu-a-1.srm"));
        assertNotNull(drive.named("ravenemu-b-2.srm"));
        assertEquals(404, store.get("ravenemu-a-1.srm", 1024).status);
    }

    @Test public void aFileLargerThanAllowedIsNeverDownloaded() throws IOException {
        store.put("ravenemu-big-1.srm", new byte[2048], null, false);
        int before = drive.log.size();
        try {
            store.get("ravenemu-big-1.srm", 1024);
            fail("Too large");
        } catch (IOException expected) {
            assertEquals("Fichier du cloud trop volumineux", expected.getMessage());
        }
        for (String request : drive.log.subList(before, drive.log.size()))
            assertFalse(request, request.contains("alt=media"));
    }

    @Test public void aRefusedTokenIsReplacedOnceThenTheAccountMustReconnect() throws IOException {
        drive.validToken = "token-2";
        assertEquals(201, store.put("ravenemu-saves.json", bytes("{}"), null, true));
        assertEquals(List.of("token-1"), tokens.refused);
        drive.validToken = "revoked";
        try {
            store.get("ravenemu-saves.json", 1024);
            fail("Refused twice");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("reconnectez-vous"));
        }
    }

    @Test public void driveErrorsBecomeTheStatusesTheSyncExplains() throws IOException {
        store.put("ravenemu-saves.json", bytes("{}"), null, true);
        drive.overrideFor = "POST UPLOAD/files?uploadType=multipart";
        drive.override = FakeDrive.error(403, "storageQuotaExceeded");
        assertEquals(507, store.put("ravenemu-a-1.srm", bytes("a"), null, false));
        drive.overrideFor = "DELETE";
        drive.override = FakeDrive.error(403, "userRateLimitExceeded");
        assertEquals(429, store.delete("ravenemu-saves.json"));
        drive.overrideFor = "GET API/files?";
        drive.override = FakeDrive.error(429, "rateLimitExceeded");
        try {
            store.get("ravenemu-saves.json", 1024);
            fail("A refused search stops the sync with Drive's reason");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("limite les requêtes"));
        }
        drive.overrideFor = "POST UPLOAD/files?uploadType=multipart";
        drive.override = FakeDrive.error(403, "accessNotConfigured");
        try {
            store.put("ravenemu-a-1.srm", bytes("a"), null, false);
            fail("Drive API disabled");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("API Drive"));
        }
    }

    @Test public void theAccountsAddressComesFromDrive() throws IOException {
        assertEquals("player@example.com", store.email());
    }

    @Test public void everyRequestCarriesTheTokenAndReachesGoogleOnly() throws IOException {
        store.put("ravenemu-saves.json", bytes("{}"), null, true);
        store.put("ravenemu-saves.json", bytes("{\"v\":1}"), null, false);
        store.get("ravenemu-saves.json", 1024);
        store.delete("ravenemu-saves.json");
        for (int i = 0; i < drive.log.size(); i++) {
            assertTrue(drive.log.get(i), drive.log.get(i).matches("(GET|POST|DELETE) (API|UPLOAD)/.*"));
            assertEquals("Bearer token-1", drive.headers.get(i).get("Authorization"));
            assertEquals("RavenEmu/test", drive.headers.get(i).get("User-Agent"));
        }
        try {
            new GoogleDriveHttp.Http().send("GET", "https://example.org/", new LinkedHashMap<>(), null, 10);
            fail("Not Google");
        } catch (IllegalArgumentException expected) {
            // The token never leaves for another host.
        }
    }

    @Test public void onlyRavenEmusOwnFileNamesAreAccepted() throws IOException {
        for (String name : new String[]{"../x", "a' or name != 'b", "UPPER.srm", ""}) {
            try {
                store.get(name, 10);
                fail(name);
            } catch (IllegalArgumentException expected) {
                // Never put in a search.
            }
        }
        assertTrue(drive.log.isEmpty());
    }
}
