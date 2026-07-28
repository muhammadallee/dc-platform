package ae.gov.dubaicustoms.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Contract tests for the six MCP tools against a fixture index. */
class PlatformMcpToolsTest {

    private static PlatformMcpTools tools;

    @BeforeAll
    static void load() {
        PlatformIndex index = IndexLoader.fromClasspath();
        tools = new PlatformMcpTools(index, new ObjectMapper());
    }

    @Test
    void exposesTheSixContractTools() {
        assertThat(tools.specifications())
                .extracting(spec -> spec.tool().name())
                .containsExactlyInAnyOrder("find_capability", "property_lookup", "error_code_lookup",
                        "usage_example", "list_starters", "platform_version");
    }

    @Test
    void findCapabilityRanksMessagingForEvents() {
        String out = text(tools.findCapability("events"));
        assertThat(out).contains("messaging").contains("platform-starter-messaging-inmemory");
        assertThat(out).doesNotContain("\"name\":\"cache\"");
    }

    @Test
    void propertyLookupByPrefixReturnsMatchingKeys() {
        String out = text(tools.propertyLookup("dc.platform.messaging"));
        assertThat(out).contains("dc.platform.messaging.enabled").contains("dc.platform.messaging.publish-timeout");
        assertThat(out).doesNotContain("dc.platform.cache");
    }

    @Test
    void errorCodeLookupIsCaseInsensitive() {
        assertThat(text(tools.errorCodeLookup("dc-core-0500"))).contains("DC-CORE-0500").contains("PlatformException");
        assertThat(text(tools.errorCodeLookup("DC-NOPE-9999"))).isEqualTo("[]");
    }

    @Test
    void usageExampleReturnsASnippetOrAMiss() {
        assertThat(text(tools.usageExample("messaging", "publish"))).contains("publisher.publish");
        assertThat(text(tools.usageExample("nosuch", null))).contains("\"found\":false");
    }

    @Test
    void listStartersCoversEveryStarter() {
        String out = text(tools.listStarters());
        assertThat(out).contains("platform-starter-messaging-kafka").contains("platform-starter-cache-caffeine");
    }

    @Test
    void platformVersionReportsTheIndexVersion() {
        assertThat(text(tools.version())).contains("9.9.9-TEST");
    }

    private static String text(CallToolResult result) {
        assertThat(result.isError()).isFalse();
        List<?> content = result.content();
        return ((TextContent) content.get(0)).text();
    }
}
