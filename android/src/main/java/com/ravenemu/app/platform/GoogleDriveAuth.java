package com.ravenemu.app.platform;

import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.ravenemu.app.BuildConfig;
import com.ravenemu.app.data.saves.CloudRefused;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Google Drive access for the cloud saves, by Google Play services'
 * authorization API: the player chooses the account and allows the
 * drive.file scope once, then each sync takes a fresh token without any
 * screen. Play services keep the grant and the tokens; RavenEmu keeps only
 * the account's address. Nothing is asked of Google until the player taps
 * « Se connecter avec Google ». */
public final class GoogleDriveAuth {
    /** The files RavenEmu creates, and nothing else of the Drive. */
    public static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String RECONNECT =
            "Google Drive demande une nouvelle autorisation : reconnectez-vous dans Paramètres → Sauvegardes cloud";
    private static final long WAIT_SECONDS = 30;
    /** Google Sign-In's codes, as its screens still answer them: cancelled, and
     * failed (most often an OAuth client not declared). */
    static final int SIGN_IN_FAILED = 12500, SIGN_IN_CANCELLED = 12501;

    /** The connection's outcome, on the UI thread. */
    public interface Answer {
        void granted(String token);
        /** Google shows its own screens (account, consent): start this for a result. */
        void consent(IntentSender screen);
        void failed(String message);
    }

    /** Google Play services, apart for the tests. */
    public interface Authorizer {
        /** UI thread: asks for access, for the account chosen on Google's screen. */
        void authorize(Activity activity, Answer answer);
        /** UI thread: the result of the consent screen. */
        void result(Activity activity, Intent data, Answer answer);
        /** Worker thread: the tokens of `email`, without any screen. */
        GoogleDriveHttp.Tokens tokens(Context context, String email);
    }

    public static final Authorizer PLAY = new Authorizer() {
        @Override public void authorize(Activity activity, Answer answer) {
            String missing = unavailable(activity);
            if (missing != null) {
                answer.failed(missing);
                return;
            }
            Identity.getAuthorizationClient(activity).authorize(request(null))
                    .addOnSuccessListener(activity, result -> {
                        if (result.hasResolution() && result.getPendingIntent() != null)
                            answer.consent(result.getPendingIntent().getIntentSender());
                        else if (result.getAccessToken() != null) answer.granted(result.getAccessToken());
                        else answer.failed("Google n'a pas donné accès au Drive");
                    })
                    .addOnFailureListener(activity, failure -> answer.failed(message(failure)));
        }

        @Override public void result(Activity activity, Intent data, Answer answer) {
            try {
                AuthorizationResult result = Identity.getAuthorizationClient(activity)
                        .getAuthorizationResultFromIntent(data);
                if (result.getAccessToken() != null) answer.granted(result.getAccessToken());
                else answer.failed("Google n'a pas donné accès au Drive");
            } catch (ApiException failure) {
                answer.failed(message(failure));
            }
        }

        @Override public GoogleDriveHttp.Tokens tokens(Context context, String email) {
            final Context app = context.getApplicationContext();
            return new GoogleDriveHttp.Tokens() {
                @Override public String token() throws IOException {
                    // Only the player can install Play services: no automatic retry.
                    String missing = unavailable(app);
                    if (missing != null) throw new CloudRefused(missing);
                    AuthorizationResult result = await(Identity.getAuthorizationClient(app).authorize(request(email)));
                    if (result.hasResolution() || result.getAccessToken() == null) throw new CloudRefused(RECONNECT);
                    return result.getAccessToken();
                }

                @Override public void refused(String token) {
                    try {
                        await(Identity.getAuthorizationClient(app)
                                .clearToken(ClearTokenRequest.builder().setToken(token).build()));
                    } catch (IOException unreachable) {
                        // Play services then hand the same token, refused again: the sync says so.
                    }
                }
            };
        }
    };

    /** The one in use: the tests set their own. */
    public static volatile Authorizer authorizer = PLAY;

    /** Whether the settings offer Google Drive: only once RavenEmu's Google
     * project declares the app (ravenemuGoogleDrive, GOOGLE_DRIVE.md),
     * as Google refuses it until then. The tests set it. */
    public static volatile boolean offered = BuildConfig.GOOGLE_DRIVE;

    private GoogleDriveAuth() {}

    static AuthorizationRequest request(String email) {
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)));
        if (email != null) builder.setAccount(new Account(email, "com.google"));
        return builder.build();
    }

    /** Why Google Drive cannot work on this phone, or null. */
    static String unavailable(Context context) {
        int status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        if (status == ConnectionResult.SUCCESS) return null;
        if (status == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED)
            return "Mettez à jour les services Google Play pour utiliser Google Drive";
        return "Les services Google Play sont absents ou désactivés : Google Drive n'est pas disponible sur ce "
                + "téléphone. Un dossier WebDAV reste possible.";
    }

    private static <T> T await(Task<T> task) throws IOException {
        try {
            return Tasks.await(task, WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            throw failure(failure.getCause());
        } catch (TimeoutException late) {
            throw new IOException("Les services Google Play ne répondent pas");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Synchronisation interrompue");
        }
    }

    /** Play services' failure, as the sync takes it: what only the player can
     * settle (signing in again, RavenEmu's OAuth client) stops the automatic
     * syncs (CloudRefused); the network and the rest are tried again later. */
    static IOException failure(Throwable cause) {
        String message = message(cause);
        if (cause instanceof ApiException) {
            int status = ((ApiException) cause).getStatusCode();
            if (status == CommonStatusCodes.SIGN_IN_REQUIRED || status == CommonStatusCodes.DEVELOPER_ERROR
                    || status == SIGN_IN_FAILED)
                return new CloudRefused(message);
        }
        return new IOException(message);
    }

    /** Play services' failure, in French; an unknown one gives Google's code,
     * which says what to look at (GOOGLE_DRIVE.md). */
    static String message(Throwable failure) {
        if (failure instanceof ApiException) {
            int code = ((ApiException) failure).getStatusCode();
            switch (code) {
                case CommonStatusCodes.NETWORK_ERROR: return "Réseau indisponible : réessayez une fois connecté";
                case CommonStatusCodes.CANCELED:
                case SIGN_IN_CANCELLED: return "Connexion à Google annulée";
                case CommonStatusCodes.SIGN_IN_REQUIRED: return RECONNECT;
                case CommonStatusCodes.DEVELOPER_ERROR:
                case SIGN_IN_FAILED:
                    return "Google ne reconnaît pas encore cette version de RavenEmu : son projet Google doit "
                            + "déclarer l'application (identifiant OAuth Android, code " + code + ")";
                default: return "Google a refusé la connexion (code " + code + ")";
            }
        }
        return "Google Drive est injoignable pour l'instant";
    }
}
