import ae.gov.dubaicustoms.platform.test.security.TestJwtIssuer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK-only helpers for tooling/scripts/golden-path.sh, compiled by the script against the INSTALLED
 * platform-test-api jar (so the gate exercises the published {@link TestJwtIssuer}, not a copy):
 *
 * <pre>
 *   java -cp classes:platform-test-api.jar GateSupport idp DIR   # loopback JWKS + tokens until DIR/stop exists
 *   java -cp ... GateSupport freeport                            # prints a free loopback TCP port
 *   java -cp ... GateSupport logcheck LOG SERVICE [CORRELATION_ID] [FORBIDDEN...]
 * </pre>
 *
 * logcheck parses every stdout line that starts with '{' as a JSON object and requires the structured
 * log contract (@timestamp, level, logger, message, service=SERVICE). Non-JSON lines (the Boot banner, JVM
 * warnings) are counted and reported, not parsed. With CORRELATION_ID, at least one event must carry it;
 * any FORBIDDEN string (e.g. a bearer token) appearing anywhere in the log fails the check.
 */
public final class GateSupport {

    public static void main(String[] args) throws Exception {
        switch (args.length == 0 ? "" : args[0]) {
            case "idp" -> idp(Path.of(args[1]));
            case "freeport" -> System.out.println(freePort());
            case "logcheck" -> System.exit(logcheck(args));
            default -> {
                System.err.println("usage: GateSupport idp DIR | freeport | logcheck LOG SERVICE [CORRELATION_ID] [FORBIDDEN...]");
                System.exit(2);
            }
        }
    }

    private static void idp(Path dir) throws Exception {
        Files.createDirectories(dir);
        try (TestJwtIssuer issuer = TestJwtIssuer.start()) {
            Files.writeString(dir.resolve("jwks-uri"), issuer.jwkSetUri());
            Files.writeString(dir.resolve("token-valid"), issuer.token("gate-user", Map.of(), java.time.Duration.ofHours(2)));
            Files.writeString(dir.resolve("token-untrusted"), issuer.untrustedToken("gate-user"));
            Files.writeString(dir.resolve("token-expired"), issuer.expiredToken("gate-user"));
            Files.writeString(dir.resolve("ready"), "ready");
            Path stop = dir.resolve("stop");
            long deadline = System.nanoTime() + java.time.Duration.ofHours(2).toNanos();
            while (!Files.exists(stop) && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static int logcheck(String[] args) throws IOException {
        Path log = Path.of(args[1]);
        String service = args[2];
        String correlationId = args.length > 3 && !args[3].isEmpty() ? args[3] : null;
        List<String> forbidden = args.length > 4 ? List.of(args).subList(4, args.length) : List.of();
        String content = Files.readString(log, StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        int events = 0;
        int other = 0;
        boolean correlated = correlationId == null;
        for (String line : content.lines().toList()) {
            if (!line.startsWith("{")) {
                other++;
                continue;
            }
            Map<String, Object> event;
            try {
                event = new Json(line).object();
            } catch (RuntimeException e) {
                problems.add("unparseable JSON log line: " + abbreviate(line) + " (" + e.getMessage() + ")");
                continue;
            }
            events++;
            for (String field : List.of("@timestamp", "level", "logger", "message", "service")) {
                if (!(event.get(field) instanceof String)) {
                    problems.add("event without string field '" + field + "': " + abbreviate(line));
                }
            }
            if (!service.equals(event.get("service"))) {
                problems.add("event with service=" + event.get("service") + " (expected " + service + ")");
            }
            if (correlationId != null && correlationId.equals(event.get("correlationId"))) {
                correlated = true;
            }
        }
        if (events == 0) {
            problems.add("no JSON log events found");
        }
        if (!correlated) {
            problems.add("no event carries correlationId " + correlationId);
        }
        for (String secret : forbidden) {
            if (!secret.isEmpty() && content.contains(secret)) {
                problems.add("a forbidden value (length " + secret.length() + ") appears in the log");
            }
        }
        System.out.println("logcheck: " + events + " JSON events, " + other + " non-JSON lines, "
                + problems.size() + " problem(s)");
        problems.stream().limit(20).forEach(problem -> System.out.println("  - " + problem));
        return problems.isEmpty() ? 0 : 1;
    }

    private static String abbreviate(String line) {
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }

    /** Minimal strict JSON reader (objects, arrays, strings, numbers, literals) — enough for log events. */
    private static final class Json {

        private final String text;
        private int pos;

        Json(String text) {
            this.text = text;
        }

        Map<String, Object> object() {
            Object value = value();
            skipWhitespace();
            if (pos != text.length() || !(value instanceof Map)) {
                throw new IllegalArgumentException("not a single JSON object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) value;
            return object;
        }

        private Object value() {
            skipWhitespace();
            char c = peek();
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, pos)) {
                    pos += literal.length();
                    return "null".equals(literal) ? null : Boolean.valueOf(literal);
                }
            }
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("unexpected character at " + pos);
            }
            return Double.valueOf(text.substring(start, pos));
        }

        private Map<String, Object> readObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String name = readString();
                skipWhitespace();
                expect(':');
                object.put(name, value());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect('}');
                    return object;
                }
            }
        }

        private List<Object> readArray() {
            List<Object> array = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return array;
            }
            while (true) {
                array.add(value());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect(']');
                    return array;
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw new IllegalArgumentException("raw control character in string at " + (pos - 1));
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape at " + (pos - 1));
                }
            }
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + pos);
            }
            pos++;
        }

        private char peek() {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return text.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
