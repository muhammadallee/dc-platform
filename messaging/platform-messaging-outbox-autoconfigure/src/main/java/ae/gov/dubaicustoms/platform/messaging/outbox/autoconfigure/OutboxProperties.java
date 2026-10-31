package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure;

import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the transactional outbox. Bound from {@code dc.platform.messaging.outbox.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code OutboxAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled {@code false} publishes after commit, no longer guaranteed, and logs a WARN saying so
 * @param poolSize the most database connections the drainer holds, on its own pool
 * @param batchSize the most rows claimed in one transaction, and so the most duplicates a killed instance can cause
 * @param pollInterval the pause when nothing is due
 * @param maxRejections how many times the sink may reject a row before it is parked; outages do not count
 * @param initialBackoff the first per-row retry delay; doubles per attempt
 * @param maxBackoff the longest per-row retry delay and the longest pause after a failure
 * @param sentRetention how long a sent row is kept before deletion
 * @param statementTimeout the timeout on each statement the drainer runs
 * @param gaugeInterval how often the backlog metrics are refreshed
 * @param retentionInterval how often sent rows are deleted
 * @param stopTimeout how long a shutdown waits for the batch in flight
 * @param readTimeout the Oracle driver's read timeout on the drainer's connections, unless the application pool sets one
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.messaging.outbox")
public record OutboxProperties(
        /** False publishes after commit, no longer guaranteed, and logs a WARN saying so. */
        @DefaultValue("true") boolean enabled,
        /** The most database connections the drainer holds, on its own pool. */
        @DefaultValue("2") int poolSize,
        /** The most rows claimed in one transaction, and so the most duplicates a killed instance can cause. */
        @DefaultValue("50") int batchSize,
        /** The pause when nothing is due. */
        @DefaultValue("1s") Duration pollInterval,
        /** How many times the sink may reject a row before it is parked; outages do not count. */
        @DefaultValue("10") int maxRejections,
        /** The first per-row retry delay; doubles per attempt. */
        @DefaultValue("1s") Duration initialBackoff,
        /** The longest per-row retry delay and the longest pause after a failure. */
        @DefaultValue("5m") Duration maxBackoff,
        /** How long a sent row is kept before deletion. */
        @DefaultValue("24h") Duration sentRetention,
        /** The timeout on each statement the drainer runs. */
        @DefaultValue("30s") Duration statementTimeout,
        /** How often the backlog metrics are refreshed. */
        @DefaultValue("5s") Duration gaugeInterval,
        /** How often sent rows are deleted. */
        @DefaultValue("5m") Duration retentionInterval,
        /** How long a shutdown waits for the batch in flight. */
        @DefaultValue("30s") Duration stopTimeout,
        /** The Oracle driver's read timeout on the drainer's connections, unless the application pool sets one. */
        @DefaultValue("60s") Duration readTimeout) {

    /**
     * The drainer's settings.
     *
     * @return the settings; never {@code null}
     */
    OutboxSettings settings() {
        return new OutboxSettings(batchSize, pollInterval, maxRejections, initialBackoff, maxBackoff, sentRetention,
                statementTimeout, gaugeInterval, retentionInterval, stopTimeout);
    }
}
