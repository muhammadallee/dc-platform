package ae.gov.dubaicustoms.platform.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Assembles the read-only {@link McpSyncServer} over the stdio transport, exposing the
 * {@link PlatformMcpTools} tool set. Read-only and no auth (v1, internal network).
 */
final class PlatformMcpServer {

    private PlatformMcpServer() {
    }

    /** Builds (and starts) the server on stdin/stdout. */
    static McpSyncServer overStdio(PlatformIndex index, ObjectMapper mapper) {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(mapper));
        return McpServer.sync(transport)
                .serverInfo("platform-mcp-server", index.version())
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(new PlatformMcpTools(index, mapper).specifications())
                .build();
    }
}
