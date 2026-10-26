package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.PlatformValueMasker;
import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.SanitizedMessageView;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;

/**
 * The platform's JSON log encoder: Logstash's {@link LogstashEncoder} plus redaction of credentials
 * and the default PII set (D102) and an opt-in strict MDC allow-list (D101). The shipped
 * {@code logback-platform.xml} uses it; reference it from your own Logback file to keep masking when
 * you replace {@code logging.config}:
 * <pre>{@code
 * <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
 *   <encoder class="ae.gov.dubaicustoms.platform.logging.autoconfigure.PlatformJsonEncoder">
 *     <redactionPatterns>CASE-\d{6}</redactionPatterns>
 *   </encoder>
 * </appender>
 * }</pre>
 *
 * <p>Every {@code LogstashEncoder} option (field names, custom fields, MDC switches) is available.
 * Not thread-safe during configuration (Logback configures it on one thread); encoding is thread-safe
 * once started. Setters accept {@code null} as "not set".
 *
 * @since 0.1.0
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

    @Override
    public byte[] encode(ILoggingEvent event) {
        // LogSanitizer beans also clean keyed arguments inside the formatted message (event-local view).
        return super.encode(redactionEnabled ? SanitizedMessageView.of(event) : event);
    }

    @Override
    public void encode(ILoggingEvent event, OutputStream outputStream) throws IOException {
        super.encode(redactionEnabled ? SanitizedMessageView.of(event) : event, outputStream);
    }
}
