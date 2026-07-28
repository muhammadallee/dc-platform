package ae.gov.dubaicustoms.platform.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The read-only MCP tool set: the six contract tools ({@code find_capability}, {@code property_lookup},
 * {@code error_code_lookup}, {@code usage_example}, {@code list_starters}, {@code platform_version})
 * answered from a {@link PlatformIndex}. Each tool returns a JSON text result; handlers are pure
 * functions of the index and their arguments, so they are unit-testable without a live transport.
 */
public final class PlatformMcpTools {

    private final PlatformIndex index;
    private final ObjectMapper mapper;
    private final McpJsonMapper jsonMapper;

    public PlatformMcpTools(PlatformIndex index, ObjectMapper mapper) {
        this.index = index;
        this.mapper = mapper;
        this.jsonMapper = new JacksonMcpJsonMapper(mapper);
    }

    /** The six tool specifications, ready to register on an {@code McpSyncServer}. */
    public List<SyncToolSpecification> specifications() {
        return List.of(
                tool("find_capability",
                        "Find platform capabilities matching a free-text query. Returns ranked capabilities "
                                + "with their starter coordinates and doc page.",
                        schema("query", "what you want to do, e.g. 'publish events' or 'cache'"),
                        (exchange, args) -> findCapability(str(args, "query"))),
                tool("property_lookup",
                        "Look up dc.platform.* configuration keys by exact key or prefix. Returns type, "
                                + "default, description, and deprecation.",
                        schema("keyOrPrefix", "a full key or a prefix, e.g. 'dc.platform.messaging'"),
                        (exchange, args) -> propertyLookup(str(args, "keyOrPrefix"))),
                tool("error_code_lookup",
                        "Look up a platform error code (DC-<CAP>-<NNNN>). Returns its capability and where "
                                + "it is declared.",
                        schema("code", "the error code, e.g. 'DC-CORE-0500'"),
                        (exchange, args) -> errorCodeLookup(str(args, "code"))),
                tool("usage_example",
                        "Get a usage snippet for a capability (optionally for a task). Returns a code example "
                                + "from the capability's documentation.",
                        schema("capability", "capability name, e.g. 'messaging'", "task",
                                "optional task hint, e.g. 'publish'"),
                        (exchange, args) -> usageExample(str(args, "capability"), str(args, "task"))),
                tool("list_starters",
                        "List every platform starter with the capability it enables.",
                        emptySchema(),
                        (exchange, args) -> listStarters()),
                tool("platform_version",
                        "The platform release-train version this index was built from.",
                        emptySchema(),
                        (exchange, args) -> version()));
    }

    // --- handlers (pure; package-visible for tests) ------------------------------------------

    CallToolResult findCapability(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Map<String, Object>> matches = index.capabilities().stream()
                .filter(c -> matches(c, q))
                .map(c -> ordered(
                        "name", c.name(),
                        "oneLiner", c.oneLiner(),
                        "starters", c.starters(),
                        "doc", c.doc()))
                .toList();
        return json(matches);
    }

    private static boolean matches(PlatformIndex.Capability c, String q) {
        if (q.isEmpty()) {
            return true;
        }
        return contains(c.name(), q) || contains(c.oneLiner(), q) || contains(c.doc(), q)
                || c.starters().stream().anyMatch(s -> contains(s, q));
    }

    CallToolResult propertyLookup(String keyOrPrefix) {
        String key = keyOrPrefix == null ? "" : keyOrPrefix.trim();
        List<PlatformIndex.Property> hits = index.properties().stream()
                .filter(p -> p.name().equals(key) || p.name().startsWith(key))
                .toList();
        return json(hits);
    }

    CallToolResult errorCodeLookup(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return json(index.errorCodes().stream().filter(e -> e.code().equalsIgnoreCase(c)).toList());
    }

    CallToolResult usageExample(String capability, String task) {
        String cap = capability == null ? "" : capability.trim().toLowerCase(Locale.ROOT);
        List<PlatformIndex.Snippet> hits = index.snippets().stream()
                .filter(s -> s.capability().equalsIgnoreCase(cap) || contains(s.id(), cap))
                .toList();
        if (hits.isEmpty()) {
            return json(Map.of("capability", capability, "found", false,
                    "message", "no snippet indexed for this capability"));
        }
        return json(hits.get(0));
    }

    CallToolResult listStarters() {
        List<Map<String, Object>> starters = index.capabilities().stream()
                .flatMap(c -> c.starters().stream().map(s -> ordered("starter", s, "capability", c.name())))
                .toList();
        return json(starters);
    }

    CallToolResult version() {
        return json(ordered("version", index.version()));
    }

    // --- helpers -----------------------------------------------------------------------------

    private SyncToolSpecification tool(String name, String description, String inputSchema,
            java.util.function.BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange,
                    Map<String, Object>, CallToolResult> handler) {
        Tool tool = Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(jsonMapper, inputSchema)
                .build();
        return new SyncToolSpecification(tool, handler);
    }

    private CallToolResult json(Object payload) {
        try {
            return new CallToolResult(mapper.writeValueAsString(payload), false);
        } catch (JsonProcessingException e) {
            return new CallToolResult("{\"error\":\"serialization failed: " + e.getMessage() + "\"}", true);
        }
    }

    private static boolean contains(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : v.toString();
    }

    /** Preserves field order in the emitted JSON. */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String schema(String prop, String desc) {
        return "{\"type\":\"object\",\"properties\":{\"" + prop + "\":{\"type\":\"string\",\"description\":\""
                + desc + "\"}},\"required\":[\"" + prop + "\"]}";
    }

    private static String schema(String req, String reqDesc, String opt, String optDesc) {
        return "{\"type\":\"object\",\"properties\":{\"" + req + "\":{\"type\":\"string\",\"description\":\""
                + reqDesc + "\"},\"" + opt + "\":{\"type\":\"string\",\"description\":\"" + optDesc
                + "\"}},\"required\":[\"" + req + "\"]}";
    }

    private static String emptySchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }
}
