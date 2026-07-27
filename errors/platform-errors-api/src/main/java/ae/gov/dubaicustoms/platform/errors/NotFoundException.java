package ae.gov.dubaicustoms.platform.errors;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import org.apiguardian.api.API;

/**
 * Thrown when the addressed resource does not exist; maps to HTTP 404.
 *
 * <pre>{@code
 * Order order = repository.findById(id)
 *         .orElseThrow(() -> new NotFoundException(new ErrorCode("DC-ORDER-0404"),
 *                 "order %s not found".formatted(id)));
 * }</pre>
 *
 * <p>Thread-safe (immutable). Constructor arguments must not be {@code null}.
 *
 * @since 0.1.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public class NotFoundException extends BusinessException {

    /**
     * Creates the exception.
     *
     * @param code the stable error code; never {@code null}
     * @param message the detail message, safe to expose to API clients; never {@code null}
     * @throws NullPointerException if {@code code} or {@code message} is null
     */
    public NotFoundException(ErrorCode code, String message) {
        super(code, message, HttpStatusHint.NOT_FOUND);
    }
}
