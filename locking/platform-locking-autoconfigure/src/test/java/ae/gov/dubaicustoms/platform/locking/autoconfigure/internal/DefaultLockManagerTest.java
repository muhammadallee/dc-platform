package ae.gov.dubaicustoms.platform.locking.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.locking.LockException;
import ae.gov.dubaicustoms.platform.locking.spi.LockHandle;
import ae.gov.dubaicustoms.platform.locking.spi.LockProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Unit tests for the lock-run-release lifecycle and its exception mapping. */
class DefaultLockManagerTest {

    private static final Duration ATMOST = Duration.ofSeconds(30);

    @Test
    void runsActionAndReleasesWhenAcquired() {
        AtomicBoolean released = new AtomicBoolean(false);
        LockProvider provider = (name, atMost) -> Optional.of(() -> released.set(true));
        DefaultLockManager manager = new DefaultLockManager(provider);

        Optional<String> result = manager.withLock("job", ATMOST, () -> "done");

        assertThat(result).contains("done");
        assertThat(released).isTrue();
    }

    @Test
    void skipsWhenNotAcquired() {
        AtomicBoolean ran = new AtomicBoolean(false);
        LockProvider provider = (name, atMost) -> Optional.empty();
        DefaultLockManager manager = new DefaultLockManager(provider);

        Optional<String> result = manager.withLock("job", ATMOST, () -> {
            ran.set(true);
            return "done";
        });

        assertThat(result).isEmpty();
        assertThat(ran).isFalse();
    }

    @Test
    void emptyWhenActionReturnsNull() {
        LockProvider provider = (name, atMost) -> Optional.of(() -> { });
        DefaultLockManager manager = new DefaultLockManager(provider);

        assertThat(manager.withLock("job", ATMOST, () -> null)).isEmpty();
    }

    @Test
    void propagatesRuntimeExceptionAndReleases() {
        AtomicBoolean released = new AtomicBoolean(false);
        LockProvider provider = (name, atMost) -> Optional.of(() -> released.set(true));
        DefaultLockManager manager = new DefaultLockManager(provider);

        assertThatThrownBy(() -> manager.withLock("job", ATMOST, () -> {
            throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("business failure");
        assertThat(released).isTrue();
    }

    @Test
    void wrapsCheckedActionExceptionInLockException() {
        AtomicBoolean released = new AtomicBoolean(false);
        LockProvider provider = (name, atMost) -> Optional.of(() -> released.set(true));
        DefaultLockManager manager = new DefaultLockManager(provider);

        assertThatThrownBy(() -> manager.withLock("job", ATMOST, () -> {
            throw new java.io.IOException("io failure");
        })).isInstanceOf(LockException.class)
                .hasMessageContaining("job")
                .hasCauseInstanceOf(java.io.IOException.class);
        assertThat(released).isTrue();
    }

    @Test
    void wrapsProviderFailureInLockException() {
        LockProvider provider = (name, atMost) -> {
            throw new IllegalStateException("backend down");
        };
        DefaultLockManager manager = new DefaultLockManager(provider);

        assertThatThrownBy(() -> manager.withLock("job", ATMOST, () -> "unused"))
                .isInstanceOf(LockException.class)
                .hasMessageContaining("job")
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
