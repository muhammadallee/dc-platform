package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.logging.Kv;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.util.NameTransformer;
import java.io.IOException;

/**
 * Writes a {@link Kv} nested in a structured value as {@code {"<key>": <value>}} rather than as a
 * record ({@code {"key": …, "value": …}}): the key becomes a JSON field name, so credential-name
 * masking applies to it as to any other field. Unwrapped ({@code fields(kv)}), it contributes the one
 * field {@code "<key>": <value>}. Stateless and thread-safe.
 */
public final class KvJsonSerializer extends StdSerializer<Kv> {

    public KvJsonSerializer() {
        super(Kv.class);
    }

    @Override
    public void serialize(Kv kv, JsonGenerator generator, SerializerProvider provider) throws IOException {
        generator.writeStartObject();
        writeField(kv, generator, provider);
        generator.writeEndObject();
    }

    @Override
    public JsonSerializer<Kv> unwrappingSerializer(NameTransformer unwrapper) {
        return new StdSerializer<>(Kv.class) {
            @Override
            public void serialize(Kv kv, JsonGenerator generator, SerializerProvider provider) throws IOException {
                writeField(kv, generator, provider);
            }

            @Override
            public boolean isUnwrappingSerializer() {
                return true;
            }
        };
    }

    private static void writeField(Kv kv, JsonGenerator generator, SerializerProvider provider) throws IOException {
        generator.writeFieldName(kv.key());
        provider.defaultSerializeValue(kv.value(), generator);
    }
}
