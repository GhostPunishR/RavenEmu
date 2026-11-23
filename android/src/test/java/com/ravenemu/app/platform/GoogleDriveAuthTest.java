package com.ravenemu.app.platform;

import static org.junit.Assert.*;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import com.ravenemu.app.data.saves.CloudRefused;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** How Google Play services' failures reach the sync: what only the player can
 * settle stops the automatic syncs, the network does not. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class GoogleDriveAuthTest {
    private static IOException failure(int status) {
        return GoogleDriveAuth.failure(new ApiException(new Status(status)));
    }

    @Test public void signingInAgainIsARefusal() {
        IOException failure = failure(CommonStatusCodes.SIGN_IN_REQUIRED);
        assertTrue(failure instanceof CloudRefused);
        assertEquals(GoogleDriveAuth.RECONNECT, failure.getMessage());
    }

    @Test public void anUndeclaredOAuthClientIsARefusal() {
        IOException failure = failure(CommonStatusCodes.DEVELOPER_ERROR);
        assertTrue(failure instanceof CloudRefused);
        assertTrue(failure.getMessage(), failure.getMessage().contains("identifiant OAuth"));
    }

    @Test public void googlesClosedScreenSaysWhyWithItsCode() {
        IOException failed = failure(GoogleDriveAuth.SIGN_IN_FAILED);
        assertTrue(failed instanceof CloudRefused);
        assertTrue(failed.getMessage(), failed.getMessage().contains("identifiant OAuth Android, code 12500"));
        assertEquals("Connexion à Google annulée", failure(GoogleDriveAuth.SIGN_IN_CANCELLED).getMessage());
        assertEquals("Connexion à Google annulée", failure(CommonStatusCodes.CANCELED).getMessage());
        assertEquals("Google a refusé la connexion (code 8)", failure(CommonStatusCodes.INTERNAL_ERROR).getMessage());
    }

    @Test public void theNetworkAndUnknownFailuresAreTriedAgainLater() {
        IOException network = failure(CommonStatusCodes.NETWORK_ERROR);
        assertFalse(network instanceof CloudRefused);
        assertEquals("Réseau indisponible : réessayez une fois connecté", network.getMessage());
        assertFalse(failure(CommonStatusCodes.INTERNAL_ERROR) instanceof CloudRefused);
        assertFalse(GoogleDriveAuth.failure(new IllegalStateException("x")) instanceof CloudRefused);
    }
}
