package ae.gov.dubaicustoms.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @EventType(value = "OrderPlaced", version = 2)
    record OrderPlaced(String orderId) {}

    record Unannotated(String value) {}

    @Test
    void derivesEventTypeAndVersionFromAnnotation() {
        var envelope = EventEnvelope.of(new OrderPlaced("o-1")).build();

        assertThat(envelope.eventType()).isEqualTo("OrderPlaced");
        assertThat(envelope.eventVersion()).isEqualTo(2);
        assertThat(envelope.payload()).isEqualTo(new OrderPlaced("o-1"));
    }

    @Test
    void fallsBackToSimpleClassNameAndVersion1WhenUnannotated() {
        var envelope = EventEnvelope.of(new Unannotated("v")).build();

        assertThat(envelope.eventType()).isEqualTo("Unannotated");
        assertThat(envelope.eventVersion()).isEqualTo(1);
    }

    @Test
    void carriesKeyAndHeaders() {
        var envelope = EventEnvelope.of(new OrderPlaced("o-1"))
                .key("o-1")
                .header("correlationId", "corr-1")
                .build();

        assertThat(envelope.key()).isEqualTo("o-1");
        assertThat(envelope.headers()).containsEntry("correlationId", "corr-1");
    }

    @Test
    void headersAreUnmodifiable() {
        var envelope = EventEnvelope.of(new OrderPlaced("o-1")).build();

        assertThatThrownBy(() -> envelope.headers().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultsOccurredAtToNowWhenNotSet() {
        Instant before = Instant.now();

        var envelope = EventEnvelope.of(new OrderPlaced("o-1")).build();

        assertThat(envelope.occurredAt()).isAfterOrEqualTo(before);
    }

    @Test
    void honorsExplicitOccurredAt() {
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");

        var envelope = EventEnvelope.of(new OrderPlaced("o-1")).occurredAt(fixed).build();

        assertThat(envelope.occurredAt()).isEqualTo(fixed);
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> EventEnvelope.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNulls() {
        assertThatThrownBy(() -> new EventEnvelope<>(null, 1, null, "p", java.util.Map.of(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventEnvelope<>("t", 1, null, null, java.util.Map.of(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventEnvelope<>("t", 1, null, "p", null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventEnvelope<>("t", 1, null, "p", java.util.Map.of(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
