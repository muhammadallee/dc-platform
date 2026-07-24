/**
 * Docker fixtures for {@code @Tag("docker")} integration tests: {@link
 * ae.gov.dubaicustoms.platform.test.container.Containers} exposes shared, lazily-started singleton
 * Testcontainers, and {@link ae.gov.dubaicustoms.platform.test.container.DockerAvailable} is the
 * assumption guard that skips those tests when no Docker daemon is present.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.test.container;
