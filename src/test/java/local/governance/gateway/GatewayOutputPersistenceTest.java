package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GatewayOutputPersistenceTest {

    @Test
    void dispatchesTasksOnVirtualThreadsWithoutWaitingForCompletion() throws Exception {
        GatewayOutputPersistence persistence = new GatewayOutputPersistence();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> virtualThread = new AtomicReference<>();

        try {
            persistence.submit(() -> {
                virtualThread.set(Thread.currentThread().isVirtual());
                started.countDown();
                await(release);
            });

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(Boolean.TRUE.equals(virtualThread.get()));
        } finally {
            release.countDown();
            persistence.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test release.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}