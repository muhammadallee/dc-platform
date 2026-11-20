package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter.MemberPath;
import org.springframework.boot.json.WritableJson;

/**
 * Masks credentials and the default PII set (Emirates ID, card numbers, e-mail addresses, phone numbers) in every value
 * Boot's structured logging writes: the message, MDC values, key-value pairs at any depth, and the stack trace (D102,
 * D141). {@link PlatformLogMasking} calls it as Boot's JSON value processor.
 *
 * <p>Order per value: whole-value mask when the member, or any member enclosing it, has a credential name (any value
 * type); then pattern masks on text. Only top-level {@code traceId}/{@code spanId} members holding W3C hex IDs are
 * exempt from pattern masks; every other member, whatever its name, is masked. Every pattern is anchored to token
 * starts and bounded, and no pattern recurses per character, so masking stays linear in the input length and
 * stack-safe. Masked text becomes {@value #MASK}.
 *
 * <p>Values Boot would write with {@code toString()} (records, other objects) are turned into something it can walk
 * or into masked text here: a record becomes a map of its components, so credential names inside it still count; any
 * other object becomes its masked {@code toString()}, called once, and a {@code toString()} that throws costs only
 * that value. Below {@value #MAX_DEPTH} levels of nesting (a container holding itself, say) a value is masked whole,
 * the traversal depth of the v3 encoder.
 *
 * <p>Stateless apart from the immutable extra patterns; thread-safe.
 */
public final class PlatformValueMasker {

    /** Replacement for masked text; fixed length so the original length is not disclosed. */
    public static final String MASK = "****";

    /** Nesting depth below which a value is masked whole, ending the descent into a self-referencing container. */
    static final int MAX_DEPTH = 64;

    // The platform writes W3C hex IDs to the top-level traceId/spanId fields (a hex ID may contain a
    // Luhn-valid digit run). Anything else, or the same names nested in a structured value, is masked.
    private static final Pattern TRACE_OR_SPAN_ID = Pattern.compile("[0-9a-f]{32}|[0-9a-f]{16}");

    // Normalized (lower case, no '-' or '_') field names whose whole value is a credential: these, and
    // compound names ending in one of the suffixes (sessionToken, db_password, x-auth-token, smtpSecret).
    private static final Set<String> CREDENTIAL_KEYS = Set.of("password", "passwd", "pwd", "secret",
            "clientsecret", "token", "accesstoken", "refreshtoken", "idtoken", "authorization",
            "proxyauthorization", "cookie", "setcookie", "apikey", "xapikey");
    private static final List<String> CREDENTIAL_SUFFIXES = List.of("password", "passwd", "secret", "token",
            "apikey", "authorization", "cookie");

