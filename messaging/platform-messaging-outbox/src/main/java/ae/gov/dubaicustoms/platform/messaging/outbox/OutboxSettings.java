package ae.gov.dubaicustoms.platform.messaging.outbox;

import java.time.Duration;
import java.util.Objects;

/**
 * The drainer's bounds. Immutable.
 *
 * @param batchSize the most rows claimed in one transaction, and so the most duplicates a killed instance can cause
 * @param pollInterval the pause when nothing is due
 * @param maxRejections how many times the sink may reject a row before it is parked. Outages do not count, so a long
 *     outage parks nothing
 * @param initialBackoff the first per-row retry delay; doubles per attempt
 * @param maxBackoff the longest per-row retry delay, and the longest pause after a failure
 * @param sentRetention how long a sent row is kept before deletion
 * @param statementTimeout the timeout on each statement the drainer runs
 * @param gaugeInterval how often the backlog metrics are refreshed
 * @param retentionInterval how often sent rows are deleted
 * @param stopTimeout how long a stop waits for the current batch
 * @since 1.0.0
 */
public record OutboxSettings(int batchSize, Duration pollInterval, int maxRejections, Duration initialBackoff,
        Duration maxBackoff, Duration sentRetention, Duration statementTimeout, Duration gaugeInterval,
        Duration retentionInterval, Duration stopTimeout) {

    /** Rejects bounds the drainer cannot work with. */
    public OutboxSettings {
        if (batchSize < 1 || maxRejections < 1) {
            throw new IllegalArgumentException("batch size and max rejections must be at least 1");
        }
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        Objects.requireNonNull(maxBackoff, "maxBackoff must not be null");
        Objects.requireNonNull(sentRetention, "sentRetention must not be null");
        Objects.requireNonNull(statementTimeout, "statementTimeout must not be null");
        Objects.requireNonNull(gaugeInterval, "gaugeInterval must not be null");
        Objects.requireNonNull(retentionInterval, "retentionInterval must not be null");
        Objects.requireNonNull(stopTimeout, "stopTimeout must not be null");
        requirePositive("poll interval", pollInterval);
        requirePositive("gauge interval", gaugeInterval);
        requirePositive("retention interval", retentionInterval);
        requirePositive("sent retention", sentRetention);
        if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("backoffs must not be negative");
        }
        // JDBC takes whole seconds, and 0 means no limit; Thread.join(0) means wait for ever.
        if (statementTimeout.toSeconds() < 1) {
            throw new IllegalArgumentException("statement timeout must be at least 1 second");
        }
        if (stopTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("stop timeout must be at least 1 millisecond");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * The retry delay after a row's {@code attempts}-th attempt: doubling, capped.
     *
     * @param attempts the attempts made so far, at least 1
     * @return the delay; never {@code null}
     */
    Duration backoff(int attempts) {
        int doublings = Math.min(attempts - 1, 30);
        Duration delay = initialBackoff.multipliedBy(1L << doublings);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
