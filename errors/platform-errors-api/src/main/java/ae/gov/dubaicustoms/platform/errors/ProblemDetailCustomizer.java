package ae.gov.dubaicustoms.platform.errors;

import org.apiguardian.api.API;
import org.springframework.http.ProblemDetail;

/**
 * SPI-lite: customize the outgoing {@link ProblemDetail} after the platform has populated it.
 * Contribute beans of this type; the platform exception handler applies them in {@code @Order}.
 *
 * <pre>{@code
 * @Bean
 * @Order(10)
 * ProblemDetailCustomizer tenantCustomizer() {
 *     return (detail, source) -> detail.setProperty("tenant", TenantContext.current());
 * }
 * }</pre>
 *
 * <p><b>Implementation requirements:</b> implementations must be thread-safe and fast — they run
 * on the request thread for every error response. They may overwrite platform-set fields (that is
 * the point) but must not throw: a throwing customizer turns the mapped error into an unmapped
 * 500. The platform guarantees {@code detail} is non-null and already fully populated, and that
 * {@code source} is the originating exception. New {@code default} methods may be added in minor
 * releases.
 *
 * @since 0.1.0
 */
@FunctionalInterface
@API(status = API.Status.STABLE, since = "0.1.0")
public interface ProblemDetailCustomizer {

    /**
     * Adjusts the problem body before it is written to the response.
     *
     * @param detail the populated problem body; never {@code null}
     * @param source the exception being mapped; never {@code null}
     */
    void customize(ProblemDetail detail, Throwable source);
}
