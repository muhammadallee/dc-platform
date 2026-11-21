package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
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
 * <p>Every value is turned into a JSON-safe copy by one walk ({@link #sanitize}), used both for JSON members and for
 * the arguments of a message (Codex phase 3 round 1): maps keep their keys and lists their order, so a credential name
 * at any depth masks its whole value; a record becomes a map of its components and any other application object a map
 * of its bean properties, so their credential names count too; JDK value types ({@code UUID}, {@code Path},
 * {@code java.time}, enums…), character data ({@code CharSequence}, {@code char[]}, {@code Character}) and Boot's
 * {@code WritableJson} become masked text; numbers stay numbers unless they are card numbers or match an extra
 * pattern, and a non-finite one becomes text. Each {@code toString()} or getter is called once, under a guard: one that
 * throws becomes {@value #UNRENDERABLE} and costs only that value. A container holding itself (by identity, so a
 * value shared between branches is still shown at each place), anything deeper than {@value #MAX_DEPTH} levels, and
 * anything beyond {@value #MAX_NODES} values in one event is masked whole.
 *
 * <p>Stateless apart from the immutable extra patterns; thread-safe.
 */
public final class PlatformValueMasker {

    /** Replacement for masked text; fixed length so the original length is not disclosed. */
    public static final String MASK = "****";

    /** Nesting depth below which a value is masked whole, the traversal depth of the v3 encoder. */
    static final int MAX_DEPTH = 64;

    /** Values one walk visits at most; the rest are masked whole, so a branching structure stays bounded. */
    static final int MAX_NODES = 10_000;

    /** Shown for a value whose {@code toString()} or getter throws. */
    static final String UNRENDERABLE = "[unrenderable]";

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
    // Basic dXNlcjpw…") is not consumed and the scan resumes right after the scheme. A token may be the mask itself,
    // so masking text that is already masked changes nothing.
    private static final Pattern BEARER = Pattern.compile("(?i)(?<![\\w-])(Bearer)(\\s+)(?=([A-Za-z0-9._~+/=*-]+))");
    private static final Pattern BASIC = Pattern.compile("(?i)(?<![\\w-])(Basic)(\\s+)(?=([A-Za-z0-9+/*]+={0,2}))");
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
    private final boolean ecsObjects;

    /**
     * Creates the masker for the platform's own format.
     *
     * @param extraPatterns additional regular expressions whose matches are masked; never {@code null}
     */
    public PlatformValueMasker(List<Pattern> extraPatterns) {
        this(extraPatterns, false);
    }

    /**
     * Creates the masker.
     *
     * @param extraPatterns additional regular expressions whose matches are masked; never {@code null}
     * @param ecsObjects whether an ECS format is configured, whose own nested objects Boot writes as deferred JSON
     */
    public PlatformValueMasker(List<Pattern> extraPatterns, boolean ecsObjects) {
        this.extraPatterns = List.copyOf(extraPatterns);
        this.ecsObjects = ecsObjects;
    }

    /**
     * The value Boot writes for one member: a JSON-safe, masked copy (see the class description).
     *
     * @param path the member's path; may be {@code null}
     * @param value the value; may be {@code null}
     * @return the value to write
     */
    public Object mask(MemberPath path, Object value) {
        if (value == null || path != null && "".equals(path.name()) || writerInternal(path, value)) {
            // An unnamed member, the logging event, or a nested object of Boot's own format: Boot's writer of further
            // members, which come through here one by one.
            return value;
        }
        if (depth(path) > MAX_DEPTH || underCredentialKey(path)) {
            return MASK;
        }
        if (value instanceof CharSequence text) {
            // A snapshot: Boot writes this string, never the original object a second time.
            String snapshot = guarded(text::toString);
            return correlationId(topLevel(path) ? path.name() : null, snapshot) ? snapshot : maskPatterns(snapshot);
        }
        return sanitize(path == null ? null : path.name(), value);
    }

    /**
     * A JSON-safe, masked copy of one value filed under a name: maps, lists, strings, numbers, booleans and
     * {@code null} only. Whatever the value throws while it is read becomes {@value #UNRENDERABLE}; nothing propagates.
     *
     * @param name the name the value is filed under; may be {@code null}
     * @param value the value; may be {@code null}
     * @return the copy, or the value itself when it is already safe
     */
    public Object sanitize(String name, Object value) {
        try {
            return new Walk().copy(name, value, 0);
        } catch (VirtualMachineError fatal) {
            if (fatal instanceof StackOverflowError) {
                return UNRENDERABLE;
            }
            throw fatal;
        } catch (Throwable failure) {
            return UNRENDERABLE;
        }
    }

    /**
     * A top-level key-value pair as the platform's format writes it: like {@link #sanitize}, except that a
     * {@code traceId} or {@code spanId} holding a W3C hex ID is kept, as for the same keys in the MDC.
     *
     * @param key the pair's key; may be {@code null}
     * @param value the pair's value; may be {@code null}
     * @return the value to write
     */
    public Object sanitizePair(String key, Object value) {
        if (value instanceof CharSequence text) {
            String snapshot = guarded(text::toString);
            return correlationId(key, snapshot) ? snapshot : sanitize(key, snapshot);
        }
        return sanitize(key, value);
    }

    /**
     * The text a sanitized copy reads as in a message: maps as {@code {key=value, …}}, lists as {@code [a, b]}.
     *
     * @param sanitized a value returned by {@link #sanitize}; may be {@code null}
     * @return its text
     */
    public static String text(Object sanitized) {
        return String.valueOf(sanitized);
    }

    // A top-level traceId/spanId member holding a W3C hex ID (which may contain a Luhn-valid digit run).
    static boolean correlationId(String topLevelName, String text) {
        return ("traceId".equals(topLevelName) || "spanId".equals(topLevelName))
                && TRACE_OR_SPAN_ID.matcher(text).matches();
    }

    // The nested objects Boot's ECS format writes as deferred JSON; Boot's Logstash and GELF formats write none.
    private static final Set<String> FORMAT_OBJECTS = Set.of("log", "process", "service", "ecs", "error");

    // The logging event and Boot's writers, which Boot builds a line from; and, only where an ECS format is configured,
    // deferred JSON under one of ECS's object names. Anything else (a Logback logger a customizer adds, raw JSON a
    // service logs) is sanitized like any value.
    private boolean writerInternal(MemberPath path, Object value) {
        String type = value.getClass().getName();
        if (value instanceof SafeMap || value instanceof SafeList) {
            // A copy this masker made: its members come through here one by one, already masked.
            return true;
        }
        if (implementsAny(value.getClass(),
                Set.of("ch.qos.logback.classic.spi.ILoggingEvent", "org.apache.logging.log4j.core.LogEvent"))) {
            return true;
        }
        if (!(value instanceof WritableJson)) {
            return false;
        }
        if (!ecsObjects) {
            return false;
        }
        MemberPath root = path;
        while (root != null && root.parent() != null && root.parent().parent() != null) {
            root = root.parent();
        }
        return root != null && FORMAT_OBJECTS.contains(root.name());
    }

    private static boolean implementsAny(Class<?> type, Set<String> names) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (names.contains(c.getName())) {
                return true;
            }
            for (Class<?> each : c.getInterfaces()) {
                if (implementsAny(each, names)) {
                    return true;
                }
            }
        }
        return false;
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
            result = outsideMasks(result, pattern);
        }
        return result;
    }

    // An extra pattern applies between the masks already in the text, never to a mask itself, so masking text that is
    // already masked changes nothing (Codex phase 3 round 2: a pattern such as \S would otherwise grow every mask).
    private static String outsideMasks(String text, Pattern pattern) {
        if (!text.contains(MASK)) {
            return pattern.matcher(text).replaceAll(MASK);
        }
        StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        for (int at = text.indexOf(MASK); at >= 0; at = text.indexOf(MASK, from)) {
            out.append(pattern.matcher(text.substring(from, at)).replaceAll(MASK)).append(MASK);
            from = at + MASK.length();
        }
        return out.append(pattern.matcher(text.substring(from)).replaceAll(MASK)).toString();
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

    // One walk over one value: the ancestors on the current branch, by identity, and the values left in the budget.
    private final class Walk {

        private final Set<Object> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
        private int budget = MAX_NODES;

        Object copy(String name, Object value, int depth) {
            // Every value visited is charged first, a masked one too.
            if (--budget < 0 || depth >= MAX_DEPTH || isCredentialKey(name)) {
                return MASK;
            }
            if (value == null) {
                return null;
            }
            if (value instanceof CharSequence text) {
                return maskPatterns(guarded(text::toString));
            }
            if (value instanceof char[] chars) {
                return maskPatterns(new String(chars));
            }
            if (value instanceof Character || value instanceof Path || value instanceof Enum<?>
                    || value instanceof Class<?> || value instanceof Throwable) {
                return maskPatterns(render(value));
            }
            if (value instanceof WritableJson) {
                // Opaque JSON a service logged: its names may be escaped and its text need not be one value.
                return MASK;
            }
            if (value instanceof Boolean flag) {
                return scalar(flag, flag.toString());
            }
            if (value instanceof Number number) {
                return number(number);
            }
            boolean container = value instanceof Map<?, ?> || value instanceof Iterable<?> || value.getClass().isArray()
                    || value instanceof Record;
            if (!container) {
                return object(name, value, depth);
            }
            if (!ancestors.add(value)) {
                // A container holding itself: shown masked rather than walked again.
                return MASK;
            }
            try {
                // The budget is checked before the next element is fetched: an endless iterator stops too.
                if (value instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new SafeMap();
                    Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
                    while (true) {
                        if (budget <= 0) {
                            copy.put(MASK, MASK);
                            break;
                        }
                        if (!entries.hasNext()) {
                            break;
                        }
                        Map.Entry<?, ?> entry = entries.next();
                        String key = guarded(() -> String.valueOf(entry.getKey()));
                        copy.put(key, copy(key, entry.getValue(), depth + 1));
                    }
                    return copy;
                }
                List<Object> copy = new SafeList();
                if (value instanceof Iterable<?> iterable) {
                    Iterator<?> elements = iterable.iterator();
                    while (true) {
                        if (budget <= 0) {
                            copy.add(MASK);
                            break;
                        }
                        if (!elements.hasNext()) {
                            break;
                        }
                        copy.add(copy(name, elements.next(), depth + 1));
                    }
                    return copy;
                }
                if (value.getClass().isArray()) {
                    for (int i = 0, length = Array.getLength(value); i < length; i++) {
                        if (budget <= 0) {
                            copy.add(MASK);
                            break;
                        }
                        copy.add(copy(name, Array.get(value, i), depth + 1));
                    }
                    return copy;
                }
                Map<String, Object> components = new SafeMap();
                for (RecordComponent component : ((Record) value).getClass().getRecordComponents()) {
                    if (budget <= 0) {
                        components.put(MASK, MASK);
                        break;
                    }
                    budget--;
                    Object componentValue = read(component.getAccessor(), value);
                    components.put(component.getName(), componentValue == UNREADABLE ? UNRENDERABLE
                            : copy(component.getName(), componentValue, depth + 1));
                }
                return components;
            } finally {
                ancestors.remove(value);
            }
        }

        // A JDK value type is text; any other object is its bean properties, or its text when it has none.
        private Object object(String name, Object value, int depth) {
            String type = value.getClass().getName();
            if (type.startsWith("java.") || type.startsWith("javax.") || type.startsWith("jdk.")
                    || type.startsWith("ch.qos.logback.") || type.startsWith("org.apache.logging.")
                    || type.startsWith("org.slf4j.")) {
                return maskPatterns(render(value));
            }
            List<Method> getters = getters(value.getClass());
            if (getters.isEmpty()) {
                return maskPatterns(render(value));
            }
            if (!ancestors.add(value)) {
                return MASK;
            }
            try {
                Map<String, Object> copy = new SafeMap();
                for (Method getter : getters) {
                    if (budget <= 0) {
                        copy.put(MASK, MASK);
                        break;
                    }
                    String property = property(getter);
                    budget--;
                    Object read = read(getter, value);
                    copy.put(property, read == UNREADABLE ? UNRENDERABLE : copy(property, read, depth + 1));
                }
                return copy;
            } finally {
                ancestors.remove(value);
            }
        }

        // Only the JDK's own number classes are trusted as they are: a subclass could write any text. Anything else
        // becomes a BigDecimal read from one snapshot of its text, or masked text.
        private Object number(Number number) {
            Class<?> type = number.getClass();
            if ((type == Double.class || type == Float.class) && !Double.isFinite(number.doubleValue())) {
                return maskPatterns(String.valueOf(number.doubleValue()));
            }
            Number value = number;
            if (type != Long.class && type != Integer.class && type != Short.class && type != Byte.class
                    && type != BigInteger.class && type != BigDecimal.class && type != Double.class
                    && type != Float.class) {
                String text = render(number);
                try {
                    value = new BigDecimal(text.strip());
                } catch (NumberFormatException notDecimal) {
                    return maskPatterns(text);
                }
            }
            if (cardNumber(value)) {
                return MASK;
            }
            // An integral number may be an identifier (an Emirates ID): every pattern. A fraction cannot: only the
            // service's own patterns.
            return integral(value) ? scalar(value, value.toString()) : extraOnly(value, value.toString());
        }

        private Object extraOnly(Object value, String text) {
            String masked = text;
            for (Pattern pattern : extraPatterns) {
                masked = outsideMasks(masked, pattern);
            }
            return masked.equals(text) ? value : masked;
        }

        // A number or boolean stays one unless a pattern matches its text (an Emirates ID logged as a number, say).
        private Object scalar(Object value, String text) {
            String masked = maskPatterns(text);
            return masked.equals(text) ? value : masked;
        }
    }

    // The masker's own copies: Boot walks them and calls the masker for each member, which then leaves them alone.
    private static final class SafeMap extends LinkedHashMap<String, Object> {
    }

    private static final class SafeList extends ArrayList<Object> {
    }

    private static boolean integral(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal.signum() == 0 || decimal.stripTrailingZeros().scale() <= 0;
        }
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            return value == Math.rint(value);
        }
        return true;
    }

    // Sentinel for a getter or accessor that threw.
    private static final Object UNREADABLE = new Object();

    // Public no-argument getters of an application class (getX, isX), by property name; getClass excluded.
    private static List<Method> getters(Class<?> type) {
        List<Method> getters = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())
                    || method.getDeclaringClass() == Object.class || method.getReturnType() == void.class) {
                continue;
            }
            String name = method.getName();
            boolean getter = name.startsWith("get") && name.length() > 3
                    || name.startsWith("is") && name.length() > 2
                            && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class);
            if (getter) {
                getters.add(method);
            }
        }
        getters.sort(Comparator.comparing(PlatformValueMasker::property));
        return getters;
    }

    private static String property(Method getter) {
        String name = getter.getName();
        String bare = name.startsWith("is") ? name.substring(2) : name.substring(3);
        return bare.length() > 1 && Character.isUpperCase(bare.charAt(1)) ? bare
                : Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
    }

    private static Object read(Method accessor, Object target) {
        try {
            accessor.setAccessible(true);
            return accessor.invoke(target);
        } catch (VirtualMachineError fatal) {
            if (fatal instanceof StackOverflowError) {
                return UNREADABLE;
            }
            throw fatal;
        } catch (Throwable failure) {
            return UNREADABLE;
        }
    }

    // toString(), called once; one that throws costs only this value, never the line.
    private static String render(Object value) {
        return guarded(() -> String.valueOf(value));
    }

    private static String guarded(java.util.function.Supplier<String> text) {
        try {
            return text.get();
        } catch (VirtualMachineError fatal) {
            if (fatal instanceof StackOverflowError) {
                return UNRENDERABLE;
            }
            throw fatal;
        } catch (Throwable failure) {
            return UNRENDERABLE;
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
