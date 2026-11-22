package com.ravenemu.app.data.saves;

import java.io.IOException;

/** The cloud folder the cartridge saves are synced with: a WebDAV folder
 * (platform.WebDavHttp), or the tests' own. Files sit directly in it. */
public interface CloudStore {
    /** An answer to GET: its HTTP status, its body when 200, its ETag. */
    final class Fetched {
        public final int status;
        public final byte[] body;
        public final String etag;

        public Fetched(int status, byte[] body, String etag) {
            this.status = status;
            this.body = body == null ? new byte[0] : body;
            this.etag = etag;
        }
    }

    Fetched get(String name, int maxBytes) throws IOException;

    /** ifMatch: only over that version; create (without ifMatch): only if
     * the file is absent. Returns the HTTP status. */
    int put(String name, byte[] body, String ifMatch, boolean create) throws IOException;

    int delete(String name) throws IOException;
}
