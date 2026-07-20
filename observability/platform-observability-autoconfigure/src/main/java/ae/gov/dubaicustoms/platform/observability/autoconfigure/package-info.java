/**
 * Auto-configuration for the observability capability: common meter tags
 * ({@code service}/{@code env}/{@code platform.version}), liveness/readiness health-group and
 * actuator-exposure defaults contributed before the context exists, correlation propagation via
 * tracing baggage (never metric tags — cardinality), and the {@code platform} actuator endpoint
 * reporting active capabilities.
 *
 * <p>Kill switch: {@code dc.platform.observability.enabled}. OTLP export is OFF by default
 * ({@code dc.platform.observability.otlp.enabled=false}) — laptops have no collector.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.observability.autoconfigure;
