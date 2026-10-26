package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
import com.fasterxml.jackson.core.JsonStreamContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.logstash.logback.mask.ValueMasker;

/**
 * Masks credentials and the default PII set (Emirates ID, card numbers, e-mail addresses, phone
 * numbers) in every value the platform JSON encoder writes (message, MDC values, structured arguments,
 * exception text), then applies the application's {@link LogSanitizer} beans to MDC and
 * structured-argument fields (D102).
 *
 * <p>Order per value: whole-value mask when the field, or any field enclosing it, is a credential key
 * (any value type); {@code LogSanitizer} beans (non-platform fields only); pattern masks. Pattern masks
 * run last, so a sanitizer can neither skip them nor reintroduce a credential the patterns would catch.
 * {@code traceId}/{@code spanId} fields holding W3C hex IDs are never pattern-masked. Every pattern is
 * anchored to token starts and bounded, so masking stays linear in the input length. Masked text
 * becomes {@value #MASK}.
 *
 * <p>Stateless apart from the immutable extra patterns; thread-safe.
 */
public final class PlatformValueMasker implements ValueMasker {

    /** Replacement for masked text; fixed length so the original length is not disclosed. */
    public static final String MASK = "****";

    // Field names written by the encoder itself; LogSanitizers apply to everything else (MDC, args).
    private static final Set<String> PLATFORM_FIELDS = Set.of("@timestamp", "level", "logger", "message",
            "stack_trace", "service", "traceId", "spanId", "tags");

    // Encoder-written fields: never pattern-masked.
    private static final Set<String> ENCODER_FIELDS = Set.of("@timestamp", "level", "logger");
    // traceId/spanId are exempt only when they hold what the platform writes there: W3C hex IDs (a hex
    // ID may contain a Luhn-valid digit run). Anything else under those names is masked as usual.
    private static final Pattern TRACE_OR_SPAN_ID = Pattern.compile("[0-9a-f]{32}|[0-9a-f]{16}");

    // Normalized (lower case, no '-' or '_') field names whose whole value is a credential.
    private static final Set<String> CREDENTIAL_KEYS = Set.of("password", "passwd", "pwd", "secret",
            "clientsecret", "token", "accesstoken", "refreshtoken", "idtoken", "authorization",
            "proxyauthorization", "cookie", "setcookie", "apikey", "xapikey");

