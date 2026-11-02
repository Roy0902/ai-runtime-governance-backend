package local.governance.auth.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class Totp {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long STEP_SECONDS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public long step(Instant instant) {
        return instant.getEpochSecond() / STEP_SECONDS;
    }

    public OptionalLong verify(String secret, String code, Instant instant) {
        if (code == null || !code.matches("[0-9]{6}")) {
            return OptionalLong.empty();
        }
        long current = step(instant);
        for (long offset = -1; offset <= 1; offset += 1) {
            long candidate = current + offset;
            if (code.equals(code(secret, candidate))) {
                return OptionalLong.of(candidate);
            }
        }
        return OptionalLong.empty();
    }

    public String code(String secret, long step) {
        byte[] key = base32Decode(secret);
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(step);
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] digest = mac.doFinal(buffer.array());
            int start = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[start] & 0x7f) << 24)
                    | ((digest[start + 1] & 0xff) << 16)
                    | ((digest[start + 2] & 0xff) << 8)
                    | (digest[start + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable", exception);
        }
    }

    public String code(String secret, Instant instant) {
        return code(secret, step(instant));
    }

    private static String base32Encode(byte[] bytes) {
        StringBuilder output = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                output.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            output.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return output.toString();
    }

    private static byte[] base32Decode(String value) {
        String clean = value.replace(" ", "").replace("-", "").toUpperCase();
        int buffer = 0;
        int bits = 0;
        byte[] output = new byte[clean.length() * 5 / 8];
        int index = 0;
        for (char character : clean.toCharArray()) {
            int digit = ALPHABET.indexOf(character);
            if (digit < 0) {
                continue;
            }
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                output[index++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return output;
    }
}
