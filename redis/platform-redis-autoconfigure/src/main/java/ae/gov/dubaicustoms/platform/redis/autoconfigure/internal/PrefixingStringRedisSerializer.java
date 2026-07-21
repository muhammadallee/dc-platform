package ae.gov.dubaicustoms.platform.redis.autoconfigure.internal;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * A {@link RedisSerializer} for {@code String} keys that prepends a fixed prefix on write and strips
 * it on read, so keys from different services stay namespaced in a shared Redis.
 *
 * <p><strong>Caveat.</strong> Server-side key scans ({@code SCAN}/{@code KEYS}) operate on the raw
 * stored keys, so any pattern must include the prefix — this serializer only rewrites keys that flow
 * through the {@code StringRedisTemplate} value/key API, not literal patterns you build yourself.
 */
public final class PrefixingStringRedisSerializer implements RedisSerializer<String> {

    private final String prefix;

    /**
     * @param prefix the prefix prepended to every key; never {@code null}
     */
    public PrefixingStringRedisSerializer(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix must not be null");
    }

    @Override
    public byte[] serialize(String value) {
        return value == null ? null : (prefix + value).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String deserialize(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }
}
