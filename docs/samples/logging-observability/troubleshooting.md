# Troubleshooting

Each entry is labelled **observed** (it happened during hardening phase 2) or **anticipated**.

| Symptom | Cause | Fix | Label |
|---|---|---|---|
| `prod` startup fails: `FlywayException: Unsupported Database: PostgreSQL 17.x` | Flyway 10+ needs a database module besides the JDBC driver; the generated `pom.xml` comment names only the driver (HI-0041) | Add `org.flywaydb:flyway-database-postgresql` (Oracle: `flyway-database-oracle`) next to the driver | **observed** |
| `OTLP enabled` but the collector receives no traces | Before phase 2 the starter lacked `opentelemetry-exporter-otlp` (HI-0039, fixed) | Upgrade | **observed** (Codex R1) |
| Traces still not exported after setting `management.otlp.tracing.endpoint` | Removed in Boot 4 (our docs showed it until HI-0035) | Use `management.opentelemetry.tracing.export.otlp.endpoint` | **observed** |
| A log shipper rejects the first lines of stdout | Boot's ASCII banner (fixed: off in JSON mode, HI-0045) | Upgrade; do not set `spring.main.banner-mode=console` in JSON mode | **observed** |
| Non-JSON `WARNING: sun.misc.Unsafe…` lines next to the JSON | The JVM's own warnings (protobuf via the OTLP exporter), written to **stderr** | Ship stdout only, or filter stderr; nothing to fix in the service | **observed** |
| `platform.capability.active` missing from `/actuator/prometheus` | Ordering bug when Boot created the registry (fixed, HI-0043) | Upgrade | **observed** (Codex R2) |
| Every pod went unready during a database outage | Readiness used to include `db` (fixed, D104) | Upgrade; point probes at `/actuator/health/readiness`, not `/actuator/health` | **observed** (baseline code, HI-0031) |
| `/actuator/metrics` or `/actuator/platform` returns 404 | Not exposed by the chassis default any more (D105) | `management.endpoints.web.exposure.include: health,info,prometheus,platform,metrics` | **observed** |
| `/actuator/prometheus` returns 404 | Actuator runs on the management port, 8081, not the application port | Scrape port 8081 | **observed** |
| An order ID or other number reads `****` | It is a Luhn-valid 13–19 digit number starting 2–6 (card residual) or a 10-digit ID starting `050`–`058` (UAE mobile) | Known residual with no opt-out: log a different identifier, or accept the mask | anticipated |
| A secret is visible in local logs | `local` profile uses console format, which is not masked (D107) | Expected for local only; never ship console format | anticipated |
| Your own Logback file logs secrets | Your `logging.config` or `logback-spring.xml` replaces Boot's structured logging, and the masking with it; a WARN at startup says so | Delete the file, or use Boot's structured appender in it | anticipated |
