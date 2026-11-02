package local.governance.auth.crypto;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import local.governance.api.APIException;
import local.governance.config.TestFaults;
import org.springframework.stereotype.Component;

/** One active hash job and at most four waiting jobs, with a two-second authority deadline. */
@Component
public class HashWorkLimiter {

    private final TestFaults faults;

    public HashWorkLimiter(TestFaults faults) {
        this.faults = faults;
    }

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4),
            runnable -> {
                Thread thread = new Thread(runnable, "password-hash");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public <T> T submit(Callable<T> task) {
        if (faults.enabled("hash-service")) {
            throw APIException.unavailable();
        }
        Future<T> future;
        try {
            future = executor.submit(task);
        } catch (RejectedExecutionException exception) {
            throw APIException.unavailable();
        }
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw APIException.unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw APIException.unavailable();
        } catch (java.util.concurrent.ExecutionException exception) {
            if (exception.getCause() instanceof APIException apiException) {
                throw apiException;
            }
            throw APIException.unavailable();
        }
    }

    public int queueDepth() {
        return executor.getQueue().size();
    }

    public int activeCount() {
        return executor.getActiveCount();
    }
}
