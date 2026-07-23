package ae.gov.dubaicustoms.platform.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * The outcome of a {@link RateLimiter#tryAcquire(String, int, java.time.Duration)} call: whether the
 * request is permitted, and if not, how long the caller should wait before retrying.
 *
 * <pre>{@code
 * Decision d = rateLimiter.tryAcquire(userId, 100, Duration.ofMinutes(1));
 * if (!d.allowed()) {
 *     throw new TooManyRequests(d.retryAfter()); // surface as HTTP 429 + Retry-After
 * }
 * }</pre>
 *
 * <p>Value object; immutable and thread-safe. {@code retryAfter} is never {@code null} — it is
 * {@link Duration#ZERO} when the request is allowed.
 *
 * @param allowed {@code true} when the request is within the limit
 * @param retryAfter how long to wait before the next attempt may succeed; {@code ZERO} when allowed
 * @since 0.2.0
 */
public record Decision(boolean allowed, Duration retryAfter) {

    /**
     * Validates that {@code retryAfter} is present.
     *
     * @throws NullPointerException if {@code retryAfter} is null
     */
    public Decision {
        Objects.requireNonNull(retryAfter, "retryAfter must not be null");
    }

    /**
     * A permitting decision with no wait.
     *
     * @return an allowed decision
     */
    public static Decision allow() {
        return new Decision(true, Duration.ZERO);
    }

    /**
     * A rejecting decision carrying the retry hint.
     *
     * @param retryAfter how long until the limit refreshes; never {@code null}
     * @return a denied decision
     */
    public static Decision deny(Duration retryAfter) {
        return new Decision(false, retryAfter);
    }
}
