package com.ravenemu.app.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Robolectric has no Android Keystore: a reversible stand-in that still
 * keeps the plain text out of the file, and refuses what it did not seal. */
@Implements(value = KeystoreSealer.class, isInAndroidSdk = false)
public class ClearSealer {
    private static final byte[] MAGIC = "TEST-SEALED:".getBytes(StandardCharsets.US_ASCII);

    @Implementation protected static byte[] seal(byte[] plain) {
        byte[] sealed = Arrays.copyOf(MAGIC, MAGIC.length + plain.length);
        for (int i = 0; i < plain.length; i++) sealed[MAGIC.length + i] = (byte)(plain[i] ^ 0x5a);
        return sealed;
    }

    @Implementation protected static byte[] open(byte[] sealed) throws GeneralSecurityException {
        if (sealed == null || sealed.length < MAGIC.length
                || !Arrays.equals(Arrays.copyOf(sealed, MAGIC.length), MAGIC))
            throw new GeneralSecurityException("Not sealed by this key");
        byte[] plain = new byte[sealed.length - MAGIC.length];
        for (int i = 0; i < plain.length; i++) plain[i] = (byte)(sealed[MAGIC.length + i] ^ 0x5a);
        return plain;
    }
}
