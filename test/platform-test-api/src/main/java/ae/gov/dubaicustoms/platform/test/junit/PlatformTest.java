package ae.gov.dubaicustoms.platform.test.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full platform in a test context with the platform's test defaults applied.
 *
 * <p>Composes {@link SpringBootTest} with:
 * <ul>
 *   <li><b>the {@code test} profile</b> ({@link ActiveProfiles}) — the platform's logging capability
 *       emits human-readable console logs (not JSON) under this profile, so test output stays
 *       readable;</li>
 *   <li><b>in-memory providers</b> — the platform's defaults (in-memory messaging transport, Caffeine
 *       cache, filesystem storage, JDBC/H2 locking) activate with no external infrastructure, so a
 *       {@code @PlatformTest} needs no Docker, network, or credentials;</li>
 *   <li><b>security-mock-friendly wiring</b> — pair with {@code @AutoConfigureMockMvc} (or use
 *       {@link PlatformWebTest}) and {@code ae.gov.dubaicustoms.platform.test.security.TestTokens} to
 *       drive authenticated requests without a real identity provider.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @PlatformTest
 * class OrderServiceTest {
 *     @Autowired OrderService service;
 *     @Test void placesOrder() { ... }
 * }
 * }</pre>
 *
 * <p>The test must be able to find a {@code @SpringBootConfiguration} (typically the service's
 * {@code @SpringBootApplication}) on the classpath, exactly as with a plain {@link SpringBootTest}.
 *
 * @since 0.2.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@ActiveProfiles("test")
public @interface PlatformTest {
}
