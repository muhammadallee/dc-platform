package ae.gov.dubaicustoms.platform.test.junit;

import ae.gov.dubaicustoms.platform.messaging.testing.AutoConfigureTestTransport;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apiguardian.api.API;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Messaging-focused slice: boots the platform with a {@code @Primary}
 * {@code ae.gov.dubaicustoms.platform.messaging.testing.TestEventTransport} standing in for the real
 * transport, so publish-side and handler-side behaviour can be asserted without a broker.
 *
 * <p>Composes {@link SpringBootTest} + the {@code test} profile + {@link AutoConfigureTestTransport}
 * (which registers the recording {@code TestEventTransport} as the context's {@code EventTransport}).
 * Assert sends with {@code EventsAssert.assertThatEvents(transport)} and simulate inbound messages
 * with {@code transport.deliver(...)}.
 *
 * <pre>{@code
 * @PlatformMessagingTest
 * class OrdersEventsTest {
 *     @Autowired TestEventTransport transport;
 *     @Autowired OrderService service;
 *
 *     @Test void publishesOrderPlaced() {
 *         service.place("order-1");
 *         assertThatEvents(transport).sentTo("dc.orders").withType("OrderPlaced");
 *     }
 * }
 * }</pre>
 *
 * <p>Requires {@code platform-messaging-test} on the classpath (bundled by {@code platform-starter-test}).
 *
 * @since 0.2.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestTransport
@API(status = API.Status.STABLE, since = "0.1.0")
public @interface PlatformMessagingTest {
}
