package com.ravenemu.app.platform;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM under a key that never leaves the Android Keystore, as the beta
 * keeps its RetroAchievements token (AchievementAccount.kt). The sealed form
 * is the 12-byte nonce, then the ciphertext and its tag. */
public final class KeystoreSealer {
    /** This preview's own key: com.ravenemu.dev never shares the beta's. */
    private static final String ALIAS = "RavenEmu.Native.RetroAchievements.v1";
    private static final int NONCE_BYTES = 12, TAG_BITS = 128;

    private KeystoreSealer() {}

    public static byte[] seal(byte[] plain) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != NONCE_BYTES) throw new GeneralSecurityException("Unexpected GCM nonce");
        byte[] body = cipher.doFinal(plain);
        byte[] sealed = Arrays.copyOf(nonce, NONCE_BYTES + body.length);
        System.arraycopy(body, 0, sealed, NONCE_BYTES, body.length);
        return sealed;
    }

    public static byte[] open(byte[] sealed) throws GeneralSecurityException {
        if (sealed == null || sealed.length <= NONCE_BYTES) throw new GeneralSecurityException("Sealed data too short");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
    }

    private static SecretKey key() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
        } catch (IOException unreadable) {
            throw new GeneralSecurityException("Android Keystore unavailable", unreadable);
        }
        Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey)existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
