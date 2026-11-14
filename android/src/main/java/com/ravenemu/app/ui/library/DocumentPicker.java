package com.ravenemu.app.ui.library;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.widget.Toast;

/** The system's document picker, for a ROM, a cover, a folder or a save.
 * Some devices (Android Go, TV, DocumentsUI turned off) have none: the
 * player is told, as in the settings, instead of the app stopping. */
final class DocumentPicker {
    static final String MISSING = "Aucune application pour choisir un fichier ou un dossier";

    private DocumentPicker() {}

    /** False when no application answers the request. UI thread. */
    static boolean start(Activity host, Intent request, int code) {
        try {
            host.startActivityForResult(request, code);
            return true;
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(host, MISSING, Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
