package ae.gov.dubaicustoms.platform.build.plugin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UpgradeReportTest {

    private static final String NOTES = "https://example/upgrade/0.3.0";

    @Test
    void keepsOnlyDeprecatedKeysTheProjectActuallySets() {
        var deprecations = List.of(
                new DeprecatedProperty("dc.platform.old", "dc.platform.new", "renamed", "error"),
                new DeprecatedProperty("dc.platform.unused", null, null, null));

        UpgradeReport report = UpgradeReport.of(
                Map.of(), Map.of(), Set.of("dc.platform.old", "server.port"),
                deprecations, "0.3.0", NOTES);

        assertThat(report.deprecationHits()).extracting(DeprecatedProperty::name)
                .containsExactly("dc.platform.old");
        assertThat(report.hasDeprecationHits()).isTrue();
    }

    @Test
    void deduplicatesDeprecationHitsByName() {
        var deprecations = List.of(
                new DeprecatedProperty("dc.platform.old", "dc.platform.new", null, null),
                new DeprecatedProperty("dc.platform.old", "dc.platform.new", null, null));

        UpgradeReport report = UpgradeReport.of(
                Map.of(), Map.of(), Set.of("dc.platform.old"), deprecations, "0.3.0", NOTES);

        assertThat(report.deprecationHits()).hasSize(1);
    }

    @Test
    void markdownRendersVersionChangesAndDeprecationsAndNotesLink() {
        UpgradeReport report = UpgradeReport.of(
                Map.of("g:a", "1.0.0"), Map.of("g:a", "2.0.0", "g:b", "1.0.0"),
                Set.of("dc.platform.old"),
                List.of(new DeprecatedProperty("dc.platform.old", "dc.platform.new", "renamed", "error")),
                "0.3.0", NOTES);

        String md = report.toMarkdown();

        assertThat(md).contains("target 0.3.0").contains(NOTES)
                .contains("g:a").contains("1.0.0").contains("2.0.0")
                .contains("_(new)_") // g:b newly managed
                .contains("dc.platform.old").contains("dc.platform.new").contains("renamed");
    }

    @Test
    void consoleSummaryCountsChangesAndHits() {
        UpgradeReport report = UpgradeReport.of(
                Map.of(), Map.of("g:a", "2.0.0"), Set.of(), List.of(), "0.3.0", NOTES);

        assertThat(report.toConsoleSummary())
                .contains("target 0.3.0").contains("1 managed version change")
                .contains("0 deprecated");
        assertThat(report.hasDeprecationHits()).isFalse();
    }
}
