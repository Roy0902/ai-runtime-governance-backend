package local.governance.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Test-only fault flags. Backed by a file so the web process and the separate
 * arg-admin process see the same state. Never enabled in production.
 */
@Component
public class TestFaults {

    private final Path file;
    private final ConcurrentHashMap<String, PauseGate> pauseGates = new ConcurrentHashMap<>();

    private static final class PauseGate {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
    }

    public TestFaults(@Value("${governance.test-faults-file:.local-cache/test-faults.properties}") String file) {
        this.file = Path.of(file);
    }

    public boolean enabled(String name) {
        Properties properties = read();
        return Boolean.parseBoolean(properties.getProperty(name, "false"));
    }

    public synchronized void set(String name, boolean enabled) {
        Properties properties = read();
        properties.setProperty(name, Boolean.toString(enabled));
        write(properties);
        if (!enabled) {
            release(name);
        }
    }

    public synchronized void clear() {
        write(new Properties());
        pauseGates.forEach((name, gate) -> gate.released.countDown());
        pauseGates.clear();
    }

    public void pauseIfEnabled(String name) {
        if (!enabled(name)) {
            return;
        }
        PauseGate gate = pauseGates.computeIfAbsent(name, ignored -> new PauseGate());
        if (!enabled(name)) {
            gate.released.countDown();
            pauseGates.remove(name, gate);
            return;
        }
        gate.reached.countDown();
        try {
            if (!gate.released.await(10, TimeUnit.SECONDS)) {
                throw local.governance.api.ApiException.unavailable();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw local.governance.api.ApiException.unavailable();
        } finally {
            pauseGates.remove(name, gate);
        }
    }

    public boolean isPaused(String name) {
        PauseGate gate = pauseGates.get(name);
        return gate != null && gate.reached.getCount() == 0 && gate.released.getCount() > 0;
    }

    private void release(String name) {
        PauseGate gate = pauseGates.get(name);
        if (gate != null) {
            gate.released.countDown();
        }
    }

    private Properties read() {
        Properties properties = new Properties();
        if (!Files.exists(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException exception) {
            // A missing/unreadable test file means no faults; do not hide a prod path.
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
                properties.store(writer, "test-only fault flags");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot persist test faults", exception);
        }
    }
}
