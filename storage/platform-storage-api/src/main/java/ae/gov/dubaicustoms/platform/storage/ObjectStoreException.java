package ae.gov.dubaicustoms.platform.storage;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import java.util.Objects;

/**
 * Thrown when an {@link ObjectStore} operation fails: an invalid or unsafe key
 * ({@link #INVALID_KEY}, client fault) or a backend read/write/delete failure
 * ({@link #IO}, infrastructural).
 *
 * <p>A missing object is <em>not</em> an exception — {@link ObjectStore#get} returns an empty
 * {@link java.util.Optional} and {@link ObjectStore#delete} returns {@code false}.
 *
 * <p>Immutable and thread-safe.
 *
 * @since 0.2.0
 */
public final class ObjectStoreException extends PlatformException {

    /** The bucket or key was blank, malformed, or attempted path traversal (client fault). */
    public static final ErrorCode INVALID_KEY = new ErrorCode("DC-STO-0400");

    /** The storage backend failed to read, write, list, or delete (infrastructural). */
    public static final ErrorCode IO = new ErrorCode("DC-STO-0500");

    /**
     * Creates the exception with an explicit code and message.
     *
     * @param code the stable error code (typically {@link #INVALID_KEY} or {@link #IO}); never {@code null}
     * @param message the detail message; never {@code null}
     * @throws NullPointerException if {@code code} or {@code message} is null
     */
    public ObjectStoreException(ErrorCode code, String message) {
        super(Objects.requireNonNull(code, "code must not be null"),
                Objects.requireNonNull(message, "message must not be null"));
    }

    /**
     * Creates the exception with an explicit code, message and cause.
     *
     * @param code the stable error code (typically {@link #INVALID_KEY} or {@link #IO}); never {@code null}
     * @param message the detail message; never {@code null}
     * @param cause the underlying failure, or {@code null}
     * @throws NullPointerException if {@code code} or {@code message} is null
     */
    public ObjectStoreException(ErrorCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(code, "code must not be null"),
                Objects.requireNonNull(message, "message must not be null"), cause);
    }
}
