package ae.gov.dubaicustoms.platform.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class DomainEventHandlerTest {

    static class Handlers {
        @DomainEventHandler
        void onOrderPlaced(String event) {
        }
    }

    @Test
    void isRetainedAtRuntimeOnAnnotatedMethods() throws NoSuchMethodException {
        Method method = Handlers.class.getDeclaredMethod("onOrderPlaced", String.class);

        assertThat(method.isAnnotationPresent(DomainEventHandler.class)).isTrue();
    }
}
