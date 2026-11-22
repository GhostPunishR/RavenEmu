package com.ravenemu.app.data.saves;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A WebDAV folder in memory, with the ETags and conditional writes of a
 * real server, and the failures a test asks for. */
public final class FakeCloudStore implements CloudStore {
    public final Map<String, byte[]> files = new LinkedHashMap<>();
    private final Map<String, Integer> versions = new LinkedHashMap<>();
    private int nextVersion = 1;
    /** Every request, "METHOD name", in order. */
    public final List<String> log = new ArrayList<>();
    /** Answered to every request when not 0 (401, 403…). */
    public int status;
    /** The folder does not exist: GET 404, PUT 409. */
    public boolean missingFolder;
    /** Run once, just before the next PUT of the list: another device writes. */
    public Runnable beforeListPut;
    /** No ETag in the answers, as some servers. */
    public boolean noEtags;

    @Override public Fetched get(String name, int maxBytes) {
        log.add("GET " + name);
        if (status != 0) return new Fetched(status, null, null);
        byte[] body = missingFolder ? null : files.get(name);
        if (body == null) return new Fetched(404, null, null);
        return new Fetched(200, body.clone(), etag(name));
    }

    @Override public int put(String name, byte[] body, String ifMatch, boolean create) {
        if (name.equals(CloudManifest.FILE) && beforeListPut != null) {
            Runnable other = beforeListPut;
            beforeListPut = null;
            other.run();
        }
        log.add("PUT " + name + (ifMatch != null ? " if-match " + ifMatch : create ? " if-none-match" : ""));
        if (status != 0) return status;
        if (missingFolder) return 409;
        boolean exists = files.containsKey(name);
        if (ifMatch != null && !ifMatch.equals(etag(name))) return 412;
        if (ifMatch == null && create && exists) return 412;
        files.put(name, body.clone());
        versions.put(name, nextVersion++);
        return exists ? 204 : 201;
    }

    @Override public int delete(String name) {
        log.add("DELETE " + name);
        if (status != 0) return status;
        versions.remove(name);
        return files.remove(name) == null ? 404 : 204;
    }

    private String etag(String name) {
        Integer version = versions.get(name);
        return noEtags || version == null ? null : "\"v" + version + "\"";
    }

    /** Another device's own write of the list, as it would do it. */
    public void replaceList(CloudManifest manifest) {
        files.put(CloudManifest.FILE, manifest.toBytes());
        versions.put(CloudManifest.FILE, nextVersion++);
    }
}
