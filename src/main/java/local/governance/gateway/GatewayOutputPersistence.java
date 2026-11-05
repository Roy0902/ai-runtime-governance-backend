package local.governance.gateway;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GatewayOutputPersistence {

    private static final Logger log = LoggerFactory.getLogger(GatewayOutputPersistence.class);
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public void submit(Runnable persistenceTask) {
        try {
            // If write volume becomes a bottleneck, batch database writes before adding application-level concurrency limits.
            executor.execute(() -> run(persistenceTask));
        } catch (RejectedExecutionException exception) {
            log.warn("gateway output persistence executor is stopping; persisting inline", exception);
            run(persistenceTask);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("gateway output persistence did not drain before shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void run(Runnable persistenceTask) {
        try {
            persistenceTask.run();
        } catch (RuntimeException exception) {
            log.error("gateway output persistence task failed unexpectedly", exception);
        }
    }
}