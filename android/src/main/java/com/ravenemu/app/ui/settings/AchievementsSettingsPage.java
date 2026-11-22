package com.ravenemu.app.ui.settings;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.platform.AchievementsHttp;
import com.ravenemu.app.platform.RetroUserAgent;
import com.ravenemu.app.settings.AchievementAccount;
import com.ravenemu.app.settings.AchievementSettings;
import com.ravenemu.app.settings.AwardJournal;
import com.ravenemu.app.settings.HardcorePolicy;
import com.ravenemu.app.ui.common.RavenTheme;
import java.io.IOException;
import java.security.GeneralSecurityException;

import static com.ravenemu.app.ui.settings.PlaybackSettingsActivity.RETRO;

/** Settings → RetroAchievements: the beta's account form or card
 * (RetroAchievementsContent.kt), then how the achievements are announced. */
final class AchievementsSettingsPage {
    private static final int ERROR = 0xffff928b;

    private final PlaybackSettingsActivity activity;
    private final SettingsWidgets widgets;
    private final RavenTheme theme;
    private AchievementSettings achievementSettings;
    private AlertDialog signOutDialog;
    /** Read from its sealed file once, then as signed in or out here. */
    private AchievementAccount account;
    /** signingOut: the account is being forgotten; until then neither button
     * answers, so "Réessayer" cannot save the token back after it. */
    private boolean accountRead, signingIn, signingOut;
    private String accountError, typedUser = "";
    private Bitmap avatar;

    AchievementsSettingsPage(PlaybackSettingsActivity activity, SettingsWidgets widgets) {
        this.activity = activity;
        this.widgets = widgets;
        this.theme = activity.theme();
        achievementSettings = AchievementSettings.load(activity);
    }

