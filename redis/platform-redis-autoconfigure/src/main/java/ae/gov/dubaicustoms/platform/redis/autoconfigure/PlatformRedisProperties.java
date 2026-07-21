package ae.gov.dubaicustoms.platform.redis.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the Redis client conventions. Bound from {@code dc.platform.redis.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformRedisAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned). Connection tuning (host, port, timeout,
 * pool) stays on Boot's own {@code spring.data.redis.*} keys; this record adds only the platform
 * key-prefix convention.
 *
 * @param enabled master kill switch for the whole capability
 * @param keyPrefix the prefix prepended to every {@code StringRedisTemplate} key; when blank, the
 *        application name followed by {@code ':'} is used
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.redis")
public record PlatformRedisProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Key prefix; blank means "&lt;spring.application.name&gt;:". */
        String keyPrefix) {
}
