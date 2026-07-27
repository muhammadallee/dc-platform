package ae.gov.dubaicustoms.example.goldenpath;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The canonical platform service. Every cross-cutting concern — correlation, JSON logs, RFC-9457
 * errors, bean validation, an authenticated-by-default security chain, OpenAPI, metrics/tracing, JPA
 * conventions, event publishing/handling, caching, resilience, and audit — comes from a
 * {@code platform-starter-*} on the classpath. This class contains no cross-cutting code; read the
 * {@code orders} package to see how a service uses the platform, not how it re-implements it.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
