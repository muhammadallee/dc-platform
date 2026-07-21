package ae.gov.dubaicustoms.platform.redis.autoconfigure.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

class StringRedisTemplateKeyPrefixPostProcessorTest {

    private final StringRedisTemplateKeyPrefixPostProcessor processor =
            new StringRedisTemplateKeyPrefixPostProcessor("orders:");

    @Test
    void installsPrefixingKeySerializerOnStringRedisTemplate() {
        StringRedisTemplate template = new StringRedisTemplate(mock(RedisConnectionFactory.class));

        Object result = processor.postProcessBeforeInitialization(template, "stringRedisTemplate");

        assertThat(result).isSameAs(template);
        @SuppressWarnings("unchecked")
        RedisSerializer<String> keySerializer = (RedisSerializer<String>) template.getKeySerializer();
        assertThat(new String(keySerializer.serialize("42"), UTF_8)).isEqualTo("orders:42");
    }

    @Test
    void ignoresOtherBeans() {
        Object bean = new Object();
        assertThat(processor.postProcessBeforeInitialization(bean, "x")).isSameAs(bean);
    }
}
