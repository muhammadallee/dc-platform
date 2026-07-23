package ae.gov.dubaicustoms.platform.audit.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.Outcome;
import ae.gov.dubaicustoms.platform.audit.messaging.autoconfigure.internal.MessagingAuditSink;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** ContextRunner matrix for the messaging audit sink plus its publish behavior. */
class MessagingAuditSinkAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessagingAuditSinkAutoConfiguration.class));

    @Test
    void activeWhenPublisherPresent() {
        runner.withBean("pub", EventPublisher.class, RecordingPublisher::new).run(ctx -> {
            assertThat(ctx).hasSingleBean(AuditSink.class);
            assertThat(ctx.getBean(AuditSink.class)).isInstanceOf(MessagingAuditSink.class);
        });
    }

    @Test
    void inactiveWithoutPublisher() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(AuditSink.class));
    }

    @Test
    void killSwitchDisables() {
        runner.withBean("pub", EventPublisher.class, RecordingPublisher::new)
                .withPropertyValues("dc.platform.audit.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AuditSink.class));
    }

    @Test
    void backsOffWhenUserSinkPresent() {
        AuditSink mine = event -> { };
        runner.withBean("pub", EventPublisher.class, RecordingPublisher::new)
                .withBean("mine", AuditSink.class, () -> mine)
                .run(ctx -> assertThat(ctx.getBean(AuditSink.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenPublisherClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(EventPublisher.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MessagingAuditSinkAutoConfiguration.class));
    }

    @Test
    void publishesEventsToDcAuditDestination() {
        RecordingPublisher publisher = new RecordingPublisher();
        runner.withBean("pub", EventPublisher.class, () -> publisher).run(ctx -> {
            AuditEvent event = new AuditEvent("order.create", "u1", "order:42", Outcome.SUCCESS,
                    Instant.parse("2026-01-01T00:00:00Z"), "cid", Map.of());
            ctx.getBean(AuditSink.class).write(event);

            assertThat(publisher.destinations).containsExactly(MessagingAuditSink.DESTINATION);
            assertThat(publisher.payloads).containsExactly(event);
        });
    }

    /** Records publish calls so the test asserts destination + payload without a mock. */
    static final class RecordingPublisher implements EventPublisher {
        private final List<String> destinations = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();

        @Override
        public void publish(String destination, EventEnvelope<?> event) {
            destinations.add(destination);
            payloads.add(event.payload());
        }

        @Override
        public void publish(String destination, Object payload) {
            destinations.add(destination);
            payloads.add(payload);
        }
    }
}
