package ae.gov.dubaicustoms.platform.build.plugin.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The upgrade-check result: managed-version deltas plus the deprecated keys the project still sets.
// Pure (built from maps/lists, renders to strings) so the whole report is unit-tested offline; the
// mojo only feeds it resolved inputs and writes/logs the rendered forms.
public final class UpgradeReport {

    private final String targetVersion;
    private final String releaseNotesUrl;
    private final List<VersionChange> versionChanges;
    private final List<DeprecatedProperty> deprecationHits;

    private UpgradeReport(String targetVersion, String releaseNotesUrl,
            List<VersionChange> versionChanges, List<DeprecatedProperty> deprecationHits) {
        this.targetVersion = targetVersion;
        this.releaseNotesUrl = releaseNotesUrl;
        this.versionChanges = versionChanges;
        this.deprecationHits = deprecationHits;
    }

    /**
     * Builds the report: diffs the current vs target managed versions, and keeps only the deprecated
     * properties the project actually sets ({@code usedKeys} ∩ target deprecations, de-duplicated by
     * name, source order preserved).
     */
    public static UpgradeReport of(Map<String, String> currentManaged, Map<String, String> targetManaged,
            Set<String> usedKeys, List<DeprecatedProperty> targetDeprecations,
            String targetVersion, String releaseNotesUrl) {
        List<VersionChange> changes = BomVersionDiffer.diff(currentManaged, targetManaged);
        List<DeprecatedProperty> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DeprecatedProperty deprecated : targetDeprecations) {
            if (usedKeys.contains(deprecated.name()) && seen.add(deprecated.name())) {
                hits.add(deprecated);
            }
        }
        return new UpgradeReport(targetVersion, releaseNotesUrl, changes, hits);
    }

    public List<VersionChange> versionChanges() {
        return versionChanges;
    }

    public List<DeprecatedProperty> deprecationHits() {
        return deprecationHits;
    }

    /** True when the project sets at least one property deprecated in the target version. */
    public boolean hasDeprecationHits() {
        return !deprecationHits.isEmpty();
    }

    /** One-line console summary for the goal's log output. */
    public String toConsoleSummary() {
        return "upgrade-check: target " + targetVersion + " — " + versionChanges.size()
                + " managed version change(s), " + deprecationHits.size()
                + " deprecated propert(y/ies) still set. See target/platform-upgrade-report.md.";
    }

    /** Full markdown report written to {@code target/platform-upgrade-report.md}. */
    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Platform upgrade report — target ").append(targetVersion).append("\n\n");
        md.append("Release notes: ").append(releaseNotesUrl).append("\n\n");

        md.append("## Managed version changes (").append(versionChanges.size()).append(")\n\n");
        if (versionChanges.isEmpty()) {
            md.append("_No managed versions change._\n\n");
        } else {
            md.append("| Coordinate | From | To |\n|---|---|---|\n");
            for (VersionChange change : versionChanges) {
                md.append("| ").append(change.coordinate()).append(" | ")
                        .append(change.isNew() ? "_(new)_" : change.from()).append(" | ")
                        .append(change.to()).append(" |\n");
            }
            md.append('\n');
        }

        md.append("## Deprecated properties still set (").append(deprecationHits.size()).append(")\n\n");
        if (deprecationHits.isEmpty()) {
            md.append("_None — no configured key is deprecated in ").append(targetVersion).append("._\n");
        } else {
            for (DeprecatedProperty hit : deprecationHits) {
                md.append("- `").append(hit.name()).append('`');
                if (hit.replacement() != null) {
                    md.append(" → use `").append(hit.replacement()).append('`');
                }
                if (hit.reason() != null) {
                    md.append(" (").append(hit.reason()).append(')');
                }
                md.append('\n');
            }
        }
        return md.toString();
    }
}
