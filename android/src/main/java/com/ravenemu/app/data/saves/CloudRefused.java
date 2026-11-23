package com.ravenemu.app.data.saves;

import java.io.IOException;

/** The cloud refused this phone: a refused password, an access withdrawn, or
 * connections blocked for a while after refusals (HTTP 429, as Koofr does
 * after several wrong passwords). Automatic syncs then stop until the player
 * syncs by hand or signs in again: retrying by itself would only prolong
 * such a block. */
public class CloudRefused extends IOException {
    public CloudRefused(String message) { super(message); }

    /** HTTP 429: blocked for `waitSeconds`, or for an unknown time (negative). */
    public static CloudRefused blocked(long waitSeconds) {
        String wait;
        if (waitSeconds <= 0 || waitSeconds > 24 * 3600) wait = "patientez avant de réessayer";
        else {
            long minutes = (waitSeconds + 59) / 60;
            wait = "réessayez dans " + (minutes <= 1 ? "une minute" : minutes + " minutes");
        }
        return new CloudRefused("Le cloud bloque les connexions pour un moment, souvent après plusieurs mots de "
                + "passe refusés : " + wait + ", avec un mot de passe d'application de votre cloud.");
    }
}
