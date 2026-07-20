package ae.gov.dubaicustoms.platform.messaging.testing;

import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Registers a {@link TestEventTransport} as the test context's {@link EventTransport}, taking
 * priority ({@code @Primary}) over any real transport that might also be on the classpath.
 *
 * <pre>{@code
 * @SpringBootTest
 * @AutoConfigureTestTransport
 * class OrdersServiceTest {
 *     @Autowired TestEventTransport transport;
 * }
 * }</pre>
 *
 * @since 0.2.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(AutoConfigureTestTransport.TestTransportConfiguration.class)
public @interface AutoConfigureTestTransport {

    /** Registers the {@link TestEventTransport} bean. */
    @Configuration(proxyBeanMethods = false)
    class TestTransportConfiguration {

        @Bean
        @Primary
        TestEventTransport testEventTransport() {
            return new TestEventTransport();
        }
    }
}
