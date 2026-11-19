package ae.gov.dubaicustoms.platform.test.container;

import org.apiguardian.api.API;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Lazily-started, JVM-singleton Testcontainers shared across all {@code @Tag("docker")} tests, so the
 * (expensive) container start-up happens at most once per backend per test run rather than per class.
 *
 * <p>Each accessor starts its container on first call and reuses it thereafter; the containers are
 * reaped by Testcontainers' Ryuk sidecar at JVM exit (no explicit stop needed). Expose one as a
 * Spring bean annotated with {@code @ServiceConnection} to wire it into a {@code @PlatformTest}
 * context:
 *
 * <pre>{@code
 * @PlatformTest
 * @Tag("docker")
 * class OrdersRepositoryIT {
 *     @BeforeAll static void docker() { assumeTrue(DockerAvailable.check()); }
 *
 *     @TestConfiguration
 *     static class Infra {
 *         @Bean @ServiceConnection PostgreSQLContainer postgres() { return Containers.postgres(); }
 *     }
 * }
 * }</pre>
 *
 * <p>Used ONLY by Docker-tagged tests; guard every use with {@link DockerAvailable#check()}. The Kafka,
 * Redis, Vault and LocalStack helpers were removed with the capabilities that needed them (Spring-native
 * plan v2 scope cut). Thread-safe.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class Containers {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private Containers() {
    }

    /**
     * The shared PostgreSQL database, started on first access.
     *
     * @return the running PostgreSQL container
     */
    public static PostgreSQLContainer postgres() {
        return started(POSTGRES);
    }

    private static <T extends GenericContainer<?>> T started(T container) {
        if (!container.isRunning()) {
            synchronized (container) {
                if (!container.isRunning()) {
                    container.start();
                }
            }
        }
        return container;
    }
}
