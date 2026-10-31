package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.messaging.autoconfigure.PlatformMessagingAutoConfiguration;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The registrar is a BeanPostProcessor: whatever it obtains while post-processors are being registered skips
 * post-processing. A MeterRegistry obtained then is never configured by Boot, and the service silently loses its JVM, pool
 * and repository meters and its common tags (HI-0084, found by the phase-04 consumer).
 */
class EventHandlerRegistrarLazinessTest {

    private static <T> T fail() {
        throw new AssertionError("resolved a collaborator before any @EventHandler needed it");
    }

    @Test
    void itsFactoryMethodIsStatic() {
        assertThat(Arrays.stream(PlatformMessagingAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("eventHandlerRegistrar")))
                .singleElement().satisfies(method -> assertThat(Modifier.isStatic(method.getModifiers())).isTrue());
    }

    @Test
    void itResolvesNothingUntilABeanHasAHandler() {
        var registrar = new EventHandlerRegistrar(OutboundFail::transport, OutboundFail::serializer, OutboundFail::properties,
                "group", OutboundFail::registry);

        assertThat(registrar.postProcessAfterInitialization(new Object(), "noHandlers")).isNotNull();
    }

    @Test
    void aBeanWithAHandlerDoesNotResolveTheTransportDuringItsOwnPostProcessing() {
        // The transport may need that very bean: a template customizer that also handles events.
        var registrar = new EventHandlerRegistrar(OutboundFail::transport, OutboundFail::serializer, OutboundFail::properties,
                "group", OutboundFail::registry);

        assertThat(registrar.postProcessAfterInitialization(new Handler(), "handler")).isNotNull();
    }

    static final class Handler {
        @ae.gov.dubaicustoms.platform.messaging.EventHandler(destination = "dc.orders")
        void on(String event) {
        }
    }

    private static final class OutboundFail {
        static ae.gov.dubaicustoms.platform.messaging.spi.EventTransport transport() {
            return fail();
        }

        static ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer serializer() {
            return fail();
        }

        static ae.gov.dubaicustoms.platform.messaging.autoconfigure.MessagingProperties properties() {
            return fail();
        }

        static io.micrometer.core.instrument.MeterRegistry registry() {
            return fail();
        }
    }
}
