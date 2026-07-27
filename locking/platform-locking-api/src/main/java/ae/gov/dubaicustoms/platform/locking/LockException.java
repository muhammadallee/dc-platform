package ae.gov.dubaicustoms.platform.locking;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * Thrown when a {@link LockManager} operation fails for an infrastructural reason — the lock backend
 * is unreachable or errors, or the guarded action throws a checked exception (wrapped here so the
 * single-declared-throwable contract of {@link LockManager#withLock} holds).
 *
 * <p>Not thrown merely because a lock could not be acquired: that is a normal outcome signalled by an
 * empty {@link java.util.Optional}.
 *
 * <p>Immutable and thread-safe.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class LockException extends PlatformException {

    private static final ErrorCode CODE = new ErrorCode("DC-LOCK-0500");

    /**
     * Creates the exception.
     *
     * @param message the detail message; never {@code null}
     * @param cause the underlying failure, or {@code null}
     * @throws NullPointerException if {@code message} is null
     */
    public LockException(String message, Throwable cause) {
        super(CODE, Objects.requireNonNull(message, "message must not be null"), cause);
    }
}