    // A JWT header is base64url JSON: '{' then '"' or whitespace encodes to "ey" / "ew" (RFC 7515 allows
    // whitespace); the smallest header ({"alg":"x"}) is 15 characters. {} as payload encodes to e30;
    // alg=none has no signature.
    private static final Pattern JWT =
            Pattern.compile("(?<![A-Za-z0-9_-])e[wy][A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)(?<![\\w-])(Bearer)(\\s+)([A-Za-z0-9._~+/=-]+)");
    private static final Pattern BASIC = Pattern.compile("(?i)(?<![\\w-])(Basic)(\\s+)([A-Za-z0-9+/]+={0,2})");
    private static final String QUOTED_OR_BARE =
            "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|(?:(?:Bearer|Basic)\\s+)?[^\\s\"'&,;}]+)";
    // key=value, key: value, "key":"value", key='value'; quoted values masked whole, quotes kept.
    private static final Pattern KEY_VALUE = Pattern.compile("(?i)(?<![\\w-])(password|passwd|pwd|secret"
            + "|client_secret|access_token|refresh_token|id_token|token|api[-_]?key|authorization)"
            + "(\"?\\s*[:=]\\s*)" + QUOTED_OR_BARE);
    // A cookie header carries several pairs: everything up to the end of the line (or the quote) goes.
    private static final Pattern COOKIE = Pattern.compile("(?i)(?<![\\w-])(set-cookie|cookie)(\"?\\s*[:=]\\s*)"
            + "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|[^\\r\\n\"]+)");
    // Emirates ID: 784-YYYY-NNNNNNN-C, dashes optional.
    private static final Pattern EMIRATES_ID = Pattern.compile("(?<![\\w-])784-?\\d{4}-?\\d{7}-?\\d(?![\\w-])");
    // Card numbers, issuer digit 2–6 (keeps 13-digit epoch millis out), Luhn-valid: contiguous 13–19
    // digits, or the printed layouts 4-4-4-4 (+1..3) and 4-6-5 with one consistent space/dash separator.
    private static final Pattern PAN_CONTIGUOUS = Pattern.compile("(?<!\\w)[2-6]\\d{12,18}(?!\\w)");
    private static final Pattern PAN_GROUPED = Pattern.compile(
            "(?<!\\w)(?<base>[2-6]\\d{3}(?<s>[ -])\\d{4}\\k<s>\\d{4}\\k<s>\\d{4})(?<ext>\\k<s>\\d{1,3})?(?!\\w)"
            + "|(?<!\\w)(?<amex>3[47]\\d{2}(?<a>[ -])\\d{6}\\k<a>\\d{5})(?!\\w)");
    private static final Pattern DIGITS = Pattern.compile("[2-6]\\d{12,18}");
    // E-mail: local part starts a token and is bounded (linear scanning); dotted domain keeps Java's
    // Object@1a2b3c identity strings out.
    private static final Pattern EMAIL = Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}"
            + "@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){0,8}\\.[A-Za-z]{2,24}(?![A-Za-z0-9-])");
    // Phone numbers: international with a + or 00 prefix (8–15 digits, single space/dash separators),
    // and UAE mobile numbers in local form (05X XXX XXXX). Local landlines without a prefix are not
    // matched: too close to ordinary numeric IDs.
    private static final Pattern PHONE_INTERNATIONAL =
            Pattern.compile("(?<![\\w+-])(?:\\+|00)[1-9](?:[ -]?\\d){7,14}(?![\\w-])");
    private static final Pattern PHONE_UAE_MOBILE =
            Pattern.compile("(?<![\\w-])05[02-8][ -]?\\d{3}[ -]?\\d{4}(?![\\w-])");

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
        if (value == null) {
            return null;
        }
        if (underCredentialKey(context)) {
            return MASK;
        }
        if (value instanceof Number number) {
            // kv("card", 4111111111111111L): a card number written as a JSON number.
            String digits = integralDigits(number);
            return digits != null && DIGITS.matcher(digits).matches() && luhn(digits) ? MASK : null;
        }
        if (!(value instanceof CharSequence text)) {
            return null;
        }
        String field = context.getCurrentName();
        String original = text.toString();
        if (field != null && (ENCODER_FIELDS.contains(field)
                || ("traceId".equals(field) || "spanId".equals(field)) && TRACE_OR_SPAN_ID.matcher(original).matches())) {
            return null;
        }
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
        // RFC 6750 tokens may be letters only: whatever follows "Bearer" goes, prose included.
        result = BEARER.matcher(result).replaceAll("$1$2" + MASK);
        result = replace(result, BASIC, m -> basicCredential(m.group(3)) ? m.group(1) + m.group(2) + MASK : m.group());
        result = replace(result, KEY_VALUE, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
        result = replace(result, COOKIE, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
        result = EMIRATES_ID.matcher(result).replaceAll(MASK);
        result = replace(result, PAN_GROUPED, PlatformValueMasker::groupedCard);
        result = replace(result, PAN_CONTIGUOUS, m -> luhn(m.group()) ? MASK : m.group());
        if (result.indexOf('@') >= 0) {
            result = EMAIL.matcher(result).replaceAll(MASK);
        }
        result = PHONE_INTERNATIONAL.matcher(result).replaceAll(MASK);
        result = PHONE_UAE_MOBILE.matcher(result).replaceAll(MASK);
        for (Pattern pattern : extraPatterns) {
            result = pattern.matcher(result).replaceAll(MASK);
        }
        return result;
    }

    /**
     * Applies the registered {@link LogSanitizer} beans to one keyed value, in order; a throwing
     * sanitizer is skipped.
     *
     * @param key the field name; never {@code null}
     * @param value the value; may be {@code null}
     * @return the sanitized value
     */
    static String sanitize(String key, String value) {
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

    private static boolean underCredentialKey(JsonStreamContext context) {
        for (JsonStreamContext c = context; c != null; c = c.getParent()) {
            String name = c.getCurrentName();
            if (name != null && CREDENTIAL_KEYS.contains(normalize(name))) {
                return true;
            }
        }
        return false;
    }

    private static String integralDigits(Number number) {
        if (number instanceof Long || number instanceof Integer || number instanceof BigInteger) {
            return number.toString();
        }
        if (number instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0) {
            return decimal.toBigInteger().toString();
        }
        return null;
    }

    private static String maskValue(String value) {
        char first = value.charAt(0);
        return first == '"' || first == '\'' ? first + MASK + first : MASK;
    }

    // RFC 7617: base64 of user-id ":" password — masked whatever its length when it decodes to one.
    private static boolean basicCredential(String token) {
        try {
            return new String(Base64.getDecoder().decode(token), StandardCharsets.ISO_8859_1).indexOf(':') >= 0
                    || token.length() >= 16;
        } catch (IllegalArgumentException notBase64) {
            return token.length() >= 16;
        }
    }

    private static String groupedCard(Matcher m) {
        String amex = m.group("amex");
        if (amex != null) {
            return luhn(amex) ? MASK : m.group();
        }
        String base = m.group("base");
        String ext = m.group("ext");
        if (ext != null && luhn(base + ext)) {
            return MASK;
        }
        if (luhn(base)) {
            // A following short number belongs to the text, not the card ("card 4111 … 1111 12").
            return ext == null ? MASK : MASK + ext;
        }
        return m.group();
    }

    private static String replace(String text, Pattern pattern, Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        do {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.apply(matcher)));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
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
