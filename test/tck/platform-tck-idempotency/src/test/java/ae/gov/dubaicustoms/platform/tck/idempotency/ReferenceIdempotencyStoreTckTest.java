package ae.gov.dubaicustoms.platform.tck.idempotency;

import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Certifies a minimal in-memory reference {@link IdempotencyStore} against {@link IdempotencyStoreTck},
 * docker-free. This both proves the kit is coherent and provides the canonical example a provider
 * follows: atomic {@code putIfAbsent} via {@link ConcurrentHashMap#compute} and self-expiry off an
 * injected clock. The shipped JDBC store is certified separately in platform-idempotency-autoconfigure.
 */
class ReferenceIdempotencyStoreTckTest extends IdempotencyStoreTck {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-22T10:00:00Z"));

    @Override
    protected IdempotencyStore store() {
        return new ReferenceIdempotencyStore(clock);
    }

    @Override
    protected void advanceTimePast(Duration ttl) {
        clock.advance(ttl.plusSeconds(1));
    }

    /** Reference store: a key is live until its expiry instant; {@code compute} makes the check-and-set atomic. */
    private static final class ReferenceIdempotencyStore implements IdempotencyStore {
        private final Map<String, Instant> expiryByKey = new ConcurrentHashMap<>();
        private final Clock clock;

        ReferenceIdempotencyStore(Clock clock) {
            this.clock = clock;
        }

        @Override
        public boolean putIfAbsent(String key, Duration ttl) {
            Instant now = clock.instant();
            boolean[] recorded = {false};
            expiryByKey.compute(key, (k, expiry) -> {
                if (expiry != null && expiry.isAfter(now)) {
                    return expiry; // still live → duplicate, leave the recorded flag false
                }
                recorded[0] = true;
                return now.plus(ttl);
            });
            return recorded[0];
        }
    }

    /** A hand-advanced clock so TTL expiry is exercised deterministically without sleeping. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
