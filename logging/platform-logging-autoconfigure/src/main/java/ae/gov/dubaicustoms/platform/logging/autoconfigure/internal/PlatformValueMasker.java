package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
import com.fasterxml.jackson.core.JsonStreamContext;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.logstash.logback.mask.ValueMasker;

/**
 * Masks credentials and the default PII set in every string value the platform JSON encoder writes
 * (message, MDC values, structured arguments, exception text), then applies the application's
 * {@link LogSanitizer} beans to MDC and structured-argument fields (D102).
 *
 * <p>Order per value: whole-value mask when the field name is a credential key; {@code LogSanitizer}
 * beans (non-platform fields only); pattern masks. Pattern masks run last, so a sanitizer can neither
 * skip them nor reintroduce a credential the patterns would catch. Masked text becomes {@value #MASK}.
 *
 * <p>Stateless apart from the immutable extra patterns; thread-safe.
 */
public final class PlatformValueMasker implements ValueMasker {

    /** Replacement for masked text; fixed length so the original length is not disclosed. */
    public static final String MASK = "****";

    // Field names written by the encoder itself; LogSanitizers apply to everything else (MDC, args).
    private static final Set<String> PLATFORM_FIELDS = Set.of("@timestamp", "level", "logger", "message",
            "stack_trace", "service", "traceId", "spanId", "tags");

    // Normalized (lower case, no '-' or '_') field names whose whole value is a credential.
    private static final Set<String> CREDENTIAL_KEYS = Set.of("password", "passwd", "pwd", "secret",
            "clientsecret", "token", "accesstoken", "refreshtoken", "idtoken", "authorization",
            "proxyauthorization", "cookie", "setcookie", "apikey", "xapikey");

    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern AUTH_SCHEME =
            Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    // key=value, key: value, "key":"value" — the value up to a delimiter; an auth scheme stays with it.
    private static final Pattern KEY_VALUE = Pattern.compile("(?i)\\b(password|passwd|pwd|secret|client_secret"
            + "|access_token|refresh_token|id_token|token|api[-_]?key|authorization|cookie|set-cookie)\\b"
            + "(\"?\\s*[:=]\\s*\"?)((?:Bearer\\s+|Basic\\s+)?[^\\s\"'&,;}]+)");
    // Emirates ID: 784-YYYY-NNNNNNN-C, dashes optional.
    private static final Pattern EMIRATES_ID = Pattern.compile("(?<![\\d-])784-?\\d{4}-?\\d{7}-?\\d(?![\\d-])");
    // Card numbers: 13–19 digits (single space/dash separators allowed), issuer digit 2–6, Luhn-valid.
    // The issuer digit keeps 13-digit epoch millis (leading 1) out.
    private static final Pattern PAN = Pattern.compile("(?<![\\d-])[2-6](?:[ -]?\\d){12,18}(?![\\d-])");

    private final List<Pattern> extraPatterns;

    /**
     * Creates the masker.
     *
     * @param extraPatterns additional regular expressions whose matches are masked; never {@code null}
     */
    public PlatformValueMasker(List<Pattern> extraPatterns) {
        this.extraPatterns = List.copyOf(extraPatterns);
    }

    @Override
    public Object mask(JsonStreamContext context, Object value) {
        if (value instanceof Long || value instanceof BigInteger) {
            // kv("card", 4111111111111111L): a card number written as a JSON number.
            return PAN.matcher(value.toString()).matches() && luhn(value.toString()) ? MASK : null;
        }
        if (!(value instanceof CharSequence text)) {
            return null;
        }
        String field = context.getCurrentName();
        if (field != null && CREDENTIAL_KEYS.contains(normalize(field))) {
            return MASK;
        }
        String original = text.toString();
        String result = original;
        if (field != null && !PLATFORM_FIELDS.contains(field)) {
            result = sanitize(field, result);
        }
        result = maskPatterns(result);
        return result.equals(original) ? null : result;
    }

    /** Applies the pattern masks (credentials, PII, extra patterns) to free text. */
    String maskPatterns(String text) {
        String result = JWT.matcher(text).replaceAll(MASK);
        result = AUTH_SCHEME.matcher(result).replaceAll("$1 " + MASK);
        result = KEY_VALUE.matcher(result).replaceAll("$1$2" + MASK);
        result = EMIRATES_ID.matcher(result).replaceAll(MASK);
        result = maskCardNumbers(result);
        for (Pattern pattern : extraPatterns) {
            result = pattern.matcher(result).replaceAll(MASK);
        }
        return result;
    }

    private static String sanitize(String key, String value) {
        String result = value;
        for (LogSanitizer sanitizer : LogSanitizerRegistry.current()) {
            try {
                String sanitized = sanitizer.sanitize(key, result);
                if (sanitized != null) {
                    result = sanitized;
                }
            } catch (RuntimeException ignored) {
                // A throwing sanitizer must not suppress the line (LogSanitizer contract); the pattern
                // masks still run on the value.
            }
        }
        return result;
    }

    private static String maskCardNumbers(String text) {
        Matcher matcher = PAN.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        do {
            if (luhn(matcher.group())) {
                out.append(text, last, matcher.start()).append(MASK);
                last = matcher.end();
            }
        } while (matcher.find());
        return out.append(text, last, text.length()).toString();
    }

    private static boolean luhn(String candidate) {
        int sum = 0;
        boolean doubled = false;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            int digit = c - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    private static String normalize(String field) {
        return field.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /**
     * Compiles newline-separated extra patterns; blank lines are ignored.
     *
     * @param lines the patterns, one per line; may be {@code null}
     * @return the compiled patterns
     */
    public static List<Pattern> compile(String lines) {
        List<Pattern> patterns = new ArrayList<>();
        if (lines != null) {
            for (String line : lines.split("\n")) {
                if (!line.isBlank()) {
                    patterns.add(Pattern.compile(line.strip()));
                }
            }
        }
        return patterns;
    }
}
