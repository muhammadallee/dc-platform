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
| `/actuator/prometheus` returns 401 | Not in the security starter's permit paths (interim until Phase 7) | Scrape with a bearer token | **observed** |
| An order ID or other number reads `****` | It is a Luhn-valid 13–19 digit number starting 2–6 (card residual) or a 10-digit ID starting `050`–`058` (UAE mobile) | Known residual with no opt-out (a `LogSanitizer` runs before the masks, so it cannot restore the value): log a different identifier, or accept the mask | anticipated |
| A secret is visible in local logs | `local` profile uses console format, which is not masked (D107) | Expected for local only; never ship console format | anticipated |
| Your own Logback file logs secrets | Masking lives in `PlatformJsonEncoder`; a custom `logging.config` replaces it | Use `PlatformJsonEncoder` as the encoder in your appender | anticipated |
| A `LogSanitizer` bean has no effect | Redaction is off (`dc.platform.logging.redaction.enabled=false`), or several contexts in one JVM (the last started wins) | Keep redaction on; in tests, start one context at a time | anticipated |
