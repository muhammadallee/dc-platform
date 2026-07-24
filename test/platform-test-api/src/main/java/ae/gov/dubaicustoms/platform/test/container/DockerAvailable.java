package ae.gov.dubaicustoms.platform.test.container;

import org.testcontainers.DockerClientFactory;

/**
 * Assumption guard for {@code @Tag("docker")} tests: skip (rather than fail) when no Docker daemon is
 * reachable, so the same suite is green on a laptop without Docker and on CI with it.
 *
 * <pre>{@code
 * @Tag("docker")
 * class RealKafkaTest {
 *     @BeforeAll static void requireDocker() { assumeTrue(DockerAvailable.check()); }
 * }
 * }</pre>
 *
 * <p>The probe is performed once and cached: repeated calls are cheap. Thread-safe.
 *
 * @since 0.2.0
 */
public final class DockerAvailable {

    private static volatile Boolean cached;

    private DockerAvailable() {
    }

    /**
     * Returns whether a Docker daemon is reachable. Never throws — a missing or unreachable daemon
     * yields {@code false}. The result is probed once and cached for the JVM's lifetime.
     *
     * @return {@code true} if Testcontainers can reach a Docker daemon
     */
    public static boolean check() {
        Boolean result = cached;
        if (result == null) {
            synchronized (DockerAvailable.class) {
                result = cached;
                if (result == null) {
                    result = probe();
                    cached = result;
                }
            }
        }
        return result;
    }

    private static boolean probe() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
}
