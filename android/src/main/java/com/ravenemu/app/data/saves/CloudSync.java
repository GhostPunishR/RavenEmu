package com.ravenemu.app.data.saves;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLException;

/** One sync of the cartridge saves of the library's games with the cloud
 * folder, after RetroArch's cloud sync: the cloud's list is read, each game
 * is decided by CloudSyncPlan, the saves to send are written as new files,
 * then the list is replaced only if nobody changed it meanwhile; otherwise
 * the whole sync starts again from the new list. A save taken from the cloud
 * is checked against its SHA-256 before it replaces this phone's, which is
 * kept aside first. Nothing is deleted but the cloud's oldest versions. */
public final class CloudSync {
    /** At most this many passes when another device writes at the same time. */
    static final int ATTEMPTS = 3;

    /** This phone's saves, as CloudSaves keeps them. */
    public interface LocalSaves {
        /** The game's save, or null without one. */
        byte[] read(String key) throws IOException;
        /** When the game's save was last written, or 0. */
        long modified(String key);
        /** Replaces the save only if it is still `expectedSha256` (null: none)
         * and the game is not being played; false otherwise. */
        boolean replace(String key, String expectedSha256, byte[] bytes) throws IOException;
    }

    /** The player's answer to a conflict, valid for the two saves it was about. */
    public static final class Resolution {
        public final boolean keepPhone;
        public final String phoneSha256, cloudSha256;

        public Resolution(boolean keepPhone, String phoneSha256, String cloudSha256) {
            this.keepPhone = keepPhone;
            this.phoneSha256 = phoneSha256;
            this.cloudSha256 = cloudSha256;
        }
    }

    private final CloudStore store;
    private final LocalSaves saves;
    private final String device;

    public CloudSync(CloudStore store, LocalSaves saves, String device) {
        this.store = store;
        this.saves = saves;
        this.device = device;
    }

    /** Syncs `keys` (the library's games) from `state`, settling the given
     * conflicts; returns the state to keep. Never throws: an error is kept
     * in the state, with what was done before it. */
    public CloudSyncState run(CloudSyncState state, List<String> keys, Map<String, Resolution> resolutions,
                              long now) {
        Map<String, String> base = new LinkedHashMap<>(state.base);
        List<CloudSyncState.Conflict> conflicts = keepKnown(state.conflicts, keys);
        String error = "";
        boolean refused = false;
        int uploaded = 0, downloaded = 0;
        try {
            for (int attempt = 0; ; attempt++) {
                Pass pass = new Pass();
                boolean done;
                try {
                    done = pass.run(keys, base, resolutions, now);
                } finally {
                    downloaded += pass.downloaded;
                }
                if (done) {
                    uploaded = pass.uploaded;
                    conflicts = pass.conflicts;
                    if (pass.unreadable > 0)
                        error = pass.unreadable == 1 ? "Une sauvegarde de ce téléphone est illisible : elle n'est pas "
                                + "synchronisée" : pass.unreadable + " sauvegardes de ce téléphone sont illisibles : "
                                + "elles ne sont pas synchronisées";
                    else if (pass.problem != null) error = pass.problem;
                    break;
                }
                if (attempt == ATTEMPTS - 1)
                    throw new IOException("Un autre appareil modifie le cloud en même temps : réessayez");
            }
        } catch (IOException failure) {
            error = message(failure);
            refused = failure instanceof CloudRefused;
        }
        return new CloudSyncState(state.account, base, conflicts, now, error, refused, uploaded, downloaded);
    }

    /** Signing in: the folder answers, and holds a list or can hold one.
     * Null when it can be used; otherwise what to tell the player. */
    public static String verify(CloudStore store) {
        try {
            CloudStore.Fetched list = store.get(CloudManifest.FILE, CloudManifest.MAX_BYTES);
            if (list.status == 200) {
                CloudManifest.parse(list.body);
                return null;
            }
            if (list.status != 404) return statusMessage(list.status);
            int created = store.put(CloudManifest.FILE, CloudManifest.empty().toBytes(), null, true);
            if (created == 200 || created == 201 || created == 204 || created == 412) return null;
            return statusMessage(created);
        } catch (IOException failure) {
            return message(failure);
        }
    }

    /** One read of the cloud's list, and what follows from it. */
    private final class Pass {
        int uploaded, downloaded, unreadable;
        /** The first save the cloud could not give back whole; the others still sync. */
        String problem;
        final List<CloudSyncState.Conflict> conflicts = new ArrayList<>();

