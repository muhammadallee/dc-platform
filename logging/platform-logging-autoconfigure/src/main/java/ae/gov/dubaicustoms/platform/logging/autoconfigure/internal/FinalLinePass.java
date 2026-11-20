package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

/**
 * The last masking step of the platform's format: one pass over the finished JSON line, after every members customizer
 * and every nested value processor has run (Codex phase 3 round 1, finding 10). Every member name and string is
 * decoded: a member with a credential name is masked whole, whatever its value; every other string goes through the
 * pattern masks, a top-level {@code traceId}/{@code spanId} W3C hex ID excepted. Anything that is not a well-formed
 * JSON object is masked whole. The masks are idempotent, so text already masked comes through unchanged.
 *
 * <p>Linear in the line length; stateless; thread-safe.
 */
final class FinalLinePass {

    private final PlatformValueMasker masker;
    private final String line;
    private final StringBuilder out;
    private int at;

    private FinalLinePass(PlatformValueMasker masker, String line) {
        this.masker = masker;
        this.line = line;
        this.out = new StringBuilder(line.length() + 16);
    }

    /**
     * Masks one finished line.
     *
     * @param masker the masker; never {@code null}
     * @param line the JSON object Boot wrote, with any trailing line break; never {@code null}
     * @return the line to write
     */
    static String mask(PlatformValueMasker masker, String line) {
        FinalLinePass pass = new FinalLinePass(masker, line);
        try {
            pass.whitespace();
            if (pass.peek() != '{') {
                throw new IllegalArgumentException("not an object");
            }
            pass.value(0, null, false);
            pass.whitespace();
            if (pass.at != line.length()) {
                throw new IllegalArgumentException("trailing content");
            }
            return pass.out.toString();
        } catch (RuntimeException malformed) {
            String end = line.endsWith("\n") ? "\n" : "";
            return "{\"message\":\"" + PlatformValueMasker.MASK + "\"}" + end;
        }
    }

    // One JSON value at a depth (0: the line's object), under a member name, masked whole when masked is true.
    private void value(int depth, String name, boolean masked) {
        char c = peek();
        if (c == '{' || c == '[') {
            if (masked) {
                skipContainer();
                out.append('"').append(PlatformValueMasker.MASK).append('"');
                return;
            }
            container(depth);
            return;
        }
        if (c == '"') {
            String text = string();
            String written = masked ? PlatformValueMasker.MASK
                    : PlatformValueMasker.correlationId(depth == 1 ? name : null, text) ? text : masker.maskPatterns(text);
            quote(written);
            return;
        }
        int start = at;
        while (at < line.length() && "{}[],: \t\r\n\"".indexOf(line.charAt(at)) < 0) {
            at++;
        }
        if (start == at) {
            throw new IllegalArgumentException("value expected");
        }
        if (masked) {
            out.append('"').append(PlatformValueMasker.MASK).append('"');
        } else {
            out.append(line, start, at);
        }
    }

    private void container(int depth) {
        char open = line.charAt(at++);
        char close = open == '{' ? '}' : ']';
        out.append(open);
        whitespace();
        if (peek() == close) {
            out.append(line.charAt(at++));
            return;
        }
        while (true) {
            if (open == '{') {
                String name = string();
                quote(name);
                whitespace();
                expect(':');
                whitespace();
                value(depth + 1, name, PlatformValueMasker.isCredentialKey(name));
            } else {
                value(depth + 1, null, false);
            }
            whitespace();
            char next = line.charAt(at++);
            out.append(next);
            if (next == close) {
                return;
            }
            if (next != ',') {
                throw new IllegalArgumentException("separator expected");
            }
            whitespace();
        }
    }

    private void skipContainer() {
        int depth = 0;
        do {
            char c = line.charAt(at);
            if (c == '"') {
                string();
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
            at++;
        } while (depth > 0);
    }

    // A JSON string at the cursor, decoded.
    private String string() {
        if (line.charAt(at) != '"') {
            throw new IllegalArgumentException("string expected");
        }
        at++;
        StringBuilder text = new StringBuilder();
        while (true) {
            char c = line.charAt(at++);
            if (c == '"') {
                return text.toString();
            }
            if (c != '\\') {
                text.append(c);
                continue;
            }
            char escape = line.charAt(at++);
            switch (escape) {
                case '"', '\\', '/' -> text.append(escape);
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case 'u' -> {
                    text.append((char) Integer.parseInt(line.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw new IllegalArgumentException("bad escape");
            }
        }
    }

    private void quote(String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private void whitespace() {
        while (at < line.length() && " \t\r\n".indexOf(line.charAt(at)) >= 0) {
            out.append(line.charAt(at++));
        }
    }

    private void expect(char c) {
        if (line.charAt(at) != c) {
            throw new IllegalArgumentException(c + " expected");
        }
        out.append(line.charAt(at++));
    }

    private char peek() {
        return line.charAt(at);
    }
}
