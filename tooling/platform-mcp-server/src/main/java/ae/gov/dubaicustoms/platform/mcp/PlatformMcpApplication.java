package ae.gov.dubaicustoms.platform.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the platform MCP server: {@code java -jar platform-mcp-server.jar --stdio}.
 *
 * <p>Loads the bundled {@code platform-index.json} (or {@code --index <path>} to point at a freshly
 * generated one), serves the read-only tool set over stdio, and runs until the client closes the
 * process (MCP clients own the subprocess lifecycle). streamable-HTTP is a planned follow-up; only
 * stdio is wired in this build.
 */
public final class PlatformMcpApplication {

    private static final Logger log = LoggerFactory.getLogger(PlatformMcpApplication.class);

    private PlatformMcpApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        boolean stdio = false;
        Path indexPath = null;
        for (int i = 0; i < args.length; i++) {
            if ("--stdio".equals(args[i])) {
                stdio = true;
            } else if ("--index".equals(args[i]) && i + 1 < args.length) {
                indexPath = Path.of(args[++i]);
            }
        }

        if (!stdio) {
            log.warn("Usage: java -jar platform-mcp-server.jar --stdio [--index <path>]. "
                    + "Only the stdio transport is wired in this build; streamable-HTTP is planned.");
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        PlatformIndex index = indexPath != null ? IndexLoader.fromPath(indexPath) : IndexLoader.fromClasspath();
        McpSyncServer server = PlatformMcpServer.overStdio(index, mapper);

        CountDownLatch until = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            until.countDown();
        }));
        until.await();
    }
}
