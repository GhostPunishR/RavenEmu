package com.ravenemu.app.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Robolectric has no Android Keystore: a reversible stand-in that still
 * keeps the plain text out of the file, and refuses what it did not seal or
 * sealed for another file. The one-argument forms reach these. */
@Implements(value = KeystoreSealer.class, isInAndroidSdk = false)
public class ClearSealer {
    private static final byte[] MAGIC = "TEST-SEALED:".getBytes(StandardCharsets.US_ASCII);

    @Implementation protected static byte[] seal(byte[] plain, byte[] context) {
        byte[] bound = bound(context);
        byte[] sealed = Arrays.copyOf(MAGIC, MAGIC.length + bound.length + plain.length);
        System.arraycopy(bound, 0, sealed, MAGIC.length, bound.length);
        for (int i = 0; i < plain.length; i++) sealed[MAGIC.length + bound.length + i] = (byte)(plain[i] ^ 0x5a);
        return sealed;
    }

    @Implementation protected static byte[] open(byte[] sealed, byte[] context) throws GeneralSecurityException {
        byte[] bound = bound(context);
        int header = MAGIC.length + bound.length;
        if (sealed == null || sealed.length < header
                || !Arrays.equals(Arrays.copyOf(sealed, MAGIC.length), MAGIC)
                || !Arrays.equals(Arrays.copyOfRange(sealed, MAGIC.length, header), bound))
            throw new GeneralSecurityException("Not sealed by this key for this file");
        byte[] plain = new byte[sealed.length - header];
        for (int i = 0; i < plain.length; i++) plain[i] = (byte)(sealed[header + i] ^ 0x5a);
        return plain;
    }

    /** The context, as a fixed-length mark: none and each file's differ. */
    private static byte[] bound(byte[] context) {
        return String.format("%08x;", context == null ? 0 : Arrays.hashCode(context) | 1)
                .getBytes(StandardCharsets.US_ASCII);
    }
}
