package ae.gov.dubaicustoms.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards the AuditEvent value contract: required components and immutable defensive copies. */
class AuditEventTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsNullRequiredComponents() {
        assertThatNullPointerException().isThrownBy(() ->
                new AuditEvent(null, "actor", "r", Outcome.SUCCESS, AT, "cid", Map.of()));
        assertThatNullPointerException().isThrownBy(() ->
                new AuditEvent("a", null, "r", Outcome.SUCCESS, AT, "cid", Map.of()));
        assertThatNullPointerException().isThrownBy(() ->
                new AuditEvent("a", "actor", "r", null, AT, "cid", Map.of()));
        assertThatNullPointerException().isThrownBy(() ->
                new AuditEvent("a", "actor", "r", Outcome.SUCCESS, null, "cid", Map.of()));
    }

    @Test
    void allowsNullResourceAndCorrelationId() {
        AuditEvent event = new AuditEvent("a", "actor", null, Outcome.SUCCESS, AT, null, Map.of());

        assertThat(event.resource()).isNull();
        assertThat(event.correlationId()).isNull();
    }

    @Test
    void nullDetailsBecomeEmptyMap() {
        AuditEvent event = new AuditEvent("a", "actor", "r", Outcome.SUCCESS, AT, "cid", null);

        assertThat(event.details()).isEmpty();
    }

    @Test
    void detailsAreDefensivelyCopiedAndImmutable() {
        Map<String, Object> source = new HashMap<>();
        source.put("k", "v");
        AuditEvent event = new AuditEvent("a", "actor", "r", Outcome.SUCCESS, AT, "cid", source);

        source.put("mutated", "after");

        assertThat(event.details()).containsExactly(Map.entry("k", "v"));
        assertThatThrownBy(() -> event.details().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
