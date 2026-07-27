package ae.gov.dubaicustoms.platform.test.junit;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apiguardian.api.API;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * JPA slice with the platform's data conventions layered on top of Spring Boot's {@link DataJpaTest}.
 *
 * <p>{@link DataJpaTest} provides the fast persistence slice: an embedded H2 database, a configured
 * {@code EntityManager}/{@code TestEntityManager}, transactional (rolled-back) test methods, and no
 * web or service beans. This annotation adds {@link PlatformDataJpaAutoConfiguration} so the
 * platform's JPA conventions (auditing, naming, repository base) are exercised exactly as in
 * production.
 *
 * <pre>{@code
 * @PlatformDataTest
 * class OrderRepositoryTest {
 *     @Autowired OrderRepository repository;
 *     @Test void persists() { ... }
 * }
 * }</pre>
 *
 * <p>Requires {@code platform-data-jpa-autoconfigure} on the classpath (bundled by
 * {@code platform-starter-test} when the data capability is in use).
 *
 * @since 0.2.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@DataJpaTest
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
@ActiveProfiles("test")
@API(status = API.Status.STABLE, since = "0.1.0")
public @interface PlatformDataTest {
}
