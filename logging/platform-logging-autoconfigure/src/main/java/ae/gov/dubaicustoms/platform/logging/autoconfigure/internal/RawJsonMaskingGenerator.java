package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import java.io.IOException;

/**
 * Wraps the masking generator so a raw JSON value ({@code raw(...)}) is masked like any other:
 * Logstash's masking generator forwards {@code writeRawValue} unread. The raw text is read as one JSON
 * value ({@link RawJson}: member order, duplicates and exact numbers kept, escapes decoded) and written
 * back through this generator, so field-name and value masks apply to its contents; text that is not
 * exactly one JSON value is written raw after the pattern masks. One instance per event's generator;
 * not thread-safe, like the generator it wraps.
 */
public final class RawJsonMaskingGenerator extends JsonGeneratorDelegate {

    private final PlatformValueMasker masker;

    /**
     * @param delegate the masking generator; never {@code null}
     * @param masker the pattern masks for raw text that is not JSON; never {@code null}
     */
    public RawJsonMaskingGenerator(JsonGenerator delegate, PlatformValueMasker masker) {
        super(delegate, false);
        this.masker = masker;
    }

    @Override
    public void writeRawValue(String text) throws IOException {
        Object value;
        try {
            value = RawJson.parse(text);
        } catch (IOException e) {
            super.writeRawValue(masker.maskPatterns(text));
            return;
        }
        // Through this generator, not the delegate's raw path: the masks see every member.
        RawJson.write(value, this);
    }

    @Override
    public void writeRawValue(String text, int offset, int len) throws IOException {
        writeRawValue(text.substring(offset, offset + len));
    }

    @Override
    public void writeRawValue(char[] text, int offset, int len) throws IOException {
        writeRawValue(new String(text, offset, len));
    }
}
