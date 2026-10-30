package ae.gov.dubaicustoms.hardening.probe;

import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer-side probe for the hardening phase-03 harness (never part of a real service). It uses the
 * database the way a service does: a Spring Data repository, the {@code EntityManager} directly, a
 * {@code JdbcTemplate}, and a transaction that holds its connection. Public API, documented
 * configuration and plain Spring only; failures are left to the platform's error handling.
 */
public final class DataProbe {

    private DataProbe() {
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        SecurityCustomizer probePathsArePublic() {
            return http -> http.authorizeHttpRequests(auth -> auth.requestMatchers("/probe/**").permitAll());
        }
    }

    @Service
    public static class ParcelService {

        private static final Logger log = LoggerFactory.getLogger(ParcelService.class);

        private final HhParcelRepository repository;
        private final EntityManager entityManager;

        public ParcelService(HhParcelRepository repository, EntityManager entityManager) {
            this.repository = repository;
            this.entityManager = entityManager;
        }

        public long saveThroughRepository(HhParcel parcel) {
            return repository.saveAndFlush(parcel).getId();
        }

        @Transactional
        public long saveThroughEntityManager(HhParcel parcel) {
            entityManager.persist(parcel);
            entityManager.flush();
            return parcel.getId();
        }

        /** Takes a connection (transaction begin), holds it, then inserts: pool exhaustion, SIGTERM, silent peer. */
        @Transactional
        public long hold(String ref, long millis) throws InterruptedException {
            long before = repository.count();
            log.info("hold-started {}", ref);
            Thread.sleep(millis);
            HhParcel parcel = repository.saveAndFlush(new HhParcel(ref, 1, "held", 1L));
            log.info("hold-finished {} (count before {})", ref, before);
            return parcel.getId();
        }
    }

    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        private final ParcelService parcels;
        private final HhParcelRepository repository;
        private final JdbcTemplate jdbc;

        public ProbeController(ParcelService parcels, HhParcelRepository repository, JdbcTemplate jdbc) {
            this.parcels = parcels;
            this.repository = repository;
            this.jdbc = jdbc;
        }

        @PostMapping("/parcels")
        public Map<String, Object> create(@RequestParam String ref, @RequestParam(required = false) Integer weight,
                @RequestParam(required = false) String label, @RequestParam(defaultValue = "1") long consignor,
                @RequestParam(defaultValue = "repository") String path) {
            HhParcel parcel = new HhParcel(ref, weight, label, consignor);
            long id = "entity-manager".equals(path)
                    ? parcels.saveThroughEntityManager(parcel)
                    : parcels.saveThroughRepository(parcel);
            return Map.of("id", id, "path", path);
        }

        @GetMapping("/parcels/count")
        public Map<String, Object> count(@RequestParam String ref) {
            return Map.of("ref", ref, "count", repository.countByRefCode(ref));
        }

        @GetMapping("/db")
        public Map<String, Object> db() {
            return Map.of("parcels", repository.count());
        }

        @GetMapping("/identity")
        public Map<String, Object> identity() {
            return jdbc.queryForObject(
                    "select user, sys_context('USERENV', 'CURRENT_SCHEMA') from dual",
                    (rs, row) -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("user", rs.getString(1));
                        result.put("currentSchema", rs.getString(2));
                        return result;
                    });
        }

        @PostMapping("/hold")
        public Map<String, Object> hold(@RequestParam String ref, @RequestParam long millis) throws InterruptedException {
            return Map.of("id", parcels.hold(ref, millis), "ref", ref);
        }

        @GetMapping("/context")
        public Map<String, Object> context() {
            return Map.of("db", false);
        }
    }
}
