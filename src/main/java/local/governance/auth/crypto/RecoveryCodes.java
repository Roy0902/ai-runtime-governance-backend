package local.governance.auth.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RecoveryCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public record Generated(List<String> display, List<byte[]> raw) {}

    public Generated generate() {
        List<String> display = new ArrayList<>(10);
        List<byte[]> raw = new ArrayList<>(10);
        for (int index = 0; index < 10; index += 1) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            raw.add(bytes);
            display.add(format(bytes));
        }
        return new Generated(display, raw);
    }

    public String format(byte[] bytes) {
        StringBuilder hex = new StringBuilder(32);
        for (byte value : bytes) {
            hex.append(HEX[(value >> 4) & 0x0f]).append(HEX[value & 0x0f]);
        }
        StringBuilder grouped = new StringBuilder(39);
        for (int index = 0; index < hex.length(); index += 4) {
            if (index > 0) {
                grouped.append('-');
            }
            grouped.append(hex, index, index + 4);
        }
        return grouped.toString();
    }

    public String normalize(String input) {
        if (input == null) {
            return null;
        }
        String compact = input.replace("-", "").replace(" ", "").trim().toUpperCase();
        if (!compact.matches("[0-9A-F]{32}")) {
            return null;
        }
        return compact;
    }

    public byte[] verifier(UUID accountId, UUID setId, String canonical) {
        byte[] code = canonical.getBytes(StandardCharsets.US_ASCII);
        byte[] domain = "arg-recovery-v1".getBytes(StandardCharsets.US_ASCII);
        byte[] account = accountId.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] set = setId.toString().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(domain.length + 2 + account.length + 2 + set.length + 2 + code.length);
        buffer.put(domain);
        buffer.putShort((short) account.length).put(account);
        buffer.putShort((short) set.length).put(set);
        buffer.putShort((short) code.length).put(code);
        try {
            return MessageDigest.getInstance("SHA-256").digest(buffer.array());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public boolean matches(String canonical, byte[] verifier, UUID accountId, UUID setId) {
        return MessageDigest.isEqual(verifier(accountId, setId, canonical), verifier);
    }
}
