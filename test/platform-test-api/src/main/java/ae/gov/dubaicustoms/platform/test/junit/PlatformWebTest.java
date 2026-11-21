package ae.gov.dubaicustoms.platform.test.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apiguardian.api.API;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

/**
 * Web slice: boots the platform with a mock servlet environment and an auto-configured
 * {@code MockMvc}, so controllers can be driven end to end through the platform's web stack.
 *
 * <p>Composes {@link SpringBootTest} ({@link SpringBootTest.WebEnvironment#MOCK}) +
 * {@link AutoConfigureMockMvc} + the {@code test} profile. Because the full platform context boots,
 * the RFC-9457 error handling, request validation, and security filter chain are all active — assert
 * error bodies with {@code assertThatProblem(...)} and authenticate requests with
 * {@code ae.gov.dubaicustoms.platform.test.security.TestTokens} (spring-security-test is on the
 * classpath, so {@code MockMvc} honours JWT post-processors).
 *
 * <pre>{@code
 * @PlatformWebTest
 * class OrderControllerTest {
 *     @Autowired MockMvc mvc;
 *     @Test void rejectsUnknownOrder() throws Exception {
 *         mvc.perform(get("/orders/unknown").with(TestTokens.user("alice").roles("VIEWER").jwt()))
 *            .andExpect(status().isNotFound());
 *     }
 * }
 * }</pre>
 *
 * <p>Actuator endpoints are served on the mock servlet here ({@code management.server.port} empty), although the
 * platform runs them on their own port in a real deployment (ND13).
 *
 * @since 0.2.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
// The platform puts actuator on its own port (ND13), which a mock servlet environment never serves; the slice keeps it
// on the mock servlet so MockMvc reaches the actuator endpoints as before.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "management.server.port=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@API(status = API.Status.STABLE, since = "0.1.0")
public @interface PlatformWebTest {
}
