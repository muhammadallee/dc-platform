package ae.gov.dubaicustoms.platform.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders {@code docs/reference/error-codes.md} from the {@code target/error-codes.csv} that
 * phase-04's {@code ErrorCodeRegistryTest} writes (columns {@code code,declaredBy}). The registry
 * test is the single source of truth and the uniqueness gate; this generator only presents it.
 */
final class ErrorCodesReferenceGenerator {

    private ErrorCodesReferenceGenerator() {
    }

    static Path csvSource() {
        return PlatformDocs.repoRoot()
                .resolve("errors/platform-errors-autoconfigure/target/error-codes.csv");
    }

    /** Regenerates {@code docs/reference/error-codes.md}; returns the written path. */
    static Path generate() {
        Path csv = csvSource();
        if (!Files.exists(csv)) {
            throw new IllegalStateException("error-codes.csv not found at " + csv
                    + " — run a full `mvn verify` first (ErrorCodeRegistryTest emits it).");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(csv);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        StringBuilder md = new StringBuilder();
        md.append("# Error codes\n\n");
        md.append("> Generated from `platform-errors-autoconfigure/target/error-codes.csv` (emitted by ");
        md.append("`ErrorCodeRegistryTest`) at build time. Do not edit by hand.\n\n");
        md.append("Every platform error carries a stable `DC-<CAP>-<NNNN>` code. Codes are unique ");
        md.append("platform-wide (enforced by the phase-04 registry gate) and surface in RFC-9457 ");
        md.append("problem responses. See [errors](../modules/errors.md).\n\n");
        md.append("| Code | Capability | Declared by |\n");
        md.append("|------|------------|-------------|\n");

        lines.stream()
                .skip(1) // header
                .filter(l -> !l.isBlank())
                .forEach(line -> {
                    int comma = line.indexOf(',');
                    String code = comma < 0 ? line : line.substring(0, comma);
                    String declaredBy = comma < 0 ? "" : line.substring(comma + 1);
                    md.append("| `").append(code).append("` | ")
                            .append(capabilityOf(code)).append(" | `")
                            .append(declaredBy).append("` |\n");
                });

        Path out = PlatformDocs.docsRoot().resolve("reference/error-codes.md");
        PlatformDocs.writeString(out, md.toString());
        return out;
    }

    private static String capabilityOf(String code) {
        String[] parts = code.split("-");
        return parts.length >= 2 ? parts[1].toLowerCase() : "";
    }
}
