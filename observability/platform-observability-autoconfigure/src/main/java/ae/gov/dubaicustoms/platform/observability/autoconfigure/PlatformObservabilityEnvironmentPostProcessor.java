package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes the platform's management-plane defaults — actuator exposure, liveness/readiness
 * health groups, correlation baggage, and the local OTLP-off posture — as a LOWEST-precedence
 * property source named {@code platform-observability-defaults}, so user configuration always
 * wins and the source name is visible in {@code /actuator/env} for debuggability
 * (property-conventions §7).
 *
 * <p>Cardinality rationale (mandatory, phase-05 spec): the correlation id is HIGH-cardinality —
 * one value per request. It must never become a metric tag or observation key-value (it would
 * explode time-series storage), so the platform propagates it exclusively through tracing
 * baggage ({@code management.tracing.baggage.remote-fields}); log/MDC correlation is already
 * handled by the core filter.
 *
 * <p>Stateless and thread-safe.
 *
 * @since 0.2.0
 */
public class PlatformObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Property-source name; keep stable — operators grep for it in /actuator/env. */
    static final String PROPERTY_SOURCE_NAME = "platform-observability-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("dc.platform.observability.enabled", Boolean.class, true)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        // Actuator exposure conventions: the platform's operational surface over http. The
        // platform endpoint itself carries no secrets; real authn arrives with phase 6 security.
        defaults.put("management.endpoints.web.exposure.include",
                "health,info,platform,metrics,prometheus");
        if (environment.getProperty("dc.platform.observability.health.groups.enabled", Boolean.class, true)) {
            // probes.enabled makes the livenessState/readinessState contributors exist on every
            // platform (Boot only auto-enables them on Kubernetes).
            defaults.put("management.endpoint.health.probes.enabled", "true");
            defaults.put("management.endpoint.health.group.liveness.include", "livenessState");
            defaults.put("management.endpoint.health.group.readiness.include",
                    "readinessState,db,rabbit,redis");
            // The readiness list names OPTIONAL members ("when present", phase-05 spec); with
            // membership validation on, a service without a DB would fail startup.
            defaults.put("management.endpoint.health.validate-group-membership", "false");
        }
        // Correlation propagates via baggage ONLY — see the class javadoc cardinality rationale.
        // Header name mirrors the core filter's dc.platform.core.correlation.header-name default.
        defaults.put("management.tracing.baggage.remote-fields", "X-Correlation-Id");
        if (!environment.getProperty("dc.platform.observability.otlp.enabled", Boolean.class, false)) {
            // Local default OFF: no collector on laptops; flipping dc.platform.observability
            // .otlp.enabled=true removes these defaults so Boot's export config takes over.
            defaults.put("management.otlp.metrics.export.enabled", "false");
            defaults.put("management.tracing.export.otlp.enabled", "false");
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor: application.yml/profile values must be
        // visible when the toggles are read.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
