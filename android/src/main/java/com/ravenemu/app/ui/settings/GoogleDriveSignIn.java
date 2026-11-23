package com.ravenemu.app.ui.settings;

import android.content.Intent;
import android.content.IntentSender;
import com.ravenemu.app.data.saves.CloudSync;
import com.ravenemu.app.platform.GoogleDriveAuth;
import com.ravenemu.app.platform.GoogleDriveHttp;
import com.ravenemu.app.settings.CloudAccount;
import com.ravenemu.app.ui.library.CloudSyncRunner;
import java.io.IOException;
import java.security.GeneralSecurityException;

/** Settings → Sauvegardes cloud → « Se connecter avec Google »: Google's own
 * screens choose the account and allow RavenEmu's folder, then the folder is
 * created and checked, as a WebDAV one is, before the account is stored. */
final class GoogleDriveSignIn {
    /** The consent screen's result, routed by PlaybackSettingsActivity. */
    static final int REQUEST = 0x4744;

    /** On the UI thread, also after the activity was recreated meanwhile. */
    interface Outcome {
        void signedIn(CloudAccount account);
        void failed(String message);
    }

    private final PlaybackSettingsActivity activity;
    private final Outcome outcome;

    private final GoogleDriveAuth.Answer answer = new GoogleDriveAuth.Answer() {
        @Override public void granted(String token) { check(token); }

        @Override public void consent(IntentSender screen) {
            try {
                activity.startIntentSenderForResult(screen, REQUEST, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException unavailable) {
                outcome.failed("Google n'a pas pu ouvrir sa page de connexion");
            }
        }

        @Override public void failed(String message) { outcome.failed(message); }
    };

    GoogleDriveSignIn(PlaybackSettingsActivity activity, Outcome outcome) {
        this.activity = activity;
        this.outcome = outcome;
    }

    /** UI thread: Google's screens, if the account or the consent are needed. */
    void start() { GoogleDriveAuth.authorizer.authorize(activity, answer); }

    /** UI thread: the consent screen closed. Google's screen also closes as
     * cancelled when Google refuses RavenEmu (an OAuth client not declared,
     * for one): its answer then says why, and is read whatever the code. */
    void result(int resultCode, Intent data) {
        if (data == null) outcome.failed("Connexion à Google annulée");
        else GoogleDriveAuth.authorizer.result(activity, data, answer);
    }

    /** The account's address, then the folder and its list, off the UI thread. */
    private void check(String token) {
        activity.network.execute(() -> {
            GoogleDriveHttp drive = new GoogleDriveHttp(new GoogleDriveHttp.Tokens() {
                @Override public String token() { return token; }
                @Override public void refused(String refused) {}
            }, CloudSyncRunner.userAgent());
            CloudAccount account = null;
            String failure;
            try {
                String email = drive.email();
                failure = CloudSync.verify(drive);
                if (failure == null) {
                    account = CloudAccount.googleDrive(email, true);
                    account.save(activity);
                }
            } catch (IOException unreachable) {
                failure = CloudSync.message(unreachable);
            } catch (GeneralSecurityException unsaved) {
                failure = "Le compte n'a pas pu être chiffré sur cet appareil : réessayez plus tard.";
            }
            final CloudAccount stored = failure == null ? account : null;
            final String shown = failure;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                if (stored != null) outcome.signedIn(stored);
                else outcome.failed(shown);
            });
        });
    }
}
