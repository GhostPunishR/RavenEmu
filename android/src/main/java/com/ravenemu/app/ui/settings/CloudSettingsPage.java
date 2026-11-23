package com.ravenemu.app.ui.settings;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.data.saves.CloudSync;
import com.ravenemu.app.data.saves.CloudSyncState;
import com.ravenemu.app.platform.WebDavHttp;
import com.ravenemu.app.settings.CloudAccount;
import com.ravenemu.app.ui.common.RavenTheme;
import com.ravenemu.app.ui.library.CloudSyncRunner;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.CLOUD;

/** Settings → Sauvegardes cloud: the cloud the cartridge saves are synced
 * with, Google Drive or a WebDAV folder as RetroArch's cloud sync (Nextcloud,
 * kDrive, a NAS…), how the last sync went, and the conflicts the player
 * settles. Only the .srm of the library's games are sent: never a ROM, a BIOS
 * or a save state. */
final class CloudSettingsPage implements CloudSyncRunner.Listener {
    private static final int ERROR = 0xffff928b;

    private final PlaybackSettingsActivity activity;
    private final SettingsWidgets widgets;
    private final RavenTheme theme;
    /** Read off the UI thread, then as signed in or out here. */
    private CloudAccount account;
    private CloudSyncState state;
    private final Map<String, String> titles = new HashMap<>();
    private boolean accountRead, signingIn, signingOut, googleSigningIn;
    private String error, typedFolder = "", typedUser = "";
    private AlertDialog signOutDialog;
    private final GoogleDriveSignIn google;

    CloudSettingsPage(PlaybackSettingsActivity activity, SettingsWidgets widgets) {
        this.activity = activity;
        this.widgets = widgets;
        this.theme = activity.theme();
        this.google = new GoogleDriveSignIn(activity, new GoogleDriveSignIn.Outcome() {
            @Override public void signedIn(CloudAccount signedIn) {
                signingIn = googleSigningIn = false;
                error = null;
                account = signedIn;
                state = null;
                CloudSyncRunner.now(activity);
                if (CLOUD.equals(activity.page())) activity.showPage(CLOUD);
            }

            @Override public void failed(String message) {
                signingIn = googleSigningIn = false;
                error = message;
                if (CLOUD.equals(activity.page())) activity.showPage(CLOUD);
            }
        });
    }

    /** Google's consent screen closed. */
    void googleResult(int resultCode, Intent data) {
        signingIn = googleSigningIn = true;
        google.result(resultCode, data);
    }

    /** The account, the last sync and the games' titles, read once off the UI thread. */
    void start() {
        CloudSyncRunner.addListener(this);
        reload();
    }

    void dismiss() {
        CloudSyncRunner.removeListener(this);
        if (signOutDialog != null) signOutDialog.dismiss();
    }

    @Override public void cloudSyncChanged() {
        if (!CloudSyncRunner.running()) reload();
        else if (CLOUD.equals(activity.page())) activity.showPage(CLOUD);
    }

