/**
 * The logging capability contract: {@link ae.gov.dubaicustoms.platform.logging.Kv} for
 * structured key-value log arguments and
 * {@link ae.gov.dubaicustoms.platform.logging.LogSanitizer} for scrubbing sensitive values
 * before they reach an appender.
 *
 * <p>Deliberately free of slf4j/logstash types: the JSON encoding is an
 * {@code platform-logging-autoconfigure} concern; application code depends only on this module.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.logging;
