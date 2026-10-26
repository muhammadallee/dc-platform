package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import java.util.Arrays;
import java.util.List;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;

/**
 * The platform's JSON encoder: Logstash's encoder plus redaction (D102) and the opt-in strict MDC
 * mode (D101). Configured from {@code logback-platform.xml}; the setters take the values the logging
 * {@code EnvironmentPostProcessor} resolved before Logback started.
 *
 * <p>Not thread-safe during configuration (Logback configures it on one thread); encoding is
 * thread-safe once started.
 */
public class PlatformJsonEncoder extends LogstashEncoder {

    /** MDC keys the platform itself writes; always included in strict mode. */
    static final List<String> PLATFORM_MDC_KEYS = List.of("traceId", "spanId");

    private String mdcInclude = "";
    private boolean redactionEnabled = true;
    private String redactionPatterns = "";

    /**
     * Comma-separated MDC keys to write besides {@code traceId} and {@code spanId}. Blank (the
     * default) writes every MDC key.
     *
     * @param mdcInclude the keys; may be {@code null} or blank
     */
    public void setMdcInclude(String mdcInclude) {
        this.mdcInclude = mdcInclude == null ? "" : mdcInclude;
    }

    /**
     * Switches redaction on (the default) or off.
     *
     * @param redactionEnabled whether values are masked
     */
    public void setRedactionEnabled(boolean redactionEnabled) {
        this.redactionEnabled = redactionEnabled;
    }

    /**
     * Additional regular expressions to mask, one per line.
     *
     * @param redactionPatterns the patterns; may be {@code null} or blank
     */
    public void setRedactionPatterns(String redactionPatterns) {
        this.redactionPatterns = redactionPatterns == null ? "" : redactionPatterns;
    }

    @Override
    public void start() {
        List<String> keys = Arrays.stream(mdcInclude.split(",")).map(String::strip).filter(k -> !k.isEmpty()).toList();
        if (!keys.isEmpty()) {
            PLATFORM_MDC_KEYS.forEach(this::addIncludeMdcKeyName);
            keys.stream().filter(k -> !PLATFORM_MDC_KEYS.contains(k)).forEach(this::addIncludeMdcKeyName);
        }
        if (redactionEnabled) {
            MaskingJsonGeneratorDecorator masking = new MaskingJsonGeneratorDecorator();
            masking.addValueMasker(new PlatformValueMasker(PlatformValueMasker.compile(redactionPatterns)));
            masking.start();
            setJsonGeneratorDecorator(masking);
        }
        super.start();
    }
}
