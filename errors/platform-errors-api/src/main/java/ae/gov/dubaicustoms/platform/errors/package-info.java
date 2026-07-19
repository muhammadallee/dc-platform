/**
 * The errors capability contract: a small business-exception hierarchy carrying stable
 * {@link ae.gov.dubaicustoms.platform.core.ErrorCode}s plus HTTP status hints, and the
 * {@link ae.gov.dubaicustoms.platform.errors.ProblemDetailCustomizer} extension point for
 * shaping the outgoing RFC-9457 {@code ProblemDetail}.
 *
 * <p>Applications throw these exceptions from domain and web code;
 * {@code platform-errors-autoconfigure} maps them to problem responses.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.errors;
