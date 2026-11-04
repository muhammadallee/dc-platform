package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.oracle.OracleContainer;

/**
 * One Oracle Free container per test JVM, shared by every Oracle test class (an Oracle instance needs
 * about 2 GB, so one per class would not fit next to the build), and one fresh schema user per JVM, so
 * no state carries over between runs.
 *
 * <p>A cold start copies about 3 GB of datafiles out of the image and took 9 minutes on a laptop
 * disk. The container is therefore reusable: with {@code testcontainers.reuse.enable=true} in
 * {@code ~/.testcontainers.properties} later runs attach to the warm instance; without it every JVM
 * starts (and removes) its own.
 *
 * <p>Production runs Oracle 19c (HD13); only 23ai Free exists as a container, so every test pins
 * Hibernate to major version 19 and every hand-written SQL file stays 19c-safe.
 */
final class OracleContainers {

    /** Present locally; pinned by digest in the phase evidence. */
    static final String IMAGE = "gvenzl/oracle-free:23-slim-faststart";

    private static OracleContainer container;
    private static String user;
    private static String password;

    private OracleContainers() {
    }

    static synchronized OracleContainer oracle() {
        if (container == null) {
            OracleContainer started = new OracleContainer(IMAGE)
                    .withStartupTimeout(Duration.ofMinutes(15))
                    .withReuse(true);
            started.start();
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
            user = "PDT_" + suffix;
            password = "Pw" + suffix;
            createUser(started, user, password);
            container = started;
        }
        return container;
    }

    /** Datasource (the per-JVM user) and the 19c pin, for a {@code @DynamicPropertySource}. */
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> oracle().getJdbcUrl());
        registry.add("spring.datasource.username", () -> {
            oracle();
            return user;
        });
        registry.add("spring.datasource.password", () -> {
            oracle();
            return password;
        });
        registry.add("spring.jpa.properties.jakarta.persistence.database-major-version", () -> "19");
    }

    /** An extra, empty schema user for a test that needs its own schema; {@code [user, password]}. */
    static String[] freshUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String[] credentials = {"PDT_" + suffix, "Pw" + suffix};
        createUser(oracle(), credentials[0], credentials[1]);
        return credentials;
    }

    /** A schema owner with exactly what the tests' migrations and DML need. */
    private static void createUser(OracleContainer oracle, String name, String secret) {
        try (Connection system = DriverManager.getConnection(oracle.getJdbcUrl(), "system", oracle.getPassword());
                Statement statement = system.createStatement()) {
            statement.execute("create user " + name + " identified by \"" + secret + "\" quota unlimited on users");
            statement.execute("grant create session, create table, create sequence, create view, create procedure to "
                    + name);
        } catch (SQLException ex) {
            throw new IllegalStateException("could not create the Oracle test user", ex);
        }
    }
}
