package ae.gov.dubaicustoms.platform.audit.spi;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Confirms AuditSink is a functional interface a provider can implement with a lambda. */
class AuditSinkContractTest {

    @Test
    void isImplementableAsLambda() {
        List<AuditEvent> written = new CopyOnWriteArrayList<>();
        AuditSink sink = written::add;

        AuditEvent event = new AuditEvent(
                "order.create", "u1", "order:42", Outcome.SUCCESS, Instant.now(), "cid", Map.of());
        sink.write(event);

        assertThat(written).containsExactly(event);
    }
}
