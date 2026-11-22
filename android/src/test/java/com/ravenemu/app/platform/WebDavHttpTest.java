package com.ravenemu.app.platform;

import static org.junit.Assert.*;

import com.ravenemu.app.data.saves.CloudStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The requests a WebDAV server receives, on a local server: the folder's
 * address is https:// as in the app, the test only routes it to loopback. */
public final class WebDavHttpTest {
    private ServerSocket server;
    private Thread serving;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile int status = 200;
    private volatile byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    private volatile String location;

    /** A minimal HTTP/1.1 server: one request per connection, as the client sends them. */
    @Before public void start() throws IOException {
        server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        serving = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    answer(socket);
                } catch (IOException closed) {
                    // The test is over.
                }
            }
        });
        serving.setDaemon(true);
        serving.start();
    }

    private void answer(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        String requestLine = line(input);
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (String header = line(input); !header.isEmpty(); header = line(input)) {
            int colon = header.indexOf(':');
            headers.put(header.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), header.substring(colon + 1).trim());
        }
        int length = headers.containsKey("content-length") ? Integer.parseInt(headers.get("content-length")) : 0;
        byte[] sent = new byte[length];
        for (int read = 0; read < length; ) read += input.read(sent, read, length - read);
        String[] parts = requestLine.split(" ");
        StringBuilder logged = new StringBuilder(parts[0] + " " + parts[1]);
        for (String header : new String[]{"Authorization", "If-Match", "If-None-Match", "Content-Type"}) {
            String value = headers.get(header.toLowerCase(java.util.Locale.ROOT));
            if (value != null) logged.append(" | ").append(header).append(": ").append(value);
        }
        if (sent.length > 0) logged.append(" | body: ").append(new String(sent, StandardCharsets.UTF_8));
        requests.add(logged.toString());
        boolean withBody = status == 200 && "GET".equals(parts[0]);
        byte[] reply = withBody ? body : new byte[0];
        StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " Status\r\nETag: \"etag-1\"\r\n"
                + "Connection: close\r\nContent-Length: " + reply.length + "\r\n");
        if (location != null) head.append("Location: ").append(location).append("\r\n");
        OutputStream output = socket.getOutputStream();
        output.write(head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        output.write(reply);
        output.flush();
    }

    private static String line(InputStream input) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int c = input.read(); c != -1 && c != '\n'; c = input.read()) if (c != '\r') text.append((char) c);
        return text.toString();
    }

    @After public void stop() throws IOException { server.close(); }

    private WebDavHttp client() {
        final int port = server.getLocalPort();
        return new WebDavHttp("https://dav.example.org/files/me/RavenEmu", "me", "pässword", "RavenEmu/test") {
            @Override HttpURLConnection connect(URL url) throws IOException {
                assertEquals("https", url.getProtocol());
                assertEquals("dav.example.org", url.getHost());
                return (HttpURLConnection) new URL("http://127.0.0.1:" + port + url.getPath()).openConnection();
            }
        };
    }

    @Test public void onlyAnHttpsFolderWithoutCredentialsOrQueryIsAccepted() {
        assertEquals("https://dav.example.org/files/me/RavenEmu/",
                WebDavHttp.folderAddress("  https://DAV.example.org/files/me/RavenEmu "));
        assertEquals("https://nas.local:5006/", WebDavHttp.folderAddress("https://nas.local:5006"));
        for (String refused : new String[]{null, "", "http://dav.example.org/x/", "ftp://dav.example.org/",
                "https://me:secret@dav.example.org/x/", "https://dav.example.org/x/?a=b",
                "https://dav.example.org/x/#top", "dav.example.org/x", "https:///x/"})
            assertNull(refused, WebDavHttp.folderAddress(refused));
        assertEquals("dav.example.org", WebDavHttp.host("https://dav.example.org/files/"));
    }

    @Test public void eachRequestCarriesBasicAuthAndItsCondition() throws Exception {
        WebDavHttp client = client();
        CloudStore.Fetched list = client.get("ravenemu-saves.json", 1024);
        assertEquals(200, list.status);
        assertEquals("{}", new String(list.body, StandardCharsets.UTF_8));
        assertEquals("\"etag-1\"", list.etag);
        String basic = "Basic " + Base64.getEncoder().encodeToString("me:pässword".getBytes(StandardCharsets.UTF_8));
        assertEquals("GET /files/me/RavenEmu/ravenemu-saves.json | Authorization: " + basic, requests.get(0));

        status = 201;
        assertEquals(201, client.put("ravenemu-saves.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                "\"etag-1\"", false));
        assertEquals("PUT /files/me/RavenEmu/ravenemu-saves.json | Authorization: " + basic
                + " | If-Match: \"etag-1\" | Content-Type: application/json; charset=utf-8 | body: {\"a\":1}",
                requests.get(1));
        assertEquals(201, client.put("ravenemu-1-2.srm", "x".getBytes(StandardCharsets.UTF_8), null, true));
        assertEquals("PUT /files/me/RavenEmu/ravenemu-1-2.srm | Authorization: " + basic
                + " | If-None-Match: * | Content-Type: application/octet-stream | body: x", requests.get(2));
        status = 204;
        assertEquals(204, client.delete("ravenemu-1-2.srm"));
        assertEquals("DELETE /files/me/RavenEmu/ravenemu-1-2.srm | Authorization: " + basic, requests.get(3));
    }

    @Test public void aRedirectIsAnsweredNotFollowed() throws Exception {
        status = 302;
        location = "https://elsewhere.example.com/steal";
        assertEquals(302, client().get("ravenemu-saves.json", 1024).status);
        assertEquals(1, requests.size());
    }

    @Test public void aFileLargerThanAllowedIsRefused() {
        body = new byte[2048];
        try {
            client().get("ravenemu-saves.json", 1024);
            fail("A body over the limit was read");
        } catch (IOException refused) {
            assertTrue(refused.getMessage().contains("trop volumineux"));
        }
    }

    @Test public void onlyRavenEmusOwnFileNamesAreRequested() {
        for (String name : new String[]{"../secret", "a/b", "", "UPPER.json", "x?y"}) {
            try {
                client().get(name, 10);
                fail("Requested " + name);
            } catch (IllegalArgumentException | IOException refused) {
                assertTrue(refused instanceof IllegalArgumentException);
            }
        }
        assertTrue(requests.isEmpty());
    }
}
