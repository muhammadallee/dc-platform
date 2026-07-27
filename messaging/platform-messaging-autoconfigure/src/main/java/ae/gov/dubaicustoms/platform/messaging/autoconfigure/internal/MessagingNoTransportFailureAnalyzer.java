package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

// Messaging is on the classpath but no transport starter was added, so no EventTransport bean exists
// and EventPublisher never gets built — the injection then fails with a bare NoSuchBeanDefinitionException.
// This analyzer (phase-16 A.2) replaces that with an Action naming the three transport starters. Highest
// precedence so it wins over Boot's generic NoSuchBeanDefinitionFailureAnalyzer for these two types only.
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MessagingNoTransportFailureAnalyzer extends AbstractFailureAnalyzer<NoSuchBeanDefinitionException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, NoSuchBeanDefinitionException cause) {
        Class<?> missing = cause.getBeanType();
        if (missing == null
                || !(EventPublisher.class.equals(missing) || EventTransport.class.equals(missing))) {
            return null; // not our case — let Boot's generic analyzer handle it
        }
        String description = "The messaging capability is on the classpath but no EventTransport provider bean is "
                + "present, so no EventPublisher could be created (a bean of type '" + missing.getName()
                + "' is required but missing).";
        String action = "Add exactly one messaging transport starter to the service: "
                + "platform-starter-messaging-inmemory (development and tests), "
                + "platform-starter-messaging-kafka, or platform-starter-messaging-rabbit. "
                + "See docs/modules/messaging.md#transports.";
        return new FailureAnalysis(description, action, cause);
    }
}
