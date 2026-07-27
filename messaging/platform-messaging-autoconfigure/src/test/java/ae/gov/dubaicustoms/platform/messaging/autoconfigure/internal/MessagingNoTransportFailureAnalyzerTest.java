package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.diagnostics.FailureAnalysis;

class MessagingNoTransportFailureAnalyzerTest {

    private final MessagingNoTransportFailureAnalyzer analyzer = new MessagingNoTransportFailureAnalyzer();

    @Test
    void namesTheThreeTransportStartersWhenPublisherMissing() {
        FailureAnalysis analysis = analyzer.analyze(new NoSuchBeanDefinitionException(EventPublisher.class));

        assertThat(analysis).isNotNull();
        assertThat(analysis.getAction())
                .contains("platform-starter-messaging-inmemory")
                .contains("platform-starter-messaging-kafka")
                .contains("platform-starter-messaging-rabbit");
    }

    @Test
    void alsoFiresForMissingTransport() {
        assertThat(analyzer.analyze(new NoSuchBeanDefinitionException(EventTransport.class))).isNotNull();
    }

    @Test
    void staysSilentForUnrelatedBeans() {
        assertThat(analyzer.analyze(new NoSuchBeanDefinitionException(String.class))).isNull();
    }
}
