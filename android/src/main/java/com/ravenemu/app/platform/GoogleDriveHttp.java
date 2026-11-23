package com.ravenemu.app.platform;

import com.ravenemu.app.data.saves.CloudRefused;
import com.ravenemu.app.data.saves.CloudStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The cartridge saves' cloud folder in Google Drive: the visible folder
 * "RavenEmu" at the root of the player's Drive, which RavenEmu creates, over
 * the Drive API v3 (REST over HTTPS). With the drive.file scope RavenEmu sees
 * only the folders and files it created, never the rest of the Drive.
 *
 * Drive has no conditional write as WebDAV's If-Match: a file's ETag here is
 * its id and version, compared just before it is replaced. Two devices
 * writing the list in the same instant may still overwrite each other; the
 * saves themselves are never lost, as each is its own file (CloudSync).
 * Blocking: worker threads only. */
public class GoogleDriveHttp implements CloudStore {
    public static final String FOLDER = "RavenEmu";
    static final String API = "https://www.googleapis.com/drive/v3/";
    static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/";
    static final String FOLDER_TYPE = "application/vnd.google-apps.folder";
    /** The list of a search, and an error's details, stay small. */
    private static final int MAX_ANSWER = 256 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The access tokens of the signed-in Google account. */
    public interface Tokens {
        /** A valid token for the drive.file scope; why not, as a French message. */
        String token() throws IOException;
        /** Drive refused this token: the next token() asks for a new one. */
        void refused(String token);
    }

    /** One HTTP exchange, apart for the tests. */
    public interface Transport {
        Response send(String method, String url, Map<String, String> headers, byte[] body, int maxBytes)
                throws IOException;
    }

    public static final class Response {
        public final int status;
        public final byte[] body;

        public Response(int status, byte[] body) {
            this.status = status;
            this.body = body == null ? new byte[0] : body;
        }
    }

    /** A file of the folder, as a search lists it. */
    private static final class Item {
        final String id, version;
        final long size;

        Item(String id, String version, long size) {
            this.id = id;
            this.version = version;
            this.size = size;
        }

        String etag() { return id + "/" + version; }
    }

    private final Tokens tokens;
    private final Transport transport;
    private final String userAgent;
    /** Found or created once per store, that is once per sync. */
    private String folderId;

    public GoogleDriveHttp(Tokens tokens, String userAgent) { this(tokens, new Http(), userAgent); }

    GoogleDriveHttp(Tokens tokens, Transport transport, String userAgent) {
        this.tokens = tokens;
        this.transport = transport;
        this.userAgent = userAgent;
    }

    /** The account's address, as Drive gives it: shown in the settings. */
    public String email() throws IOException {
        Response answer = call("GET", API + "about?fields=user(emailAddress)", null, null, MAX_ANSWER);
        if (answer.status != 200) throw new IOException(failure(answer));
        try {
            String email = json(answer).getJSONObject("user").getString("emailAddress").trim();
            if (email.isEmpty() || email.length() > 320) throw new IOException("Adresse du compte Google illisible");
            return email;
        } catch (JSONException unreadable) {
            throw new IOException("Réponse de Google Drive illisible");
        }
    }

    @Override public Fetched get(String name, int maxBytes) throws IOException {
        checkName(name);
        String folder = folder(false);
        Item item = folder == null ? null : find(folder, name);
        if (item == null) return new Fetched(404, null, null);
        if (item.size > maxBytes) throw new IOException("Fichier du cloud trop volumineux");
        Response answer = call("GET", API + "files/" + item.id + "?alt=media", null, null, maxBytes);
        if (answer.status == 200) return new Fetched(200, answer.body, item.etag());
        return new Fetched(status(answer), null, null);
    }

