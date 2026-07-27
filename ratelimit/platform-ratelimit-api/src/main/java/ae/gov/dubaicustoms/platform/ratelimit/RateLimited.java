package ae.gov.dubaicustoms.platform.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apiguardian.api.API;

/**
 * Rate-limits a method: before each invocation the platform consumes a permit from the bucket named
 * {@link #name()}, keyed by {@link #keyExpression()}, and rejects the call when the limit is exceeded
 * (surfaced as HTTP 429 with {@code Retry-After} when the errors/web capabilities are present).
 *
 * <pre>{@code
 * @RateLimited(name = "search", permits = 20, window = "PT1S", keyExpression = "#user")
 * public List<Hit> search(String user, String query) { ... }
 * }</pre>
 *
 * <p>Apply to public methods of Spring-managed beans. An empty {@link #keyExpression()} rate-limits
 * the method globally (a single bucket named {@link #name()} shared by all callers).
 *
 * @since 0.2.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@API(status = API.Status.STABLE, since = "0.1.0")
public @interface RateLimited {

    /**
     * The bucket name; combined with the evaluated key to form the limiter key.
     *
     * @return the bucket name
     */
    String name();

    /**
     * The maximum number of permitted invocations within {@link #window()}.
     *
     * @return the permit count
     */
    int permits() default 100;

    /**
     * The window length as an ISO-8601 / Spring duration string (e.g. {@code "PT1M"}, {@code "1s"}).
     *
     * @return the window duration
     */
    String window() default "PT1M";

    /**
     * A SpEL expression, evaluated against the method arguments (by name and as {@code #a0},
     * {@code #p0}, …), that yields the per-caller key dimension. Empty limits the method globally.
     *
     * @return the key expression, or {@code ""} for a global bucket
     */
    String keyExpression() default "";
}
