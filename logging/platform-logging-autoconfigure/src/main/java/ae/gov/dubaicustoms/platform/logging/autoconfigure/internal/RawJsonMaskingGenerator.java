package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * Wraps the masking generator so a raw JSON value ({@code raw(...)}) is masked like any other:
 * Logstash's masking generator forwards {@code writeRawValue} unread. The raw text is parsed and
 * written back as a tree through this generator, so field-name and value masks apply to its contents
 * (escaped field names included); text that is not JSON is written raw after the pattern masks. One
 * instance per event's generator; not thread-safe, like the generator it wraps.
 */
public final class RawJsonMaskingGenerator extends JsonGeneratorDelegate {

    private static final ObjectMapper RAW = new ObjectMapper();

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
        JsonNode tree;
        try {
            tree = RAW.readTree(text);
        } catch (JsonProcessingException e) {
            super.writeRawValue(masker.maskPatterns(text));
            return;
        }
        // Not delegated: the codec writes the tree through this generator, so the masks see every field.
        writeTree(tree);
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