        /** False when another device replaced the list meanwhile. */
        boolean run(List<String> keys, Map<String, String> base, Map<String, Resolution> resolutions, long now)
                throws IOException {
            CloudStore.Fetched fetched = store.get(CloudManifest.FILE, CloudManifest.MAX_BYTES);
            final CloudManifest manifest;
            if (fetched.status == 200) manifest = CloudManifest.parse(fetched.body);
            else if (fetched.status == 404) manifest = CloudManifest.empty();
            else throw failure(fetched.status);
            Map<String, byte[]> sent = new LinkedHashMap<>();
            List<String> dropped = new ArrayList<>();
            for (String key : keys) {
                byte[] local;
                try {
                    local = saves.read(key);
                } catch (IOException damaged) {
                    unreadable++;
                    continue;
                }
                String phone = local == null ? null : sha256(local);
                CloudManifest.Entry entry = manifest.get(key);
                String cloud = entry == null ? null : entry.sha256;
                CloudSyncPlan.Action action = CloudSyncPlan.decide(phone, base.get(key), cloud);
                if (action == CloudSyncPlan.Action.CONFLICT) {
                    Resolution answer = resolutions.get(key);
                    if (answer != null && answer.phoneSha256.equals(phone) && answer.cloudSha256.equals(cloud))
                        action = answer.keepPhone ? CloudSyncPlan.Action.UPLOAD : CloudSyncPlan.Action.DOWNLOAD;
                }
                switch (action) {
                    case NONE:
                        if (cloud != null) base.put(key, cloud);
                        break;
                    case UPLOAD:
                        int status = store.put(CloudManifest.fileName(key, phone), local, null, false);
                        if (status != 200 && status != 201 && status != 204) throw failure(status);
                        dropped.addAll(manifest.put(key, phone, local.length, now, device));
                        sent.put(key, local);
                        break;
                    case DOWNLOAD:
                        String failure = download(key, entry, phone);
                        if (failure == null) {
                            base.put(key, cloud);
                            downloaded++;
                        } else if (!failure.isEmpty() && problem == null) problem = failure;
                        break;
                    default:
                        conflicts.add(new CloudSyncState.Conflict(key, phone, saves.modified(key), cloud,
                                entry.modified, entry.device));
                }
            }
            if (sent.isEmpty()) return true;
            String etag = fetched.status == 200 ? fetched.etag : null;
            int status = store.put(CloudManifest.FILE, manifest.toBytes(), etag, fetched.status == 404);
            if (status == 412) return false;
            if (status != 200 && status != 201 && status != 204) throw failure(status);
            for (Map.Entry<String, byte[]> save : sent.entrySet()) base.put(save.getKey(), sha256(save.getValue()));
            uploaded = sent.size();
            // The oldest versions: the list no longer names them.
            for (String name : dropped) {
                try {
                    store.delete(name);
                } catch (IOException kept) {
                    // A file left behind costs space only.
                }
            }
            return true;
        }

        /** Null when replaced; "" when left for later (played, changed
         * meanwhile); otherwise why the cloud's file was not taken. */
        private String download(String key, CloudManifest.Entry entry, String phone) throws IOException {
            CloudStore.Fetched file = store.get(CloudManifest.fileName(key, entry.sha256),
                    SaveRepository.BATTERY_LIMIT_BYTES);
            if (file.status == 401 || file.status == 403 || file.status == 429) throw failure(file.status);
            if (file.status != 200) return "Une sauvegarde manque dans le cloud : elle n'est pas reçue";
            if (file.body.length != entry.size || !sha256(file.body).equals(entry.sha256))
                return "Sauvegarde du cloud incomplète ou modifiée : elle n'est pas reçue";
            return saves.replace(key, phone, file.body) ? null : "";
        }
    }

    /** After an error, the conflicts already known for games still in the library. */
    private static List<CloudSyncState.Conflict> keepKnown(List<CloudSyncState.Conflict> known, List<String> keys) {
        List<CloudSyncState.Conflict> kept = new ArrayList<>();
        for (CloudSyncState.Conflict conflict : known) if (keys.contains(conflict.key)) kept.add(conflict);
        return Collections.unmodifiableList(kept);
    }

    /** A refusal stops the automatic syncs (CloudRefused); other errors do not. */
    static IOException failure(int status) {
        if (status == 401 || status == 403 || status == 429) return new CloudRefused(statusMessage(status));
        return new IOException(statusMessage(status));
    }

    static String statusMessage(int status) {
        switch (status) {
            case 401: return "Identifiant ou mot de passe refusé : avec Koofr, kDrive ou Nextcloud, utilisez un mot "
                    + "de passe d'application. Après plusieurs refus, le cloud peut bloquer les connexions un moment.";
            case 403: return "Accès refusé à ce dossier";
            case 404:
            case 409: return "Dossier introuvable : créez-le d'abord dans votre cloud";
            // Koofr, among others, answers 429 after several refused passwords.
            case 429: return CloudRefused.blocked(-1).getMessage();
            case 507: return "Espace du cloud plein";
            default:
                if (status >= 300 && status < 400)
                    return "Le serveur redirige ailleurs : vérifiez l'adresse du dossier";
                return "Le cloud a répondu " + status;
        }
    }

    public static String message(IOException failure) {
        if (failure instanceof SSLException) return "Connexion sécurisée refusée : certificat du serveur non reconnu";
        if (failure instanceof java.net.UnknownHostException) return "Serveur introuvable : vérifiez l'adresse";
        if (failure instanceof java.net.SocketTimeoutException) return "Le serveur ne répond pas";
        String text = failure.getMessage();
        return text == null || text.isEmpty() ? "Serveur injoignable" : text;
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) {
                result.append(hex[(b >>> 4) & 15]);
                result.append(hex[b & 15]);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
