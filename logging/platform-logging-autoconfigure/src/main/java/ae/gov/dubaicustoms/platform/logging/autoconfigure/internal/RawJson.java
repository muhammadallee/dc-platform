package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the text of a {@code raw(...)} argument as exactly one JSON value, losing nothing: members keep
 * their order and duplicates, numbers stay exact ({@link BigDecimal}, integers as parsed), strings and
 * names are decoded. Objects become {@link RawObject}, arrays {@link List}. Text that is not exactly one
 * JSON value (invalid, empty, trailing text) fails with an {@link IOException}. Stateless and thread-safe.
 */
final class RawJson {

    private static final JsonFactory FACTORY = new JsonFactory();

    private RawJson() {
    }

    /** A JSON object as parsed: its members in order, duplicates kept. */
    static final class RawObject {

        final List<Map.Entry<String, Object>> members = new ArrayList<>();

        void add(String name, Object value) {
            members.add(new SimpleImmutableEntry<>(name, value));
        }
    }

    static Object parse(String text) throws IOException {
        try (JsonParser parser = FACTORY.createParser(text)) {
            parser.nextToken();
            Object value = read(parser);
            if (parser.nextToken() != null) {
                throw new JsonParseException(parser, "trailing text after the JSON value");
            }
            return value;
        }
    }

    private static Object read(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == null) {
            throw new JsonParseException(parser, "no JSON value");
        }
        return switch (token) {
            case START_OBJECT -> {
                RawObject object = new RawObject();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName();
                    parser.nextToken();
                    object.add(name, read(parser));
                }
                yield object;
            }
            case START_ARRAY -> {
                List<Object> array = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    array.add(read(parser));
                }
                yield array;
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getNumberValue();
            case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new JsonParseException(parser, "unexpected " + token);
        };
    }

    /** Writes a parsed value through the given generator (so its masks apply to every member). */
    static void write(Object value, JsonGenerator generator) throws IOException {
        switch (value) {
            case null -> generator.writeNull();
            case RawObject object -> {
                generator.writeStartObject();
                for (Map.Entry<String, Object> member : object.members) {
                    generator.writeFieldName(member.getKey());
                    write(member.getValue(), generator);
                }
                generator.writeEndObject();
            }
            case List<?> array -> {
                generator.writeStartArray();
                for (Object element : array) {
                    write(element, generator);
                }
                generator.writeEndArray();
            }
            case String text -> generator.writeString(text);
            case BigDecimal number -> generator.writeNumber(number);
            case BigInteger number -> generator.writeNumber(number);
            case Long number -> generator.writeNumber(number);
            case Integer number -> generator.writeNumber(number);
            case Boolean bool -> generator.writeBoolean(bool);
            default -> generator.writeString(String.valueOf(value));
        }
    }
}
