package ae.gov.dubaicustoms.platform.build.plugin.internal;

// One managed-dependency version delta between the current and target platform BOM. `from` is null
// when the target BOM newly manages a coordinate the current one did not.
public record VersionChange(String coordinate, String from, String to) {

    public boolean isNew() {
        return from == null;
    }
}
