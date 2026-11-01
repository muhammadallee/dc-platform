package ae.gov.dubaicustoms.platform.messaging.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EventTransportDefaultsTest {

    @Test
    void aTransportDoesNotDeadLetterNativelyUnlessItSaysSo() {
        EventTransport transport = new EventTransport() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
            }

            @Override
            public Subscription subscribe(String destination, String group, TransportListener listener) {
                return () -> { };
            }

            @Override
            public void close() {
            }
        };

        assertThat(transport.deadLettersNatively()).isFalse();
    }

    @Test
    void theRequeueExceptionSaysWhy() {
        assertThat(new EventTransport.TransportListener.RequeueException("stopping")).hasMessage("stopping");
        assertThat(new EventTransport.TransportListener.DeadLetterException("poison")).hasMessage("poison").hasNoCause();
    }
}
