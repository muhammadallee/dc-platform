/**
 * Module-private internals of the ratelimit autoconfigure: the {@link
 * ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.DefaultRateLimiter}, the
 * {@code @RateLimited} advisor pieces, the HTTP {@link
 * ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.RateLimitFilter}, the MVC {@link
 * ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.RateLimitExceptionAdvice}, and the
 * {@link ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal.RateLimitMetrics} seam.
 *
 * <p>Not API: types here may change without notice. Applications use {@code RateLimiter} and
 * {@code @RateLimited} from {@code platform-ratelimit-api}.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;