    // Compact JWS/JWE candidates: runs of base64url segments joined by dots. A window of three segments
    // is a token when the first decodes to a JSON object (RFC 7515 allows whitespace around it); the
    // smallest header ({"alg":"x"}) is 15 characters, {} as payload encodes to e30, alg=none has no
    // signature.
    private static final Pattern DOTTED_RUN = Pattern.compile("[A-Za-z0-9_.-]{15,}");
    // Scheme then token. The token is matched in a lookahead, so a word that is not a credential ("basic
    // Basic dXNlcjpw…") is not consumed and the scan resumes right after the scheme.
    private static final Pattern BEARER = Pattern.compile("(?i)(?<![\\w-])(Bearer)(\\s+)(?=([A-Za-z0-9._~+/=-]+))");
    private static final Pattern BASIC = Pattern.compile("(?i)(?<![\\w-])(Basic)(\\s+)(?=([A-Za-z0-9+/]+={0,2}))");
    // Quoted values with escapes, unrolled and possessive: a loop over an alternation would recurse once
    // per character in java.util.regex and overflow the stack on long values. An unterminated quote runs
    // to the end of the text.
    private static final String QUOTED = "\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+(?:\"|\\z)"
            + "|'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+(?:'|\\z)";
    // key=value, key: value, "key":"value", key='value'; quoted values masked whole, quotes kept. The key
    // may be compound (session_token=, db-password:, smtpSecret=, sessionCookie=); the prefix is lazy and
    // starts a token, so scanning stays linear. A bare cookie key here masks its first value; the cookie
    // patterns below then take a header's remaining pairs.
    private static final Pattern KEY_VALUE = Pattern.compile("(?i)(?<![\\w-])([A-Za-z0-9_-]*?(?:password|passwd|pwd"
            + "|secret|token|api[-_]?key|authorization|cookie))"
            + "(\"?\\s*[:=]\\s*)(" + QUOTED + "|(?:(?:Bearer|Basic)\\s+)?[^\\s\"'&,;}]+)");
    // A quoted key ("cookie":"…", "cookie"="…", "cookie":12345678): the value goes — a quoted one whole,
    // a bare one up to the next , } or line end. An unquoted key (Cookie: … header text, several pairs,
    // quoted pair values included): everything up to the end of the line goes.
    private static final Pattern COOKIE_FIELD = Pattern.compile("(?i)(?<![\\w-])(set-cookie|cookie)(\"\\s*[:=]\\s*)("
            + QUOTED + "|[^\\s\"',}][^,}\\r\\n]*+)");
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)(?<![\\w-])(set-cookie|cookie)(\\s*[:=]\\s*)"
            + "([^\\r\\n]+)");
    // Emirates ID: 784-YYYY-NNNNNNN-C, dashes optional.
    private static final Pattern EMIRATES_ID = Pattern.compile("(?<![\\w-])784-?\\d{4}-?\\d{7}-?\\d(?![\\w-])");
    // Card numbers, issuer digit 2–6 (keeps 13-digit epoch millis out), Luhn-valid: contiguous 13–19
    // digits, or the printed layouts 4-4-4-4 (+1..3) and 4-6-5 with one consistent space/dash separator.
    private static final Pattern PAN_CONTIGUOUS = Pattern.compile("(?<!\\w)[2-6]\\d{12,18}(?!\\w)");
    private static final Pattern PAN_GROUPED = Pattern.compile(
            "(?<!\\w)(?<base>[2-6]\\d{3}(?<s>[ -])\\d{4}\\k<s>\\d{4}\\k<s>\\d{4})(?<ext>\\k<s>\\d{1,3})?(?!\\w)"
            + "|(?<!\\w)(?<amex>3[47]\\d{2}(?<a>[ -])\\d{6}\\k<a>\\d{5})(?!\\w)");
    private static final Pattern DIGITS = Pattern.compile("[2-6]\\d{12,18}");
    // A card number logged as a double and formatted into text by Java: 4.111111111111111E15 (HI-0050).
    private static final Pattern PAN_SCIENTIFIC = Pattern.compile("(?<![\\w.])[2-6]\\.\\d{1,18}E1[2-8](?![\\w.])");
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

    /**
     * The value Boot writes for one member: the value itself when nothing is masked, otherwise its masked form.
     *
     * @param path the member's path; may be {@code null}
     * @param value the value; may be {@code null}
     * @return the value to write
     */
    public Object mask(MemberPath path, Object value) {
        if (value == null) {
            return null;
        }
        if (depth(path) > MAX_DEPTH || underCredentialKey(path)) {
            return MASK;
        }
        if (value instanceof CharSequence text) {
            String original = text.toString();
            if (topLevel(path) && ("traceId".equals(path.name()) || "spanId".equals(path.name()))
                    && TRACE_OR_SPAN_ID.matcher(original).matches()) {
                return value;
            }
            String masked = maskPatterns(original);
            return masked.equals(original) ? value : masked;
        }
        if (value instanceof Number number) {
            // addKeyValue("card", 4111111111111111L): a card number written as a JSON number.
            return cardNumber(number) ? MASK : value;
        }
        if (value instanceof Boolean || value instanceof Map || value instanceof Iterable || value instanceof Object[]
                || value instanceof WritableJson) {
            // Boot walks containers and calls this masker for every element, with its path.
            return value;
        }
        if (value.getClass().isArray()) {
            // A primitive array (double[], byte[]): its elements, each checked like any number.
            return elements(value);
        }
        if (value instanceof Record record) {
            Map<String, Object> components = components(record);
            if (components != null) {
                return components;
            }
        }
        return maskPatterns(text(value));
    }

    /** Applies the pattern masks (credentials, PII, extra patterns) to free text. */
    String maskPatterns(String text) {
        String result = maskJwts(text);
        // RFC 6750 tokens may be letters only: whatever follows "Bearer" goes, prose included.
        result = schemeMask(result, BEARER, token -> true);
        result = schemeMask(result, BASIC, PlatformValueMasker::basicCredential);
        result = replace(result, KEY_VALUE, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
        result = replace(result, COOKIE_FIELD, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
        result = replace(result, COOKIE_HEADER, m -> m.group(1) + m.group(2) + MASK);
        result = EMIRATES_ID.matcher(result).replaceAll(MASK);
        result = replace(result, PAN_GROUPED, PlatformValueMasker::groupedCard);
        result = replace(result, PAN_CONTIGUOUS, m -> luhn(m.group()) ? MASK : m.group());
        if (result.indexOf('E') >= 0) {
            result = replace(result, PAN_SCIENTIFIC, m -> cardNumber(Double.valueOf(m.group())) ? MASK : m.group());
        }
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
     * Whether a member name denotes a credential, compared case-insensitively and ignoring {@code -} and {@code _}
     * ({@code api-key}, {@code API_KEY}, {@code apiKey}).
     *
     * @param name the member name; may be {@code null}
     * @return {@code true} when the member's whole value must be masked
     */
    static boolean isCredentialKey(String name) {
        if (name == null) {
            return false;
        }
        String normalized = normalize(name);
        return CREDENTIAL_KEYS.contains(normalized) || CREDENTIAL_SUFFIXES.stream().anyMatch(normalized::endsWith);
    }

    private static boolean underCredentialKey(MemberPath path) {
        for (MemberPath p = path; p != null; p = p.parent()) {
            if (isCredentialKey(p.name())) {
                return true;
            }
        }
        return false;
    }

    // A member of the root object: its parent is the root path, which has no parent.
    static boolean topLevel(MemberPath path) {
        return path != null && path.parent() != null && path.parent().parent() == null;
    }

    private static int depth(MemberPath path) {
        int depth = 0;
        for (MemberPath p = path; p != null; p = p.parent()) {
            depth++;
        }
        return depth;
    }

    private static List<Object> elements(Object array) {
        int length = java.lang.reflect.Array.getLength(array);
        List<Object> elements = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            elements.add(java.lang.reflect.Array.get(array, i));
        }
        return elements;
    }

    // A record as a map of its components, so their names count; null when a component cannot be read.
    private static Map<String, Object> components(Record record) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (RecordComponent component : components) {
                java.lang.reflect.Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                values.put(component.getName(), accessor.invoke(record));
            }
            return values;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    // toString(), called once; one that throws costs only this value, never the line.
    private static String text(Object value) {
        try {
            return String.valueOf(value);
        } catch (VirtualMachineError fatal) {
            if (fatal instanceof StackOverflowError) {
                return "[toString() failed: " + fatal.getClass().getName() + "]";
            }
            throw fatal;
        } catch (Throwable failure) {
            return "[toString() failed: " + failure.getClass().getName() + "]";
        }
    }

    /** Whether a number is a Luhn-valid card number (integral, 13–19 digits, issuer digit 2–6). */
    static boolean cardNumber(Number number) {
        String digits = integralDigits(number);
        return digits != null && DIGITS.matcher(digits).matches() && luhn(digits);
    }

    private static String integralDigits(Number number) {
        if (number instanceof Long || number instanceof Integer) {
            return number.toString();
        }
        if (number instanceof BigInteger integer) {
            // 19 digits fit in 64 bits; anything longer is no card number and is never expanded.
            return integer.bitLength() <= 64 ? integer.toString() : null;
        }
        if (number instanceof BigDecimal decimal) {
            BigDecimal stripped = decimal.stripTrailingZeros();
            // Integer digits from precision and scale (long: scale may be near Integer.MIN_VALUE), so
            // 1E+100000000 is rejected without expanding it.
            long integerDigits = (long) stripped.precision() - stripped.scale();
            return stripped.scale() <= 0 && integerDigits >= 13 && integerDigits <= 19
                    ? stripped.toBigInteger().toString() : null;
        }
        if (number instanceof Double || number instanceof Float) {
            // 4111111111111111.0 logged as a double: integral finite values are checked like any number.
            double value = number.doubleValue();
            return Double.isFinite(value) && value == Math.rint(value) ? integralDigits(BigDecimal.valueOf(value)) : null;
        }
        return null;
    }

    private static String maskJwts(String text) {
        if (text.indexOf('.') < 0) {
            return text;
        }
        Matcher run = DOTTED_RUN.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        while (run.find()) {
            String masked = maskJwtRun(run.group());
            if (masked != null) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                }
                out.append(text, copied, run.start()).append(masked);
                copied = run.end();
            }
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    // Masks every header.payload.signature window in one dotted run; null when there is none.
    private static String maskJwtRun(String run) {
        String[] segments = run.split("\\.", -1);
        if (segments.length < 3) {
            return null;
        }
        StringBuilder out = new StringBuilder(run.length());
        boolean found = false;
        int i = 0;
        while (i < segments.length) {
            if (i + 2 < segments.length && jsonObjectHeader(segments[i]) && segments[i + 1].length() >= 2) {
                out.append(MASK);
                found = true;
                i += 3;
            } else {
                out.append(segments[i]);
                i++;
            }
            if (i < segments.length) {
                out.append('.');
            }
        }
        return found ? out.toString() : null;
    }

    private static boolean jsonObjectHeader(String segment) {
        if (segment.length() < 10 || segment.length() % 4 == 1 || segment.indexOf('=') >= 0) {
            return false;
        }
        try {
            String header = new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.ISO_8859_1);
            int start = 0;
            int end = header.length();
            while (start < end && jsonWhitespace(header.charAt(start))) {
                start++;
            }
            while (end > start && jsonWhitespace(header.charAt(end - 1))) {
                end--;
            }
            return end - start >= 2 && header.charAt(start) == '{' && header.charAt(end - 1) == '}';
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }

    private static boolean jsonWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
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

    // Masks the token after each scheme match the predicate accepts; a rejected match consumes only the
    // scheme and its whitespace.
    private static String schemeMask(String text, Pattern scheme, Predicate<String> isCredential) {
        Matcher matcher = scheme.matcher(text);
        Matcher next = scheme.matcher(text).useTransparentBounds(true).useAnchoringBounds(false);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (from <= text.length() && matcher.find(from)) {
            String token = matcher.group(3);
            // "bearer Bearer <token>": the scheme word is skipped only when a scheme + token follows it;
            // "Bearer bearer" alone is a credential like any other.
            boolean schemeWord = token.equalsIgnoreCase(matcher.group(1))
                    && next.region(matcher.end(), text.length()).lookingAt();
            if (!schemeWord && isCredential.test(token)) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                }
                out.append(text, copied, matcher.end()).append(MASK);
                copied = matcher.end() + token.length();
                from = copied;
            } else {
                from = matcher.end();
            }
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
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
