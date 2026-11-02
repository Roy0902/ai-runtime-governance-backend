package local.governance.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Test-only dispatch ordering state. File-backed so the web process and the
 * separate arg-admin process observe the same committed order.
 */
@Component
public class DispatchState {

    private final Path file;

    public DispatchState(@Value("${governance.dispatch-file:.local-cache/dispatch.properties}") String file) {
        this.file = Path.of(file);
    }

    public synchronized void dispatch(String email) {
        Properties properties = read();
        if (!properties.containsKey(email)) {
            properties.setProperty(email, "DISPATCHED");
            write(properties);
        }
    }

    public synchronized void revoke(String email) {
        Properties properties = read();
        String current = properties.getProperty(email);
        properties.setProperty(email, current == null ? "REVOKED_THEN_BLOCKED" : "DISPATCHED_THEN_REVOKED");
        write(properties);
    }

    public String order(String email) {
        return read().getProperty(email, "NONE");
    }

    public boolean dispatched(String email) {
        String current = order(email);
        return !"NONE".equals(current);
    }

    public boolean undispatchedBlocked(String email) {
        String current = order(email);
        return "REVOKED_THEN_BLOCKED".equals(current) || "DISPATCHED_THEN_REVOKED".equals(current);
    }

    public synchronized void clear() {
        write(new Properties());
    }

    private Properties read() {
        Properties properties = new Properties();
        if (!Files.exists(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException exception) {
            // no dispatch state
        }
        return properties;
    }

    private void write(Properties properties) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(file)) {
                properties.store(writer, "test-only dispatch state");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot persist dispatch state", exception);
        }
    }
}
