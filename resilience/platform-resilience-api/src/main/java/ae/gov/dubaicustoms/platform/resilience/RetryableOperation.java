package ae.gov.dubaicustoms.platform.resilience;

import java.util.function.Supplier;
import org.apiguardian.api.API;

/**
 * Programmatic entry point for retrying a block of code under a named policy, for callers who cannot
 * use Resilience4j's declarative {@code @Retry} annotation (dynamic names, non-Spring-managed call
 * sites). The named policy resolves against the platform's retry registry, so {@code name} selects
 * the same configuration a {@code @Retry("name")} annotation would.
 *
 * <pre>{@code
 * String body = retryableOperation.call("inventory", () -> restClient.get(url));
 * }</pre>
 *
 * <p>Deliberately narrow: no wrapper types over Resilience4j's own API (things-to-avoid #25). For
 * circuit breaking or time limiting, use Resilience4j's annotations directly.
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public interface RetryableOperation {

    /**
     * Invokes {@code action}, retrying per the retry policy named {@code name} until it succeeds or
     * the policy is exhausted.
     *
     * @param <T> the result type
     * @param name the retry policy name; resolves against the platform retry registry, falling back
     *     to the {@code default} instance configuration when no instance is explicitly configured;
     *     never {@code null}
     * @param action the operation to attempt; never {@code null}
     * @return the value produced by the first successful attempt
     * @throws RuntimeException the exception thrown by the final failed attempt when retries are
     *     exhausted (propagated unchanged)
     */
    <T> T call(String name, Supplier<T> action);
}
