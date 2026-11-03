package local.governance.authentication.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

@Component
public class PasswordHashService {

    public enum Verification {
        CURRENT,
        PREDECESSOR,
        FAILED
    }

    private static final String ALGORITHM = "pbkdf2-sha256";
    private static final String JCA_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int CURRENT_ITERATIONS = 210_000;
    private static final int PREDECESSOR_ITERATIONS = 100_000;

    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        return encode(password, CURRENT_ITERATIONS);
    }

    public String hashPredecessorForTest(String password) {
        return encode(password, PREDECESSOR_ITERATIONS);
    }

    private String encode(String password, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, iterations);
        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        String encoded = "$" + ALGORITHM + "$i=" + iterations + "$"
                + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(hash, (byte) 0);
        return encoded;
    }

    public Verification verify(String password, String encoded) {
        if (encoded == null || !encoded.startsWith("$" + ALGORITHM + "$")) {
            return Verification.FAILED;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 5 || !parts[2].startsWith("i=")) {
            return Verification.FAILED;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[2].substring(2));
        } catch (RuntimeException exception) {
            return Verification.FAILED;
        }
        boolean current = iterations == CURRENT_ITERATIONS;
        boolean predecessor = iterations == PREDECESSOR_ITERATIONS;
        if (!current && !predecessor) {
            return Verification.FAILED;
        }
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] salt;
        byte[] expected;
        try {
            salt = decoder.decode(parts[3]);
            expected = decoder.decode(parts[4]);
        } catch (IllegalArgumentException exception) {
            return Verification.FAILED;
        }
        if (salt.length != SALT_BYTES || expected.length != HASH_BYTES) {
            return Verification.FAILED;
        }
        byte[] actual = derive(password, salt, iterations);
        if (!MessageDigest.isEqual(expected, actual)) {
            return Verification.FAILED;
        }
        return current ? Verification.CURRENT : Verification.PREDECESSOR;
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        char[] characters = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(characters, salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance(JCA_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is unavailable", exception);
        } finally {
            spec.clearPassword();
            Arrays.fill(characters, '\0');
        }
    }
}