    @Override public int put(String name, byte[] body, String ifMatch, boolean create) throws IOException {
        checkName(name);
        String folder = folder(true);
        Item item = find(folder, name);
        if (ifMatch != null && (item == null || !item.etag().equals(ifMatch))) return 412;
        if (ifMatch == null && create && item != null) return 412;
        String type = name.endsWith(".json") ? "application/json; charset=UTF-8" : "application/octet-stream";
        if (item != null) {
            Map<String, String> headers = new LinkedHashMap<>();
            // HttpURLConnection has no PATCH: Google's APIs take it overridden.
            headers.put("X-HTTP-Method-Override", "PATCH");
            headers.put("Content-Type", type);
            Response answer = call("POST", UPLOAD + "files/" + item.id + "?uploadType=media&fields=id",
                    headers, body, MAX_ANSWER);
            return answer.status == 200 ? 200 : status(answer);
        }
        String boundary = "ravenemu-" + Long.toHexString(RANDOM.nextLong()) + Long.toHexString(RANDOM.nextLong());
        byte[] metadata;
        try {
            metadata = new JSONObject().put("name", name).put("parents", new JSONArray().put(folder)).toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        ByteArrayOutputStream multipart = new ByteArrayOutputStream(body.length + 512);
        multipart.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        multipart.write(metadata);
        multipart.write(("\r\n--" + boundary + "\r\nContent-Type: " + type + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        multipart.write(body);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "multipart/related; boundary=" + boundary);
        Response answer = call("POST", UPLOAD + "files?uploadType=multipart&fields=id", headers,
                multipart.toByteArray(), MAX_ANSWER);
        return answer.status == 200 ? 201 : status(answer);
    }

    @Override public int delete(String name) throws IOException {
        checkName(name);
        String folder = folder(false);
        Item item = folder == null ? null : find(folder, name);
        if (item == null) return 404;
        Response answer = call("DELETE", API + "files/" + item.id, null, null, MAX_ANSWER);
        return answer.status == 204 || answer.status == 200 ? 204 : status(answer);
    }

    /** The folder RavenEmu created at the root of the Drive, the oldest if two
     * devices created one at the same time; null if absent and !create. */
    private String folder(boolean create) throws IOException {
        if (folderId != null) return folderId;
        JSONArray found = search("name = '" + FOLDER + "' and mimeType = '" + FOLDER_TYPE
                + "' and 'root' in parents and trashed = false", "id");
        try {
            if (found.length() > 0) return folderId = checkId(found.getJSONObject(0).getString("id"));
            if (!create) return null;
            byte[] metadata = new JSONObject().put("name", FOLDER).put("mimeType", FOLDER_TYPE)
                    .put("parents", new JSONArray().put("root")).toString().getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json; charset=UTF-8");
            Response answer = call("POST", API + "files?fields=id", headers, metadata, MAX_ANSWER);
            if (answer.status != 200) throw new IOException(failure(answer));
            return folderId = checkId(json(answer).getString("id"));
        } catch (JSONException unreadable) {
            throw new IOException("Réponse de Google Drive illisible");
        }
    }

    /** The oldest file of that name in the folder, or null. */
    private Item find(String folder, String name) throws IOException {
        JSONArray found = search("name = '" + name + "' and '" + folder + "' in parents and trashed = false",
                "id,version,size");
        if (found.length() == 0) return null;
        try {
            JSONObject file = found.getJSONObject(0);
            return new Item(checkId(file.getString("id")), file.optString("version", "0"),
                    Long.parseLong(file.optString("size", "0")));
        } catch (JSONException | NumberFormatException unreadable) {
            throw new IOException("Réponse de Google Drive illisible");
        }
    }

    private JSONArray search(String query, String fields) throws IOException {
        String url = API + "files?spaces=drive&pageSize=10&orderBy=createdTime&q="
                + URLEncoder.encode(query, "UTF-8") + "&fields=" + URLEncoder.encode("files(" + fields + ")", "UTF-8");
        Response answer = call("GET", url, null, null, MAX_ANSWER);
        if (answer.status != 200) throw new IOException(failure(answer));
        try {
            return json(answer).optJSONArray("files") == null ? new JSONArray() : json(answer).getJSONArray("files");
        } catch (JSONException unreadable) {
            throw new IOException("Réponse de Google Drive illisible");
        }
    }

    /** With the account's token; a refused token is replaced once. */
    private Response call(String method, String url, Map<String, String> extra, byte[] body, int maxBytes)
            throws IOException {
        for (int attempt = 0; ; attempt++) {
            String token = tokens.token();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + token);
            headers.put("User-Agent", userAgent);
            if (extra != null) headers.putAll(extra);
            Response answer = transport.send(method, url, headers, body, maxBytes);
            if (answer.status != 401) return answer;
            tokens.refused(token);
            if (attempt > 0) throw new CloudRefused(
                    "Google Drive refuse la connexion : reconnectez-vous dans Paramètres → Sauvegardes cloud");
        }
    }

    /** Drive's answer as the HTTP status CloudSync reads. */
    private static int status(Response answer) throws IOException {
        String reason = reason(answer);
        if ("storageQuotaExceeded".equals(reason)) return 507;
        if (answer.status == 429 || "userRateLimitExceeded".equals(reason) || "rateLimitExceeded".equals(reason))
            return 429;
        if ("accessNotConfigured".equals(reason)) throw new IOException(failure(answer));
        return answer.status;
    }

    private static String failure(Response answer) {
        String reason = reason(answer);
        if ("accessNotConfigured".equals(reason))
            return "Google Drive n'est pas encore ouvert à RavenEmu : l'API Drive est désactivée pour son projet";
        if ("storageQuotaExceeded".equals(reason)) return "Espace du Google Drive plein";
        if (answer.status == 429 || "userRateLimitExceeded".equals(reason) || "rateLimitExceeded".equals(reason))
            return "Google Drive limite les requêtes : réessayez dans un moment";
        if (answer.status == 403) return "Google Drive refuse l'accès au dossier RavenEmu";
        return "Google Drive a répondu " + answer.status;
    }

    /** The first reason of a Drive error, or "". */
    private static String reason(Response answer) {
        try {
            JSONArray errors = json(answer).getJSONObject("error").optJSONArray("errors");
            return errors == null || errors.length() == 0 ? "" : errors.getJSONObject(0).optString("reason", "");
        } catch (JSONException notJson) {
            return "";
        }
    }

    private static JSONObject json(Response answer) throws JSONException {
        return new JSONObject(new String(answer.body, StandardCharsets.UTF_8));
    }

    /** Only names RavenEmu writes, as WebDavHttp: they need no quoting. */
    private static void checkName(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9.-]{0,199}"))
            throw new IllegalArgumentException("Invalid cloud file");
    }

    /** A Drive id goes into URLs and searches as it is. */
    private static String checkId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,200}")) throw new IOException("Réponse de Google Drive illisible");
        return id;
    }

    /** HTTPS to Google only, without redirects or caches. */
    static final class Http implements Transport {
        private static final int CONNECT_MILLIS = 15_000, READ_MILLIS = 30_000;

        @Override public Response send(String method, String url, Map<String, String> headers, byte[] body,
                                       int maxBytes) throws IOException {
            if (!url.startsWith(API) && !url.startsWith(UPLOAD)) throw new IllegalArgumentException("Not Google Drive");
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(CONNECT_MILLIS);
                connection.setReadTimeout(READ_MILLIS);
                connection.setRequestMethod(method);
                for (Map.Entry<String, String> header : headers.entrySet())
                    connection.setRequestProperty(header.getKey(), header.getValue());
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) return new Response(status, null);
                try (InputStream input = stream) { return new Response(status, read(input, maxBytes)); }
            } finally {
                connection.disconnect();
            }
        }

        private static byte[] read(InputStream stream, int maxBytes) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                if (output.size() > maxBytes - count) throw new IOException("Fichier du cloud trop volumineux");
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }
}
