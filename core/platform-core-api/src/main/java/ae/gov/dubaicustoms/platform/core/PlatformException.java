package ae.gov.dubaicustoms.platform.core;

import java.util.Objects;

/**
 * Root of the platform exception hierarchy. Carries a stable, machine-readable {@link ErrorCode}.
 *
 * <p>Every exception thrown by platform code is a subtype of this class, so applications can
 * catch, map, and alert on codes rather than types. Immutable and safe to serialize into an
 * RFC-9457 ProblemDetail (phase 4).
 *
 * <pre>{@code
 * public final class EventPublishException extends PlatformException {
 *     public EventPublishException(String message, Throwable cause) {
 *         super(new ErrorCode("DC-MSG-0500"), message, cause);
 *     }
 * }
 * }</pre>
 *
 * <p>Thread-safe. Constructor arguments other than {@code cause} must not be {@code null}.
 *
 * @since 0.1.0
 */
public abstract class PlatformException extends RuntimeException {

    private final ErrorCode code;

    /**
     * Creates the exception with a code and a human-readable message.
     *
     * @param code the stable error code; never {@code null}
     * @param message the detail message; never {@code null}
     * @throws NullPointerException if {@code code} or {@code message} is null
     */
    protected PlatformException(ErrorCode code, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    /**
     * Creates the exception with a code, a human-readable message, and a cause.
     *
     * @param code the stable error code; never {@code null}
     * @param message the detail message; never {@code null}
     * @param cause the underlying cause; may be {@code null}
     * @throws NullPointerException if {@code code} or {@code message} is null
     */
    protected PlatformException(ErrorCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    /**
     * Returns the stable error code identifying this failure.
     *
     * @return the error code; never {@code null}
     */
    public ErrorCode code() {
        return code;
    }
}
