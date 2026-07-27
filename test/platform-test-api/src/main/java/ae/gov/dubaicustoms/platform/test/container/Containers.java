package ae.gov.dubaicustoms.platform.test.container;

import org.apiguardian.api.API;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
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
 * class OrdersKafkaIT {
 *     @BeforeAll static void docker() { assumeTrue(DockerAvailable.check()); }
 *
 *     @TestConfiguration
 *     static class Infra {
 *         @Bean @ServiceConnection KafkaContainer kafka() { return Containers.kafka(); }
 *     }
 * }
 * }</pre>
 *
 * <p>Used ONLY by Docker-tagged tests; guard every use with {@link DockerAvailable#check()}. Vault and
 * LocalStack are exposed as generic containers (their single relevant port is published) rather than
 * dedicated types, so no extra Testcontainers modules are pulled in. Thread-safe.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class Containers {

    private static final int VAULT_PORT = 8200;
    private static final int LOCALSTACK_PORT = 4566;
    private static final int REDIS_PORT = 6379;

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    private static final GenericContainer<?> VAULT =
            new GenericContainer<>(DockerImageName.parse("hashicorp/vault:1.17"))
                    .withExposedPorts(VAULT_PORT)
                    .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "test-root-token");
    private static final GenericContainer<?> LOCALSTACK =
            new GenericContainer<>(DockerImageName.parse("localstack/localstack:3"))
                    .withExposedPorts(LOCALSTACK_PORT);

    private Containers() {
    }

    /**
     * The shared Kafka broker, started on first access.
     *
     * @return the running Kafka container
     */
    public static KafkaContainer kafka() {
        return started(KAFKA);
    }

    /**
     * The shared Redis server (port 6379 exposed), started on first access.
     *
     * @return the running Redis container
     */
    public static GenericContainer<?> redis() {
        return started(REDIS);
    }

    /**
     * The shared PostgreSQL database, started on first access.
     *
     * @return the running PostgreSQL container
     */
    public static PostgreSQLContainer postgres() {
        return started(POSTGRES);
    }

    /**
     * The shared HashiCorp Vault (dev mode, root token {@code test-root-token}, port 8200 exposed),
     * started on first access.
     *
     * @return the running Vault container
     */
    public static GenericContainer<?> vault() {
        return started(VAULT);
    }

    /**
     * The shared LocalStack (AWS emulation for S3/etc., edge port 4566 exposed), started on first access.
     *
     * @return the running LocalStack container
     */
    public static GenericContainer<?> localstack() {
        return started(LOCALSTACK);
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
