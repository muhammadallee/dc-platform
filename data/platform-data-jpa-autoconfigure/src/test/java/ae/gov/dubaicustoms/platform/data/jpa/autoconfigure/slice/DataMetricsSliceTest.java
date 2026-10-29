package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.autoconfigure.metrics.DataRepositoryMetricsAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.metrics.DataSourcePoolMetricsAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pool and repository meters on a real Hikari pool (scope R8, AC11): Boot's Hikari and Spring Data
 * repository meters are present with the platform's JPA wiring, carry only bounded tag keys, and no tag
 * value holds SQL or an entity ID. The consumer run checks the same meters on {@code /actuator/prometheus}.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "dc.platform.data.jpa.require-migrations=false",
        "spring.datasource.url=jdbc:h2:mem:metrics;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.pool-name=platform-metrics"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({PlatformDataJpaAutoConfiguration.class, MetricsAutoConfiguration.class,
        SimpleMetricsExportAutoConfiguration.class, DataSourcePoolMetricsAutoConfiguration.class,
        DataRepositoryMetricsAutoConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataMetricsSliceTest {

    /** Spring Data's documented tag keys; Hikari tags its meters with the pool name only. */
    private static final Set<String> REPOSITORY_TAGS = Set.of("repository", "method", "state", "exception");

    @Autowired
    private AuditedWidgetRepository widgets;

    @Autowired
    private MeterRegistry registry;

    @Test
    void poolAndRepositoryMetersCarryBoundedTagsWithoutSqlOrIds() {
        AuditedWidget widget = new AuditedWidget();
        widget.referenceCode = "METER-1";
        Long id = widgets.save(widget).id;
        widgets.findById(id);
        widgets.findById(-424242L);
        widgets.count();

        List<Meter> pool = registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("hikaricp.connections")).toList();
        assertThat(pool).isNotEmpty();
        assertThat(pool).allSatisfy(m -> assertThat(tagKeys(m)).containsExactly("pool"));
        assertThat(pool).allSatisfy(m -> assertThat(m.getId().getTag("pool")).isEqualTo("platform-metrics"));

        List<Meter> repository = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("spring.data.repository.invocations")).toList();
        assertThat(repository).extracting(m -> m.getId().getTag("method"))
                .contains("save", "findById", "count");
        assertThat(repository).allSatisfy(m -> assertThat(REPOSITORY_TAGS).containsAll(tagKeys(m)));

        assertThat(registry.getMeters()).flatExtracting(m -> m.getId().getTags()).extracting(Tag::getValue)
                .allSatisfy(value -> {
                    String lower = value.toLowerCase(Locale.ROOT);
                    assertThat(lower).doesNotContain("select", "insert", "update ", "where", "audited_widget");
                    assertThat(value).isNotEqualTo(String.valueOf(id)).doesNotContain("424242");
                });
    }

    private static Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
    }
}
