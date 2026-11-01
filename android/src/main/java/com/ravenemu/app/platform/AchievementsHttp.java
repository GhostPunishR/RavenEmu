package com.ravenemu.app.platform;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** The network of RetroAchievements: rcheevos' requests and the badges and
 * avatars, over HTTPS to retroachievements.org and its subdomains only, with
 * the User-Agent the service asks for. Blocking: worker threads only. */
public class AchievementsHttp {
    /** No answer came: rcheevos counts it as the network being gone. */
    public static final int NO_ANSWER = -1;
    /** A whole game's set stays far below; the bridge refuses more. */
    public static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    public static final int MAX_IMAGE_BYTES = 512 * 1024;
    /** Badges are 64 pixels square, avatars 128: a larger picture is not one. */
    public static final int MAX_IMAGE_SIDE = 512;
    private static final int CONNECT_MILLIS = 15_000, READ_MILLIS = 30_000;

    public static final class Answer {
        public final byte[] body;
        public final int status;
        public Answer(byte[] body, int status) {
            this.body = body == null ? new byte[0] : body;
            this.status = status;
        }
    }

    private final String userAgent;

    public AchievementsHttp(String userAgent) { this.userAgent = userAgent; }

    /** A POST with rcheevos' form, a GET without one. Never throws. */
    public Answer send(String url, String postData, String contentType) {
        if (!allowed(url)) return new Answer(null, NO_ANSWER);
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            if (postData != null && !postData.isEmpty()) {
                byte[] form = postData.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", contentType == null || contentType.isEmpty()
                        ? "application/x-www-form-urlencoded" : contentType);
                connection.setFixedLengthStreamingMode(form.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(form); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] body = stream == null ? new byte[0] : read(stream, MAX_BODY_BYTES);
            return body == null ? new Answer(null, NO_ANSWER) : new Answer(body, status);
        } catch (IOException | RuntimeException unreachable) {
            return new Answer(null, NO_ANSWER);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** A badge or an avatar; null when unavailable or too large. */
    public byte[] image(String url) {
        if (!allowed(url)) return null;
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream stream = connection.getInputStream()) { return read(stream, MAX_IMAGE_BYTES); }
        } catch (IOException | RuntimeException unreachable) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** A badge, an icon or an avatar, decoded; null when unavailable. */
    public Bitmap badge(String url) { return decode(image(url)); }

    public static Bitmap decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > MAX_IMAGE_SIDE || bounds.outHeight > MAX_IMAGE_SIDE) return null;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    /** HTTPS to retroachievements.org or one of its subdomains, nothing else. */
    public static boolean allowed(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("retroachievements.org") || host.endsWith(".retroachievements.org");
        } catch (URISyntaxException malformed) {
            return false;
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_MILLIS);
        connection.setReadTimeout(READ_MILLIS);
        // A redirection could leave the allowed hosts: none is followed.
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", userAgent);
        return connection;
    }

    /** At most `limit` bytes; null for more. */
    private static byte[] read(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int count; (count = stream.read(buffer)) != -1; ) {
            if (body.size() + count > limit) return null;
            body.write(buffer, 0, count);
        }
        return body.toByteArray();
    }
}
