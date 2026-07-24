package ae.gov.dubaicustoms.platform.build.plugin.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

// Pure diff of two "groupId:artifactId" -> version maps (current vs target platform BOM). Kept free
// of Maven/Aether types so upgrade-check's core is unit-tested without resolving anything.
public final class BomVersionDiffer {

    private BomVersionDiffer() {
    }

    /**
     * Coordinates whose managed version changed, or that the target BOM manages and the current one
     * did not. Unchanged coordinates and coordinates dropped by the target are omitted. Sorted by
     * coordinate for a stable report.
     */
    public static List<VersionChange> diff(Map<String, String> current, Map<String, String> target) {
        List<VersionChange> changes = new ArrayList<>();
        for (String coordinate : new TreeSet<>(target.keySet())) {
            String to = target.get(coordinate);
            String from = current.get(coordinate);
            if (!Objects.equals(from, to)) {
                changes.add(new VersionChange(coordinate, from, to));
            }
        }
        return changes;
    }
}
