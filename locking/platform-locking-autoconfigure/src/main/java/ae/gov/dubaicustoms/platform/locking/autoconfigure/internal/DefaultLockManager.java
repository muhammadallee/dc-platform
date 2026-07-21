package ae.gov.dubaicustoms.platform.locking.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.locking.LockException;
import ae.gov.dubaicustoms.platform.locking.LockManager;
import ae.gov.dubaicustoms.platform.locking.spi.LockHandle;
import ae.gov.dubaicustoms.platform.locking.spi.LockProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The platform {@link LockManager}: acquires a lock via the configured {@link LockProvider} and runs
 * the action inside try-with-resources so the lock is always released. Exists to keep the provider
 * SPI minimal (acquire/release only) while giving applications the higher-level "run this once" API.
 */
public final class DefaultLockManager implements LockManager {

    private final LockProvider provider;

    /**
     * @param provider the backend that acquires and releases locks
     */
    public DefaultLockManager(LockProvider provider) {
        this.provider = provider;
    }

    @Override
    public <T> Optional<T> withLock(String name, Duration atMost, Callable<T> action) throws LockException {
        Optional<LockHandle> acquired;
        try {
            acquired = provider.tryAcquire(name, atMost);
        } catch (RuntimeException e) {
            throw new LockException("Failed to acquire lock '" + name + "'", e);
        }
        if (acquired.isEmpty()) {
            return Optional.empty();
        }
        try (LockHandle handle = acquired.get()) {
            return Optional.ofNullable(action.call());
        } catch (RuntimeException e) {
            // Business failures propagate unchanged; only the lock is released (by close()).
            throw e;
        } catch (Exception e) {
            // Callable's checked exceptions are wrapped so the single-throwable contract holds.
            throw new LockException("Locked action '" + name + "' failed", e);
        }
    }
}
