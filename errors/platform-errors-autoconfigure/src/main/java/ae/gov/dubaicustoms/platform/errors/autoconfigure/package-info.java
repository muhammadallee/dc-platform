/**
 * Auto-configuration for the errors capability: registers the platform exception-handling
 * {@code @RestControllerAdvice} beans that turn {@code PlatformException}s, Bean Validation
 * failures, and unexpected exceptions into RFC-9457 problem responses.
 *
 * <p>Kill switch: {@code dc.platform.errors.enabled}. Back-off: define beans named
 * {@code platformExceptionHandler} / {@code platformValidationExceptionHandler}.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.errors.autoconfigure;
