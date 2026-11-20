package ae.gov.dubaicustoms.platform.observability.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes the platform's management-plane defaults — minimal actuator exposure on its own port (8081, ND13),
 * pod-local liveness/readiness groups plus a {@code dependencies} group, and the local OTLP-off posture — as a
 * LOWEST-precedence property source named {@code platform-observability-defaults}, so user
 * configuration always wins and the source name is visible in {@code /actuator/env} for
 * debuggability (property-conventions §7).
 *
 * <p>Cardinality rationale (mandatory, phase-05 spec): the correlation id — the W3C trace ID — is
 * HIGH-cardinality, one value per request. It must never become a metric tag or observation
 * key-value (it would explode time-series storage); it travels only in the standard
 * {@code traceparent} header, and log/MDC correlation is handled by the core filter. No custom
 * correlation header is mirrored into tracing baggage any more.
 *
 * <p>Stateless and thread-safe.
 *
 * @since 0.2.0
 */
public class PlatformObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Property-source name; keep stable — operators grep for it in /actuator/env. */
    static final String PROPERTY_SOURCE_NAME = "platform-observability-defaults";

    /** Default actuator web exposure. */
    static final String EXPOSURE = "health,info,prometheus";

    /**
     * Members of the {@code dependencies} health group: shared infrastructure, never readiness. Only shipped
     * capabilities (HI-0112): the REST client returns when iteration 2 unparks it.
     */
    static final String DEPENDENCIES = "db,rabbit";

    /** Default management port (ND13): actuator is never served on the application port. */
    static final String MANAGEMENT_PORT = "8081";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("dc.platform.observability.enabled", Boolean.class, true)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        // Minimal exposure (D105): probes, build info and the scrape endpoint. Everything else,
        // including metrics and the platform capability report, is an explicit opt-in.
        defaults.put("management.endpoints.web.exposure.include", EXPOSURE);
        // ND13: iteration 1 has no web security, so probes and the scrape live on their own port, reachable only
        // inside the cluster; the application port serves only the API. A random server port (tests) gets a random
        // management port too, so parallel tests never collide.
        defaults.put("management.server.port", managementPort(environment));
        if (environment.getProperty("dc.platform.observability.health.groups.enabled", Boolean.class, true)) {
            // probes.enabled makes the livenessState/readinessState contributors exist on every
            // platform (Boot only auto-enables them on Kubernetes).
            defaults.put("management.endpoint.health.probes.enabled", "true");
            defaults.put("management.endpoint.health.group.liveness.include", "livenessState");
            // Readiness is pod-local (D6/D104): a shared dependency in it would mark every replica
            // unready at once. Shared dependencies get their own group, for dashboards.
            defaults.put("management.endpoint.health.group.readiness.include", "readinessState");
            defaults.put("management.endpoint.health.group.dependencies.include", DEPENDENCIES);
            // The dependencies list names OPTIONAL members (present only with the matching
            // capability); with membership validation on, a service without a DB would fail startup.
            defaults.put("management.endpoint.health.validate-group-membership", "false");
        }
        if (!environment.getProperty("dc.platform.observability.otlp.enabled", Boolean.class, false)) {
            // Local default OFF: no collector on laptops; flipping dc.platform.observability
            // .otlp.enabled=true removes these defaults so Boot's export config takes over.
            defaults.put("management.otlp.metrics.export.enabled", "false");
            defaults.put("management.tracing.export.otlp.enabled", "false");
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    // The platform's management port, unless the service set one. Fails closed when the service moved its application
    // port onto the platform's management port: Boot would then serve actuator on the application port (ND13).
    static String managementPort(Environment environment) {
        String server = environment.getProperty("server.port");
        if ("0".equals(server)) {
            return "0";
        }
        if (environment.getProperty("management.server.port") == null && MANAGEMENT_PORT.equals(server)) {
            throw new IllegalStateException("server.port is " + server + ", the port the platform gives actuator "
                    + "(ND13), so actuator would answer on the application port. Set management.server.port to another "
                    + "port, or to " + server + " to serve actuator on the application port on purpose.");
        }
        return MANAGEMENT_PORT;
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor: application.yml/profile values must be
        // visible when the toggles are read.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
