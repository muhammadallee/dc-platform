/**
 * Auto-configuration for the OpenAPI capability: a springdoc {@code OpenAPI} bean (info, servers,
 * bearer-jwt security scheme) and an {@code OpenApiCustomizer} that appends the platform's
 * {@code ProblemDetail} error schema and default 4xx/5xx responses to every documented operation
 * — the OpenAPI mirror of the errors slice (phase 4).
 *
 * <p>Kill switch: {@code dc.platform.openapi.enabled}. Back-off: define your own {@code OpenAPI}
 * bean.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.openapi.autoconfigure;
