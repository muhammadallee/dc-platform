package ae.gov.dubaicustoms.platform.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * In-memory model of {@code platform-index.json} — the build-time fact sheet the server answers from.
 * Deserialized once at startup; immutable thereafter. Unknown fields are ignored so a newer index
 * (extra keys) still loads on an older server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlatformIndex(
        String version,
        List<Capability> capabilities,
        List<Property> properties,
        List<ErrorCode> errorCodes,
        List<ApiType> apiTypes,
        List<Snippet> snippets) {

    /** A platform capability and the starters that switch it on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capability(String name, String doc, String oneLiner, List<String> starters) {
    }

    /** A {@code dc.platform.*} configuration key with its type, default, and deprecation. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Property(
            String name, String type,
            @JsonProperty("default") String defaultValue,
            String description, String deprecation, String capability) {
    }

    /** A stable {@code DC-<CAP>-<NNNN>} error code and where it is declared. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorCode(String code, String capability, String declaredBy) {
    }

    /** A public API/SPI type and its apiguardian stability. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiType(String type, String kind, String status, String since) {
    }

    /** A usage snippet lifted from a capability's documentation page. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Snippet(String capability, String id, String code) {
    }
}
