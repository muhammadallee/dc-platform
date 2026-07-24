package ${package};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ${artifactId} service.
 *
 * <p>Built on the DC Platform chassis (parent {@code platform-service-parent}): correlation IDs,
 * JSON logging, RFC-9457 error handling, metrics/tracing, OpenAPI, and an authenticated-by-default
 * security filter chain are all auto-configured. Add capabilities as explicit {@code platform-starter-*}
 * dependencies — see {@code CLAUDE.md} before writing cross-cutting code.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
