package ae.gov.dubaicustoms.platform.messaging.outbox;

import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** What the outbox tests share: the shipped DDL, settings, a serializer and a recording transport. */
final class OutboxTestSupport {

    static final EventSerializer TEXT = new EventSerializer() {
        @Override
        public byte[] serialize(Object payload) {
            return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T deserialize(byte[] value, Class<T> type) {
            return type.cast(new String(value, StandardCharsets.UTF_8));
        }

        @Override
        public String contentType() {
            return "text/plain";
        }
    };

    private OutboxTestSupport() {
    }

    /** The shipped migration, split into statements, comments dropped. */
    static List<String> ddl() throws Exception {
        try (var in = OutboxTestSupport.class.getResourceAsStream("platform_outbox.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
            return java.util.Arrays.stream(sql.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
    }

    static OutboxSettings settings(int batchSize, int maxRejections) {
        return new OutboxSettings(batchSize, Duration.ofMillis(20), maxRejections, Duration.ZERO, Duration.ZERO,
                Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofDays(1), Duration.ofDays(1),
                Duration.ofSeconds(10));
    }

    /** Records every send; {@code onSend} may throw to fail it. */
    static final class RecordingTransport implements EventTransport {

        final List<Sent> sent = new CopyOnWriteArrayList<>();
        volatile Consumer<String> onSend = destination -> { };

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void send(String destination, byte[] key, byte[] value, Map<String, String> headers) {
            onSend.accept(destination);
            sent.add(new Sent(destination, key, value, headers));
        }

        @Override
        public Subscription subscribe(String destination, String group, TransportListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    record Sent(String destination, byte[] key, byte[] value, Map<String, String> headers) {
        String eventId() {
            return headers.get("eventId");
        }
    }
}
