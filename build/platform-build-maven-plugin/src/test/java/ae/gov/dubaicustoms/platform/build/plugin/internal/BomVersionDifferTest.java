package ae.gov.dubaicustoms.platform.build.plugin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BomVersionDifferTest {

    @Test
    void reportsChangedVersionsSortedByCoordinate() {
        var current = Map.of("g:b", "1.0.0", "g:a", "1.0.0", "g:c", "1.0.0");
        var target = Map.of("g:b", "2.0.0", "g:a", "1.0.0", "g:c", "1.5.0");

        var changes = BomVersionDiffer.diff(current, target);

        assertThat(changes).extracting(VersionChange::coordinate).containsExactly("g:b", "g:c");
        assertThat(changes.get(0).from()).isEqualTo("1.0.0");
        assertThat(changes.get(0).to()).isEqualTo("2.0.0");
    }

    @Test
    void flagsNewlyManagedCoordinatesWithNullFrom() {
        var changes = BomVersionDiffer.diff(Map.of(), Map.of("g:new", "1.0.0"));

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).isNew()).isTrue();
        assertThat(changes.get(0).from()).isNull();
        assertThat(changes.get(0).to()).isEqualTo("1.0.0");
    }

    @Test
    void ignoresUnchangedAndDroppedCoordinates() {
        var current = Map.of("g:same", "1.0.0", "g:dropped", "1.0.0");
        var target = Map.of("g:same", "1.0.0");

        assertThat(BomVersionDiffer.diff(current, target)).isEmpty();
    }
}
