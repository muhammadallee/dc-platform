package ae.gov.dubaicustoms.platform.resilience.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Behavior test: the helper retries the configured number of attempts before giving up. */
class DefaultRetryableOperationTest {

    private final RetryRegistry registry = RetryRegistry.of(RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(1))
            .retryExceptions(RuntimeException.class)
            .build());
    private final DefaultRetryableOperation operation = new DefaultRetryableOperation(registry);

    @Test
    void retriesUntilTheOperationSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        String result = operation.call("flaky", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("transient");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void propagatesTheFinalFailureWhenRetriesAreExhausted() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> operation.call("always-fails", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(attempts).hasValue(3);
    }
}
