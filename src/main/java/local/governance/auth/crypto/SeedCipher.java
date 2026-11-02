package local.governance.auth.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

@Component
public class SeedCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SeedKeyProvider keys;
    private final local.governance.config.TestFaults faults;

    public SeedCipher(SeedKeyProvider keys, local.governance.config.TestFaults faults) {
        this.keys = keys;
        this.faults = faults;
    }

    public record Encrypted(String keyId, byte[] nonce, byte[] ciphertext) {}

    public Encrypted encrypt(UUID accountId, UUID seedId, String seed) {
        if (faults.enabled("totp-key-missing")) {
            throw new KeyUnavailableException();
        }
        SecretKey key = keys.activeKey();
        if (key == null) {
            throw new KeyUnavailableException();
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(accountId, seedId));
            byte[] ciphertext = cipher.doFinal(seed.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(keys.activeKeyId(), nonce, ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("seed encryption failed", exception);
        }
    }

    public String decrypt(UUID accountId, UUID seedId, String keyId, byte[] nonce, byte[] ciphertext) {
        if (faults.enabled("totp-key-missing")) {
            throw new KeyUnavailableException();
        }
        SecretKey key = keys.key(keyId);
        if (key == null) {
            throw new KeyUnavailableException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(accountId, seedId));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new KeyUnavailableException();
        }
    }

    private byte[] aad(UUID accountId, UUID seedId) {
        return (accountId + "|" + seedId + "|1").getBytes(StandardCharsets.UTF_8);
    }

    public static class KeyUnavailableException extends RuntimeException {
        public KeyUnavailableException() {
            super("TOTP key is unavailable");
        }
    }
}
