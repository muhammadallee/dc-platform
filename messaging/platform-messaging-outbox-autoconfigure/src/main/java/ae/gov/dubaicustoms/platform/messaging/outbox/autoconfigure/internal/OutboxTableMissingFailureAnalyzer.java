package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxTableMissingException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

// Turns the drainer's startup check into the action that fixes it: the platform never creates the table (a runtime user
// has no DDL rights, and a migration in a jar would collide with the service's version numbers, D118).
public class OutboxTableMissingFailureAnalyzer extends AbstractFailureAnalyzer<OutboxTableMissingException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, OutboxTableMissingException cause) {
        return new FailureAnalysis(
                "The transactional outbox is on, but the platform_outbox table is missing or this database user cannot "
                        + "read it, so published events could not be stored or delivered.",
                "Copy the DDL from classpath:ae/gov/dubaicustoms/platform/messaging/outbox/platform_outbox.sql "
                        + "(platform-messaging-outbox) into your own Flyway migrations, and grant select, insert, update, "
                        + "delete on it to the runtime user. To run without the outbox, set "
                        + "dc.platform.messaging.outbox.enabled=false: publishes are then no longer guaranteed.",
                cause);
    }
}
