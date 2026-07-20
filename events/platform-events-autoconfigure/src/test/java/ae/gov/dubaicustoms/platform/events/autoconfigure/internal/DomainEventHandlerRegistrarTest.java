package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.events.DomainEventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.support.StaticApplicationContext;

class DomainEventHandlerRegistrarTest {

    record OrderPlaced(String orderId) implements DomainEvent {}

    record OrderCancelled(String orderId) implements DomainEvent {}

    static class Handlers {
        final List<OrderPlaced> received = new CopyOnWriteArrayList<>();

        @DomainEventHandler
        void onOrderPlaced(OrderPlaced event) {
            received.add(event);
        }
    }

    private static PayloadApplicationEvent<Object> payloadEvent(Object payload) {
        var source = new Object();
        return new PayloadApplicationEvent<>(source, payload);
    }

    @Test
    void dispatchesToTheMatchingHandlerMethod() {
        var registrar = new DomainEventHandlerRegistrar(new ImmediateDispatcher());
        var handlers = new Handlers();
        registrar.postProcessAfterInitialization(handlers, "handlers");

        registrar.onApplicationEvent(payloadEvent(new OrderPlaced("o-1")));

        assertThat(handlers.received).containsExactly(new OrderPlaced("o-1"));
    }

    @Test
    void ignoresEventsOfAnUnrelatedType() {
        var registrar = new DomainEventHandlerRegistrar(new ImmediateDispatcher());
        var handlers = new Handlers();
        registrar.postProcessAfterInitialization(handlers, "handlers");

        registrar.onApplicationEvent(payloadEvent(new OrderCancelled("o-1")));

        assertThat(handlers.received).isEmpty();
    }

    @Test
    void ignoresNonDomainEventPayloads() {
        var registrar = new DomainEventHandlerRegistrar(new ImmediateDispatcher());
        var handlers = new Handlers();
        registrar.postProcessAfterInitialization(handlers, "handlers");

        registrar.onApplicationEvent(payloadEvent("not a domain event"));

        assertThat(handlers.received).isEmpty();
    }

    static class BadSignatureHandler {
        @DomainEventHandler
        void onOrderPlaced(OrderPlaced a, OrderPlaced b) {
        }
    }

    @Test
    void rejectsHandlerMethodWithWrongParameterCount() {
        var registrar = new DomainEventHandlerRegistrar(new ImmediateDispatcher());

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> registrar.postProcessAfterInitialization(new BadSignatureHandler(), "handlers"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void integratesWithARealApplicationContext() {
        var context = new StaticApplicationContext();
        var handlers = new Handlers();
        var registrar = new DomainEventHandlerRegistrar(new ImmediateDispatcher());
        context.getBeanFactory().registerSingleton("handlers", handlers);
        registrar.postProcessAfterInitialization(handlers, "handlers");
        context.addApplicationListener(registrar);
        context.refresh();

        context.publishEvent(new OrderPlaced("o-1"));

        assertThat(handlers.received).containsExactly(new OrderPlaced("o-1"));
    }
}
