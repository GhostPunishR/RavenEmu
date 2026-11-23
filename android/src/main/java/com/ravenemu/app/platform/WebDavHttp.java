package com.ravenemu.app.platform;

import com.ravenemu.app.data.saves.CloudRefused;
import com.ravenemu.app.data.saves.CloudStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** A WebDAV folder over HTTPS, as RetroArch's cloud sync reaches its server:
 * GET, PUT and DELETE of the files directly in it, with Basic authentication
 * and conditional writes (If-Match, If-None-Match). Never follows a redirect:
 * the password goes to the folder's host only. Blocking: worker threads only. */
public class WebDavHttp implements CloudStore {
    private static final int CONNECT_MILLIS = 15_000, READ_MILLIS = 30_000;

    private final String folder;
    private final String authorization;
    private final String userAgent;

    /** folder: an https:// address, checked by {@link #folderAddress}. */
    public WebDavHttp(String folder, String user, String password, String userAgent) {
        this.folder = folderAddress(folder);
        if (this.folder == null) throw new IllegalArgumentException("Invalid WebDAV folder");
        byte[] credentials = (user + ":" + password).getBytes(StandardCharsets.UTF_8);
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(credentials);
        this.userAgent = userAgent;
    }

    /** The folder's address, ending with a slash; null unless it is https://
     * with a host, without credentials, query or fragment. */
    public static String folderAddress(String typed) {
        if (typed == null) return null;
        String address = typed.trim();
        if (address.isEmpty() || address.length() > 2048) return null;
        try {
            URI uri = new URI(address);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                return null;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (!path.endsWith("/")) path += "/";
            return "https://" + uri.getRawAuthority().toLowerCase(Locale.ROOT) + path;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    /** The host shown in the settings, from a folder address. */
    public static String host(String folder) {
        try {
            return new URI(folder).getHost();
        } catch (URISyntaxException | NullPointerException invalid) {
            return "";
        }
    }

    @Override public Fetched get(String name, int maxBytes) throws IOException {
        HttpURLConnection connection = open(name, "GET");
        try {
            int status = answered(connection);
            byte[] body = new byte[0];
            if (status == HttpURLConnection.HTTP_OK) {
                long declared = connection.getContentLengthLong();
                if (declared > maxBytes) throw new IOException("Fichier du cloud trop volumineux");
                try (InputStream stream = connection.getInputStream()) { body = read(stream, maxBytes); }
            }
            return new Fetched(status, body, connection.getHeaderField("ETag"));
        } finally {
            connection.disconnect();
        }
    }

    @Override public int put(String name, byte[] body, String ifMatch, boolean create) throws IOException {
        HttpURLConnection connection = open(name, "PUT");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", name.endsWith(".json")
                    ? "application/json; charset=utf-8" : "application/octet-stream");
            if (ifMatch != null) connection.setRequestProperty("If-Match", ifMatch);
            else if (create) connection.setRequestProperty("If-None-Match", "*");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            return answered(connection);
        } finally {
            connection.disconnect();
        }
    }

    @Override public int delete(String name) throws IOException {
        HttpURLConnection connection = open(name, "DELETE");
        try {
            return answered(connection);
        } finally {
            connection.disconnect();
        }
    }

    /** The status; a block (429) stops the sync with the wait the server gives. */
    private static int answered(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status == 429) throw CloudRefused.blocked(retryAfterSeconds(connection));
        return status;
    }

    /** Retry-After, in seconds or as a date; negative when absent. */
    static long retryAfterSeconds(HttpURLConnection connection) {
        String value = connection.getHeaderField("Retry-After");
        if (value == null) return -1;
        value = value.trim();
        if (value.matches("[0-9]{1,9}")) return Long.parseLong(value);
        long date = connection.getHeaderFieldDate("Retry-After", 0);
        return date > 0 ? Math.max(0, (date - System.currentTimeMillis()) / 1000) : -1;
    }

    /** Only names RavenEmu writes: letters, digits, dots and dashes. */
    private HttpURLConnection open(String name, String method) throws IOException {
        if (name == null || !name.matches("[a-z0-9][a-z0-9.-]{0,199}"))
            throw new IllegalArgumentException("Invalid cloud file");
        HttpURLConnection connection = connect(new URL(folder + name));
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(CONNECT_MILLIS);
        connection.setReadTimeout(READ_MILLIS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", authorization);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Cache-Control", "no-cache");
        return connection;
    }

    /** Tests reach a local server through this. */
    HttpURLConnection connect(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
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
