package com.ravenemu.app.data.saves;

/** What the cloud sync does with one game's cartridge save, from three
 * SHA-256 digests, as RetroArch's cloud sync compares its local file, the
 * server's and its last-sync manifest: this phone's save now, the cloud's
 * now, and the one both had at this phone's last sync (null: none). A save
 * changed on one side only follows that side; changed on both, it is a
 * conflict that the player settles, and nothing is overwritten. Nothing is
 * ever deleted: a save missing on one side is copied to it. Free of Android
 * types. */
public final class CloudSyncPlan {
    public enum Action {
        /** Both sides hold the same save, or neither has one. */
        NONE,
        /** This phone's save goes to the cloud. */
        UPLOAD,
        /** The cloud's save replaces this phone's, kept aside first. */
        DOWNLOAD,
        /** Both changed since the last sync: the player chooses. */
        CONFLICT
    }

    private CloudSyncPlan() {}

    public static Action decide(String local, String base, String cloud) {
        if (local == null ? cloud == null : local.equals(cloud)) return Action.NONE;
        if (cloud == null) return Action.UPLOAD;
        if (local == null) return Action.DOWNLOAD;
        if (local.equals(base)) return Action.DOWNLOAD;
        if (cloud.equals(base)) return Action.UPLOAD;
        // Both changed, or this phone never synced the two saves it compares.
        return Action.CONFLICT;
    }
}
