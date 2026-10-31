# Troubleshooting

Each entry is labelled **observed** (it happened during hardening phase 3) or **anticipated**.

| Symptom | Cause | Fix | Label |
|---|---|---|---|
| `prod` startup fails: `Failed to bind properties under 'spring.datasource.hikari.connection' to java.sql.Connection … Connection reset` | The environment variable `SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL` binds Hikari's `connection` getter, which opens a connection | Drop the dashes in environment names: `SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL` | **observed** |
| `prod` startup fails in Flyway with `ORA-17008: Closed connection` during a migration | The migration statement ran longer than the read timeout on Flyway's connection (seen with the former 60 s default on a slow disk, HI-0070) | Upgrade (10 min default); for longer statements raise `dc.platform.data.jpa.oracle.flyway-read-timeout`; without a separate Flyway user raise `read-timeout` | **observed** |
| A batch transaction with `@Transactional(timeout = 300)` fails after about 60 s with `ORA-18730 … Socket read timed out` | The driver read timeout bounds each wait for the database's answer, whatever the transaction timeout | Raise `dc.platform.data.jpa.oracle.read-timeout` above the longest statement | **observed** (backstop scenario) |
| Startup against a database that accepts connections but never answers takes about 10 minutes to fail | The connect timeout covers only the TCP connect; Flyway's 10 min read timeout ends the handshake | Expected; lower `flyway-read-timeout` if a faster failure matters more than long migrations, or rely on the startup probe | **observed** |
| A request that hit a silent database got `500 DC-CORE-0500` instead of a 503 | Spring rethrew the failed rollback over Hikari's closed connection (HI-0068, fixed) | Upgrade | **observed** |
| Three WARN lines per constraint violation | Hibernate's `org.hibernate.orm.jdbc.error` logs the error code and driver message before the platform's WARN (scope A2) | Expected; set `logging.level.org.hibernate.orm.jdbc.error=error` to keep only the platform's line (you then lose Hibernate's record of failures your code catches) | **observed** |
| Read of an `Instant` fails with ORA-18716 | The column is a plain `TIMESTAMP`; Hibernate reads `Instant` as an offset date-time (D114) | Declare `Instant` columns `TIMESTAMP WITH TIME ZONE` | **observed** (container) |
| Inserting `""` into a NOT NULL column returns 409 | Oracle stores an empty string as NULL | Expected; validate empty input before saving | **observed** |
| `prod` startup fails: `Unsupported Database: …` | Flyway 10+ needs `flyway-database-<db>` besides the driver (HI-0041) | Use `platform-starter-data-jpa-oracle` (Oracle); other databases: add the module | **observed** (phase 2) |
| Startup fails with the out-of-order diagnostic | `spring.flyway.out-of-order=true` (or a customizer) outside the `local` profile | Remove it; renumber the migration instead | **observed** (tests) |
| Startup fails: migration file not named `V<version>__<description>.sql` | `validate-migration-naming` is pinned on | Rename the file | **observed** (tests) |
| A connection to Oracle has no read timeout | The pool was started by the application (`new HikariDataSource(config)`), a `Flyway` bean of its own was built from a URL, or a `{vendor}` location was used with a separate Flyway user | Set `oracle.jdbc.ReadTimeout` and `oracle.net.CONNECT_TIMEOUT` on that config, or as JVM system properties | **anticipated** (documented residual, HI-0064) |
| A `SelectionQuery` or `getDelegate()` query ignores a shorter transaction deadline | Those paths take the 30 s query default (HI-0065) | `.setTimeout(seconds)` on the query | **observed** (container) |
| ORA-00972 naming a migration at startup | An identifier longer than the database allows | Shorten it (the startup failure names the file) | **observed** (container) |
