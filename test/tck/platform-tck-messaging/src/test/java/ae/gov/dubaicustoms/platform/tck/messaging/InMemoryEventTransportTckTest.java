package ae.gov.dubaicustoms.platform.tck.messaging;

import ae.gov.dubaicustoms.platform.messaging.inmemory.InMemoryEventTransport;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;

/**
 * Certifies the in-memory reference transport against {@link EventTransportTck}, docker-free — the
 * proof that the TCK itself is satisfiable by the platform's default provider.
 */
class InMemoryEventTransportTckTest extends EventTransportTck {

    @Override
    protected EventTransport transport() {
        // Fast redelivery so retriesUntilAFailingListenerSucceeds does not wait on the default backoff.
        return new InMemoryEventTransport(1000, 3, java.time.Duration.ofMillis(20));
    }
}
