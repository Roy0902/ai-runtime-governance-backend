package local.governance.provider.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.spec.MGF1ParameterSpec;
import org.springframework.stereotype.Component;

/**
 * Hybrid envelope encryption for provider secrets.
 *
 * <p>A random 256-bit data key encrypts the secret with AES-GCM. The data key is
 * then wrapped with RSA-OAEP. Rotating the RSA key only requires rewrapping the
 * data keys; the secrets stay as they are. The AAD binds each sealed value to
 * the owning account and provider, so a row cannot be moved between users or
 * provider slots.
 */
@Component
public class EnvelopeCipher {

    private static final int DATA_KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    public record Sealed(String keyId, byte[] nonce, byte[] ciphertext, byte[] wrappedKey) {}

    private final CredentialKeyProvider keys;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeCipher(CredentialKeyProvider keys) {
        this.keys = keys;
    }

    public Sealed seal(String owner, String provider, String secret) {
        byte[] dataKey = new byte[DATA_KEY_BYTES];
        random.nextBytes(dataKey);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            aes.updateAAD(aad(owner, provider));
            byte[] ciphertext = aes.doFinal(secret.getBytes(StandardCharsets.UTF_8));

            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsa.init(Cipher.WRAP_MODE, keys.publicKey(), oaep());
            byte[] wrapped = rsa.wrap(new SecretKeySpec(dataKey, "AES"));

            return new Sealed(keys.keyId(), nonce, ciphertext, wrapped);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("provider secret encryption failed", exception);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    public String open(String owner, String provider, Sealed sealed) {
        if (!keys.keyId().equals(sealed.keyId())) {
            throw new IllegalStateException("provider secret was sealed with an unavailable key " + sealed.keyId());
        }
        SecretKeySpec dataKey = null;
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsa.init(Cipher.UNWRAP_MODE, keys.privateKey(), oaep());
            dataKey = (SecretKeySpec) rsa.unwrap(sealed.wrappedKey(), "AES", Cipher.SECRET_KEY);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            aes.updateAAD(aad(owner, provider));
            return new String(aes.doFinal(sealed.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("provider secret decryption failed", exception);
        } finally {
            if (dataKey != null) {
                byte[] encoded = dataKey.getEncoded();
                if (encoded != null) {
                    Arrays.fill(encoded, (byte) 0);
                }
            }
        }
    }

    private static OAEPParameterSpec oaep() {
        return new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    private static byte[] aad(String owner, String provider) {
        return (owner + "\u0000" + provider).getBytes(StandardCharsets.UTF_8);
    }
}