    private void reload() {
        activity.io.execute(() -> {
            CloudAccount stored = CloudAccount.load(activity);
            CloudSyncState last = stored == null ? null
                    : CloudSyncState.load(activity.getFilesDir(), stored.fingerprint());
            Map<String, String> names = new HashMap<>();
            try {
                for (RomLibrary.Entry entry : RomLibrary.list(activity.getFilesDir()))
                    names.put(entry.key, RomLibrary.displayTitle(entry.title));
            } catch (IOException unreadable) {
                // Conflicts then name their game by its console only.
            }
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                accountRead = true;
                account = stored;
                state = last;
                titles.clear();
                titles.putAll(names);
                if (CLOUD.equals(activity.page())) activity.showPage(CLOUD);
            });
        });
    }

    void cloudPage() {
        if (!accountRead) widgets.add(widgets.text("Lecture du compte…", 13, theme.muted), 0);
        else if (account == null) signInForm();
        else accountCard();
        if (error != null) {
            TextView shown = widgets.text(error, 12, ERROR);
            shown.setTag("cloud.error");
            widgets.add(shown, 10);
        }
        if (account != null && state != null) conflicts();
    }

    private void signInForm() {
        widgets.add(widgets.text("Retrouvez vos parties sur tous vos appareils : RavenEmu envoie les sauvegardes "
                + "de cartouche de vos jeux dans votre Google Drive ou dans un dossier WebDAV, comme RetroArch.",
                13, theme.muted), 0);
        googleChoice();
        widgets.group("Dossier WebDAV");
        widgets.add(widgets.text("Nextcloud, ownCloud, kDrive, Koofr, pCloud ou un NAS le proposent. Créez d'abord "
                + "un dossier dédié, par exemple RavenEmu, puis copiez son adresse WebDAV.", 13, theme.muted), 0);
        EditText folder = widgets.field("Adresse WebDAV du dossier (https://…)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        folder.setText(typedFolder);
        EditText user = widgets.field("Identifiant",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        user.setText(typedUser);
        EditText password = widgets.field("Mot de passe d'application",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // A password field otherwise shows its hint in monospace.
        password.setTypeface(Typeface.DEFAULT);
        Button connect = widgets.button(signingIn ? "Connexion…" : "Se connecter");
        connect.setTextColor(theme.background);
        connect.setBackground(widgets.shape(theme.accent));
        Runnable enable = () -> {
            boolean ready = !signingIn && !folder.getText().toString().trim().isEmpty()
                    && !user.getText().toString().trim().isEmpty() && password.getText().length() > 0;
            connect.setEnabled(ready);
            connect.setAlpha(ready || signingIn ? 1f : 0.5f);
        };
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                typedFolder = folder.getText().toString();
                typedUser = user.getText().toString();
                enable.run();
            }
        };
        folder.addTextChangedListener(watcher);
        user.addTextChangedListener(watcher);
        password.addTextChangedListener(watcher);
        folder.setEnabled(!signingIn);
        user.setEnabled(!signingIn);
        password.setEnabled(!signingIn);
        enable.run();
        connect.setOnClickListener(view -> {
            String address = folder.getText().toString().trim();
            String name = user.getText().toString().trim();
            // A pasted password often brings a line break, which no password
            // holds; its spaces may be its own, and are kept.
            String secret = password.getText().toString().replaceAll("^[\\r\\n]+|[\\r\\n]+$", "");
            password.setText("");
            if (address.isEmpty() || name.isEmpty() || secret.isEmpty() || signingIn) return;
            signIn(address, name, secret);
        });
        widgets.add(folder, 12);
        widgets.add(user, 10);
        widgets.add(password, 10);
        widgets.add(connect, 12);
        widgets.add(widgets.text("Seules les sauvegardes de cartouche (.srm) sont envoyées : jamais les ROMs, le "
                + "BIOS ni les états. Le mot de passe est chiffré sur cet appareil ; préférez un mot de passe "
                + "d'application, que votre cloud permet de révoquer.", 11, theme.muted), 8);
    }

    /** Google Drive: Google's screens choose the account; RavenEmu then sees
     * only the folder it creates there. */
    private void googleChoice() {
        widgets.group("Google Drive");
        widgets.add(widgets.text("RavenEmu crée le dossier « RavenEmu » dans votre Drive et n'accède qu'aux "
                + "fichiers qu'il y a créés. Demande les services Google Play.", 13, theme.muted), 0);
        Button connect = widgets.button(googleSigningIn ? "Connexion…" : "Se connecter avec Google");
        connect.setTag("cloud.google");
        connect.setTextColor(theme.background);
        connect.setBackground(widgets.shape(theme.accent));
        connect.setEnabled(!signingIn);
        connect.setAlpha(signingIn && !googleSigningIn ? 0.5f : 1f);
        connect.setOnClickListener(view -> {
            if (signingIn) return;
            signingIn = googleSigningIn = true;
            error = null;
            activity.showPage(CLOUD);
            google.start();
        });
        widgets.add(connect, 10);
    }

    /** The folder answers and can hold the list before anything is stored. */
    private void signIn(String typed, String user, String password) {
        String folder = WebDavHttp.folderAddress(typed);
        if (folder == null) {
            error = "Adresse invalide : elle commence par https:// et désigne un dossier.";
            activity.showPage(CLOUD);
            return;
        }
        signingIn = true;
        error = null;
        activity.showPage(CLOUD);
        activity.network.execute(() -> {
            String failure = CloudSync.verify(new WebDavHttp(folder, user, password, CloudSyncRunner.userAgent()));
            CloudAccount signedIn = new CloudAccount(folder, user, password, true);
            if (failure == null) {
                try {
                    signedIn.save(activity);
                } catch (IOException | GeneralSecurityException unsaved) {
                    failure = "Le mot de passe n'a pas pu être chiffré sur cet appareil : réessayez plus tard.";
                }
            }
            final String shown = failure;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                signingIn = false;
                error = shown;
                if (shown == null) {
                    account = signedIn;
                    state = null;
                    typedFolder = "";
                    typedUser = "";
                    CloudSyncRunner.now(activity);
                }
                if (CLOUD.equals(activity.page())) activity.showPage(CLOUD);
            });
        });
    }

    private void accountCard() {
        LinearLayout card = widgets.column();
        GradientDrawable surface = widgets.shape(theme.surface);
        surface.setCornerRadius(widgets.dp(14));
        card.setBackground(surface);
        card.setPadding(widgets.dp(14), widgets.dp(14), widgets.dp(14), widgets.dp(14));
        boolean drive = account.isGoogleDrive();
        TextView host = widgets.text(drive ? "Google Drive" : WebDavHttp.host(account.folder), 17, theme.text);
        host.setTypeface(null, Typeface.BOLD);
        card.addView(host);
        card.addView(widgets.text(drive ? account.user + " · dossier RavenEmu" : "@" + account.user, 12,
                theme.muted));
        widgets.add(card, widgets.text(status(), 12, theme.accent), 8);
        widgets.add(card, 0);
        widgets.settingSwitch("Synchroniser automatiquement",
                "Au lancement de RavenEmu et à la fermeture d'un jeu.", account.automatic, enabled -> {
                    CloudAccount updated = account.withAutomatic(enabled);
                    account = updated;
                    activity.io.execute(() -> {
                        try {
                            updated.save(activity);
                        } catch (IOException | GeneralSecurityException unsaved) {
                            // Kept for this screen; read again from the file next time.
                        }
                    });
                });
        LinearLayout actions = new LinearLayout(activity);
        boolean busy = CloudSyncRunner.running() || signingOut;
        Button now = widgets.outlined(CloudSyncRunner.running() ? "Synchronisation…" : "Synchroniser maintenant");
        now.setEnabled(!busy);
        now.setOnClickListener(view -> {
            error = null;
            CloudSyncRunner.now(activity);
        });
        Button signOut = widgets.outlined("Déconnexion");
        signOut.setContentDescription("Se déconnecter du cloud");
        signOut.setEnabled(!busy);
        signOut.setOnClickListener(view -> confirmSignOut());
        actions.addView(now, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.setMarginStart(widgets.dp(8));
        actions.addView(signOut, second);
        widgets.add(actions, 12);
    }

    private String status() {
        if (CloudSyncRunner.running()) return "Synchronisation en cours…";
        if (state == null || state.lastSync == 0) return "Pas encore synchronisé";
        String when = "Dernière synchronisation : " + date(state.lastSync);
        if (state.refused) return when + "\n" + state.lastError + "\nSynchronisation automatique suspendue : "
                + "touchez « Synchroniser maintenant » une fois le problème réglé, ou reconnectez-vous.";
        if (!state.lastError.isEmpty()) return when + "\n" + state.lastError;
        if (state.lastUploaded == 0 && state.lastDownloaded == 0) return when + " · tout est à jour";
        return when + " · " + count(state.lastUploaded, "envoyée") + ", " + count(state.lastDownloaded, "reçue");
    }

    private static String count(int number, String participle) {
        return number + (number > 1 ? " sauvegardes " + participle + "s" : " sauvegarde " + participle);
    }

    /** Each game changed on both sides: the player keeps one, the other is kept aside. */
    private void conflicts() {
        if (state.conflicts.isEmpty()) return;
        widgets.group("Conflits à régler");
        widgets.add(widgets.text("Ces sauvegardes ont changé sur ce téléphone et dans le cloud depuis la dernière "
                + "synchronisation. Choisissez celle à garder : l'autre n'est pas effacée. Prise du cloud, la "
                + "sauvegarde de ce téléphone est mise de côté sur l'appareil ; gardée, celle du cloud y reste "
                + "parmi les versions précédentes.", 12, theme.muted), 8);
        boolean busy = CloudSyncRunner.running();
        for (CloudSyncState.Conflict conflict : state.conflicts) {
            String title = titles.containsKey(conflict.key) ? titles.get(conflict.key) : "Jeu retiré de la bibliothèque";
            LinearLayout block = widgets.column();
            TextView name = widgets.text(title, 14, theme.text);
            name.setTypeface(null, Typeface.BOLD);
            block.addView(name);
            widgets.add(block, widgets.text("Ce téléphone : " + date(conflict.phoneModified) + "\nCloud : "
                    + (conflict.cloudDevice.isEmpty() ? "" : conflict.cloudDevice + ", ")
                    + date(conflict.cloudModified), 12, theme.muted), 4);
            LinearLayout choices = new LinearLayout(activity);
            Button keep = widgets.rowButton("Garder ce téléphone", theme.accent);
            keep.setContentDescription("Garder la sauvegarde de ce téléphone pour " + title);
            keep.setEnabled(!busy);
            keep.setOnClickListener(view -> CloudSyncRunner.resolve(activity, conflict, true));
            Button take = widgets.rowButton("Prendre le cloud", theme.accent);
            take.setContentDescription("Prendre la sauvegarde du cloud pour " + title);
            take.setEnabled(!busy);
            take.setOnClickListener(view -> CloudSyncRunner.resolve(activity, conflict, false));
            choices.addView(keep, new LinearLayout.LayoutParams(-2, -2));
            choices.addView(take, new LinearLayout.LayoutParams(-2, -2));
            block.addView(choices);
            widgets.add(block, 12);
        }
    }

    private void confirmSignOut() {
        if (signingOut || account == null) return;
        if (signOutDialog != null && signOutDialog.isShowing()) return;
        String forgotten = account.isGoogleDrive()
                ? "Le compte Google est oublié sur cet appareil. Les sauvegardes restent sur ce téléphone et dans "
                + "votre Drive. L'accès de RavenEmu se retire depuis votre compte Google, rubrique Sécurité."
                : "L'adresse et le mot de passe sont oubliés sur cet appareil. Les sauvegardes restent sur ce "
                + "téléphone et dans le cloud.";
        signOutDialog = new AlertDialog.Builder(activity).setTitle("Se déconnecter du cloud ?")
                .setMessage(forgotten)
                .setPositiveButton("Se déconnecter", (dialog, which) -> signOut())
                .setNegativeButton("Annuler", null).create();
        signOutDialog.show();
        signOutDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(theme.accent);
        signOutDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(theme.secondary);
    }

    private void signOut() {
        if (signingOut) return;
        signingOut = true;
        activity.showPage(CLOUD);
        activity.io.execute(() -> {
            CloudAccount.clear(activity);
            CloudSyncState.clear(activity.getFilesDir());
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                signingOut = false;
                account = null;
                state = null;
                error = null;
                if (CLOUD.equals(activity.page())) activity.showPage(CLOUD);
            });
        });
    }

    private static String date(long millis) {
        if (millis <= 0) return "date inconnue";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.FRANCE).format(new Date(millis));
    }
}