    /** The stored account, read once off the UI thread. */
    void start() {
        activity.io.execute(() -> {
            AchievementAccount stored = AchievementAccount.load(activity);
            // Stored but unreadable: said so, never shown as merely signed out.
            final String unreadable = stored == null && AchievementAccount.stored(activity)
                    ? AchievementAccount.UNREADABLE : null;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                accountRead = true;
                accountChanged(stored, unreadable);
            });
        });
    }

    /** The beta's RetroAchievements page: the account (RetroAchievementsContent.kt),
     * then how it notifies (SettingsScreen.kt). The game's achievement list,
     * its leaderboards and Hardcore come with the next tranche. */
    void retroPage() {
        if (!accountRead) widgets.add(widgets.text("Lecture du compte…", 13, theme.muted), 0);
        else if (account == null) loginForm();
        else accountCard();
        if (accountError != null) {
            TextView error = widgets.text(accountError, 12, ERROR);
            error.setTag("achievements.error");
            widgets.add(error, 10);
        }
        widgets.group("Notifications RetroAchievements");
        widgets.settingSwitch("Déblocages et bilan", "Afficher les succès obtenus et le jeu terminé.",
                achievementSettings.unlockPopups, value -> updateAchievements(achievementSettings.withUnlockPopups(value)));
        widgets.settingSwitch("Progression des succès", "Afficher en bas à droite l'avancée mesurée d'un succès.",
                achievementSettings.progressPopups,
                value -> updateAchievements(achievementSettings.withProgressPopups(value)));
        widgets.settingSwitch("Son des succès", "Le cri du corbeau, joué avec le son du jeu pour chaque succès et le "
                        + "jeu terminé.",
                achievementSettings.sound, value -> updateAchievements(achievementSettings.withSound(value)));
        widgets.description("Position des popups",
                "Les six emplacements de RetroArch, pour le lancement d'un jeu, les succès et les avis. "
                        + "La progression, les défis et les classements restent en bas à droite.");
        widgets.choices(new String[]{"En haut à gauche", "En haut au centre (défaut)", "En haut à droite",
                        "En bas à gauche", "En bas au centre", "En bas à droite"},
                AchievementSettings.POSITIONS.toArray(new String[0]), achievementSettings.position,
                value -> updateAchievements(achievementSettings.withPosition(value)));
        widgets.slider("Durée des déblocages", achievementSettings.popupSeconds, AchievementSettings.POPUP_MIN,
                AchievementSettings.POPUP_MAX, " s", value -> updateAchievements(achievementSettings.withPopupSeconds(value)));
        widgets.slider("Durée des progressions", achievementSettings.progressSeconds, AchievementSettings.PROGRESS_MIN,
                AchievementSettings.PROGRESS_MAX, " s",
                value -> updateAchievements(achievementSettings.withProgressSeconds(value)));
        // As in the beta: no switch until RetroAchievements approves RavenEmu.
        if (HardcorePolicy.approved()) {
            widgets.group("Mode Hardcore");
            widgets.settingSwitch("Mode Hardcore", "Succès comptés en Hardcore. Le chargement des sauvegardes d'état est "
                            + "désactivé et les pauses sont limitées. L'activer redémarre le jeu en cours.",
                    achievementSettings.hardcore, value -> updateAchievements(achievementSettings.withHardcore(value)));
        } else {
            widgets.description("Mode Hardcore indisponible", "L'activation attend la validation de RavenEmu par "
                    + "RetroAchievements. Le mode Softcore reste disponible.");
        }
        widgets.add(widgets.text("Mode Softcore · SDK officiel " + sdkVersion(), 11, theme.muted), 14);
    }

    /** The beta's RetroLoginForm. The password is never kept: the field is
     * emptied as it is sent, and only the token is stored. */
    private void loginForm() {
        widgets.add(widgets.text("Connectez votre compte pour obtenir les succès officiels pendant vos parties Game Boy, Game "
                + "Boy Color et Game Boy Advance. Mode normal (Softcore).", 13, theme.muted), 0);
        EditText user = widgets.field("Nom d'utilisateur", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        user.setText(typedUser);
        user.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        EditText password = widgets.field("Mot de passe", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button signIn = widgets.button(signingIn ? "Connexion…" : "Se connecter");
        signIn.setTextColor(theme.background);
        signIn.setBackground(widgets.shape(theme.accent));
        Runnable enable = () -> {
            boolean ready = !signingIn && !user.getText().toString().trim().isEmpty()
                    && password.getText().length() > 0;
            signIn.setEnabled(ready);
            signIn.setAlpha(ready || signingIn ? 1f : 0.5f);
        };
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                typedUser = user.getText().toString();
                enable.run();
            }
        };
        user.addTextChangedListener(watcher);
        password.addTextChangedListener(watcher);
        user.setEnabled(!signingIn);
        password.setEnabled(!signingIn);
        enable.run();
        signIn.setOnClickListener(view -> {
            String name = user.getText().toString().trim();
            String secret = password.getText().toString();
            password.setText("");
            if (name.isEmpty() || secret.isEmpty() || signingIn) return;
            signIn(name, secret, null);
        });
        widgets.add(user, 12);
        widgets.add(password, 10);
        widgets.add(signIn, 12);
        widgets.add(widgets.text("Le mot de passe n'est pas enregistré. Seul le jeton est conservé, chiffré sur cet appareil.",
                11, theme.muted), 8);
    }

    /** The beta's RetroAccountSummary and RetroAccountActions. */
    private void accountCard() {
        LinearLayout card = widgets.column();
        GradientDrawable surface = widgets.shape(theme.surface);
        surface.setCornerRadius(widgets.dp(14));
        card.setBackground(surface);
        card.setPadding(widgets.dp(14), widgets.dp(14), widgets.dp(14), widgets.dp(14));
        LinearLayout identity = new LinearLayout(activity);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        if (avatar != null) {
            ImageView picture = new ImageView(activity);
            picture.setImageBitmap(avatar);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            picture.setContentDescription("Avatar RetroAchievements");
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(widgets.dp(58), widgets.dp(58));
            size.setMarginEnd(widgets.dp(12));
            identity.addView(picture, size);
        }
        LinearLayout names = widgets.column();
        TextView name = widgets.text(account.displayName, 17, theme.text);
        name.setTypeface(null, Typeface.BOLD);
        names.addView(name);
        names.addView(widgets.text("@" + account.user, 12, theme.muted));
        identity.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(identity);
        widgets.add(card, widgets.text("Softcore : " + account.softcorePoints + " points  ·  Hardcore : " + account.hardcorePoints
                + " points", 12, theme.accent), 6);
        widgets.add(card, widgets.text("Les succès des jeux suivent ce compte à chaque lancement.", 12, theme.muted), 6);
        widgets.add(card, 0);
        LinearLayout actions = new LinearLayout(activity);
        Button retry = widgets.outlined(signingIn ? "Connexion…" : "Réessayer");
        retry.setContentDescription("Actualiser le compte RetroAchievements");
        retry.setEnabled(!signingIn && !signingOut);
        retry.setOnClickListener(view -> signIn(account.user, null, account.token));
        Button signOut = widgets.outlined("Déconnexion");
        signOut.setContentDescription("Se déconnecter de RetroAchievements");
        signOut.setEnabled(!signingIn && !signingOut);
        signOut.setOnClickListener(view -> confirmSignOut());
        actions.addView(retry, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.setMarginStart(widgets.dp(8));
        actions.addView(signOut, second);
        widgets.add(actions, 12);
    }

    /** With a password, a new account; with the token, the stored one refreshed. */
    private void signIn(String user, String password, String token) {
        if (signingIn || signingOut) return;
        signingIn = true;
        accountError = null;
        if (RETRO.equals(activity.page())) activity.showPage(RETRO);
        activity.network.execute(() -> {
            AchievementsHttp http = new AchievementsHttp(RetroUserAgent.of(activity));
            AchievementLogin.Result result = AchievementLogin.run(http, user, password, token);
            String error = result.error;
            if (result.account != null) {
                try {
                    result.account.save(activity);
                } catch (IOException | GeneralSecurityException unsaved) {
                    error = "Connecté, mais le jeton n'a pas pu être chiffré sur cet appareil : "
                            + "reconnectez-vous plus tard.";
                }
            }
            Bitmap picture = result.account == null ? null : http.badge(result.account.avatarUrl);
            final String failure = error;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                signingIn = false;
                if (picture != null) avatar = picture;
                accountChanged(result.account != null && failure == null ? result.account : account, failure);
            });
        });
    }

    /** The unlocks still waiting are counted off the UI thread, then the
     * player confirms: they go with the token. */
    private void confirmSignOut() {
        if (signingIn || signingOut || account == null) return;
        final AchievementAccount shown = account;
        activity.io.execute(() -> {
            final int pending = AwardJournal.open(activity).forAccount(shown.user).size();
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || account != shown || signingIn || signingOut) return;
                if (signOutDialog != null && signOutDialog.isShowing()) return;
                signOutDialog = new AlertDialog.Builder(activity).setTitle(AchievementAccount.SIGN_OUT_TITLE)
                        .setMessage(AchievementAccount.signOutMessage(pending))
                        .setPositiveButton("Se déconnecter", (dialog, which) -> signOut())
                        .setNegativeButton("Annuler", null).create();
                signOutDialog.show();
                signOutDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(theme.accent);
                signOutDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(theme.secondary);
            });
        });
    }

    /** Forgets the token on activity device; RetroAchievements is not told. */
    private void signOut() {
        if (signingIn || signingOut) return;
        signingOut = true;
        if (RETRO.equals(activity.page())) activity.showPage(RETRO);
        activity.io.execute(() -> {
            // The unlocks still waiting go with the account, as in the beta.
            AwardJournal.clear(activity);
            AchievementAccount.clear(activity);
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                signingOut = false;
                avatar = null;
                accountChanged(null, null);
            });
        });
    }

    private void accountChanged(AchievementAccount updated, String error) {
        account = updated;
        accountError = error;
        if (account != null && avatar == null && !account.avatarUrl.isEmpty()) loadAvatar(account.avatarUrl);
        if (RETRO.equals(activity.page())) activity.showPage(RETRO);
    }

    private void loadAvatar(String url) {
        activity.network.execute(() -> {
            Bitmap picture = new AchievementsHttp(RetroUserAgent.of(activity)).badge(url);
            if (picture != null) activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || account == null || !url.equals(account.avatarUrl)) return;
                avatar = picture;
                if (RETRO.equals(activity.page())) activity.showPage(RETRO);
            });
        });
    }

    private void updateAchievements(AchievementSettings updated) {
        achievementSettings = updated;
        achievementSettings.save(activity);
    }

    /** "rcheevos/x.y", from the native library; plain "rcheevos" without it. */
    private static String sdkVersion() {
        try {
            return NativeAchievements.sdkVersion();
        } catch (LinkageError withoutNativeLibrary) {
            return "rcheevos";
        }
    }

    void dismiss() {
        if (signOutDialog != null) signOutDialog.dismiss();
    }
}
