package ae.gov.dubaicustoms.example.goldenpath;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The canonical platform service. Every cross-cutting concern — correlation, JSON logs, RFC-9457
 * errors, bean validation, metrics/tracing, JPA conventions and the transactional outbox — comes from a
 * {@code platform-starter-*} (or Spring Boot starter) on the classpath. Web security arrives with the REST API
 * capability in iteration 2 of the Spring-native plan; until then these endpoints are open. This class contains no cross-cutting code; read the
 * {@code orders} package to see how a service uses the platform, not how it re-implements it.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
