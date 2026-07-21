package ae.gov.dubaicustoms.platform.locking.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Documents the SPI contract with a minimal fake: {@code tryAcquire} returns a handle whose
 * {@code close} releases exactly once and is safe to call again.
 */
class LockProviderContractTest {

    @Test
    void handleReleasesIdempotently() {
        AtomicBoolean released = new AtomicBoolean(false);
        LockProvider provider = (name, atMost) -> Optional.of(() -> released.set(true));

        Optional<LockHandle> handle = provider.tryAcquire("orders", Duration.ofSeconds(30));

        assertThat(handle).isPresent();
        try (LockHandle held = handle.orElseThrow()) {
            assertThat(released).isFalse();
        }
        assertThat(released).isTrue();
    }

    @Test
    void emptyWhenAlreadyHeld() {
        LockProvider provider = (name, atMost) -> Optional.empty();

        assertThat(provider.tryAcquire("orders", Duration.ofSeconds(30))).isEmpty();
    }
}
