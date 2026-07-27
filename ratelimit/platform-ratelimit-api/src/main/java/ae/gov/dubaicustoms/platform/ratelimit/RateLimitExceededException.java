package ae.gov.dubaicustoms.platform.ratelimit;

import java.time.Duration;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * Thrown when a {@link RateLimited} method is invoked past its limit. Carries the {@code retryAfter}
 * hint so a handler can surface it as HTTP 429 with a {@code Retry-After} header — the platform's
 * ratelimit auto-configuration registers exactly such a handler when Spring MVC is present.
 *
 * <p>The errors capability's {@code HttpStatusHint} enum has no 429 value (it is a closed 4xx set),
 * so rate-limit rejection is a distinct exception type rather than a {@code BusinessException}
 * (decision D54).
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    /**
     * @param name the exceeded bucket name (from {@link RateLimited#name()})
     * @param retryAfter how long the caller should wait before retrying; never {@code null}
     */
    public RateLimitExceededException(String name, Duration retryAfter) {
        super("rate limit '" + name + "' exceeded; retry after " + retryAfter);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter must not be null");
    }

    /**
     * Returns how long the caller should wait before retrying.
     *
     * @return the retry-after duration; never {@code null}
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}
