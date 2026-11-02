package local.governance.auth.crypto;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local demo key source. Production must supply the active key through a
 * deployment-protected secret source; keys are never read from source code or
 * the database ciphertext.
 */
@Component
public class SeedKeyProvider {

    private final Map<String, SecretKey> keys = new ConcurrentHashMap<>();
    private final Path directory;
    private final String configuredKey;
    private volatile String activeId;

    public SeedKeyProvider(
            @Value("${governance.seed-key:}") String configuredKey,
            @Value("${governance.seed-key-directory:.local-cache/seed-keys}") String directory) {
        this.configuredKey = configuredKey;
        this.directory = Path.of(directory);
    }

    @PostConstruct
    void initialize() throws IOException {
        Files.createDirectories(directory);
        if (configuredKey != null && !configuredKey.isBlank()) {
            keys.put("configured", decodeKey(configuredKey));
            activeId = "configured";
            return;
        }
        Path active = directory.resolve("active.txt");
        if (Files.exists(active)) {
            activeId = Files.readString(active).trim();
        }
        try (var files = Files.list(directory)) {
            files.filter((file) -> file.getFileName().toString().endsWith(".key")).forEach((file) -> {
                try {
                    String id = file.getFileName().toString().replace(".key", "");
                    keys.put(id, new SecretKeySpec(Files.readAllBytes(file), "AES"));
                } catch (IOException exception) {
                    throw new IllegalStateException("cannot load seed key " + file, exception);
                }
            });
        }
        if (activeId == null || !keys.containsKey(activeId)) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String id = "local-1";
            Files.write(directory.resolve(id + ".key"), raw);
            Files.writeString(active, id);
            keys.put(id, new SecretKeySpec(raw, "AES"));
            activeId = id;
        }
    }

    public String activeKeyId() {
        return activeId;
    }

    public java.util.List<String> keyIds() {
        return keys.keySet().stream().sorted().toList();
    }

    public SecretKey activeKey() {
        return keys.get(activeId);
    }

    public SecretKey key(String id) {
        return keys.get(id);
    }

    public synchronized void provision(String id, byte[] raw) throws IOException {
        if (raw.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes");
        }
        Files.createDirectories(directory);
        Files.write(directory.resolve(id + ".key"), raw);
        keys.put(id, new SecretKeySpec(raw, "AES"));
    }

    public synchronized void activate(String id) throws IOException {
        if (!keys.containsKey(id)) {
            throw new IllegalArgumentException("unknown key " + id);
        }
        Files.writeString(directory.resolve("active.txt"), id);
        activeId = id;
    }

    public boolean keyDependentWorkAvailable() {
        return activeId != null && keys.containsKey(activeId);
    }

    private SecretKey decodeKey(String base64) {
        byte[] raw = java.util.Base64.getDecoder().decode(base64);
        if (raw.length != 32) {
            throw new IllegalStateException("configured seed key must be 32 bytes");
        }
        return new SecretKeySpec(raw, "AES");
    }
}
