package ae.gov.dubaicustoms.platform.messaging.testing;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

class AutoConfigureTestTransportTest {

    @AutoConfigureTestTransport
    @Configuration
    static class Annotated {
    }

    @Test
    void registersTestEventTransportAsThePrimaryEventTransportBean() {
        try (var context = new AnnotationConfigApplicationContext(Annotated.class)) {
            assertThat(context.getBean(TestEventTransport.class)).isNotNull();
            assertThat(context.getBean(EventTransport.class)).isInstanceOf(TestEventTransport.class);
        }
    }
}
