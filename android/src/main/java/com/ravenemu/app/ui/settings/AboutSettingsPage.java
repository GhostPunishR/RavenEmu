package com.ravenemu.app.ui.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.ui.common.RavenTheme;

/** Settings → À propos: who makes RavenEmu, its documents, licences and source. */
final class AboutSettingsPage {
    private static final String REPOSITORY = "https://github.com/GhostPunishR/RavenEmu";

    private final PlaybackSettingsActivity activity;
    private final SettingsWidgets widgets;
    private final RavenTheme theme;

    AboutSettingsPage(PlaybackSettingsActivity activity, SettingsWidgets widgets) {
        this.activity = activity;
        this.widgets = widgets;
        this.theme = activity.theme();
    }

    /** The beta's À propos: who makes RavenEmu, its licences and its source.
     * The terms and the privacy policy are read online until the native
     * reader carries them. */
    void aboutPage() {
        TextView name = widgets.text("RavenEmu", 18, theme.text);
        name.setTypeface(null, Typeface.BOLD);
        widgets.add(name, 0);
        widgets.add(widgets.text("Par GhostPunishR · version native expérimentale Java / C / C++", 13, theme.muted), 4);
        widgets.group("Informations légales");
        // Read inside the app, as in the beta: the repository's own documents.
        aboutRow("Conditions d'utilisation", "Ce que RavenEmu fournit, et votre responsabilité", RavenIcon.Glyph.CHEVRON,
                view -> openDocument(LegalDocumentActivity.TERMS));
        aboutRow("Politique de confidentialité", "Données échangées et services tiers", RavenIcon.Glyph.CHEVRON,
                view -> openDocument(LegalDocumentActivity.PRIVACY));
        widgets.group("Licences et crédits");
        aboutRow("Licences et crédits", "Crédits, GPLv3, AGPLv3, MIT et Apache 2, lus dans l'application",
                RavenIcon.Glyph.CHEVRON,
                view -> activity.startActivity(new Intent(activity, LicensesActivity.class)));
        widgets.group("Code source");
        aboutRow("RavenEmu", "github.com/GhostPunishR/RavenEmu", RavenIcon.Glyph.EXTERNAL, view -> openLink(REPOSITORY));
        aboutRow("RavenCore", "github.com/GhostPunishR/RavenCore", RavenIcon.Glyph.EXTERNAL,
                view -> openLink("https://github.com/GhostPunishR/RavenCore"));
    }

    private void openDocument(String document) {
        activity.startActivity(new Intent(activity, LegalDocumentActivity.class)
                .putExtra(LegalDocumentActivity.EXTRA_DOCUMENT, document));
    }

    private void aboutRow(String label, String summary, RavenIcon.Glyph trailing, View.OnClickListener open) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(widgets.dp(56));
        row.setPadding(0, widgets.dp(8), 0, widgets.dp(8));
        LinearLayout description = widgets.column();
        description.addView(widgets.text(label, 15, theme.text));
        widgets.add(description, widgets.text(summary, 12, theme.muted), 3);
        row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(RavenIcon.image(activity, trailing, theme.accent, 20), new LinearLayout.LayoutParams(widgets.dp(24), widgets.dp(24)));
        row.setContentDescription(label + ". " + summary);
        row.setOnClickListener(open);
        widgets.add(row, 6);
    }

    private void openLink(String address) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(activity, "Aucune application pour ouvrir ce lien", Toast.LENGTH_LONG).show();
        }
    }
}
