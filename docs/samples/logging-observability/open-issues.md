# Open issues

| ID | Impact | Owner | Mitigation | Link |
|---|---|---|---|---|
| HI-0041 | A generated `data` service in `prod` needs `flyway-database-<db>` besides the JDBC driver; the generated comment does not say so | repository owner (Phase 3) | Add the module by hand (see troubleshooting) | [issue register](../../production-readiness/issue-register.md) |
| G7-02 | Prometheus scrape needs a bearer token; management port / permit rule undecided | repository owner (Phase 7) | Scrape with a token | [scope R7.3](../../production-readiness/phase-02-logging-observability/scope.md) |
| D103 | Head sampling cannot keep every failed request's trace | deployment (platform posture OPEN) | Collector tail sampling with an error policy (snippet in [`operations.md`](operations.md)) | [decision log](../../decisions/decision-log.md) |
| D107 | Console format (`local` profile) is not masked and CR/LF can split a line | accepted by owner (2026-10-26) | Never ship console format | [issue register HI-0037](../../production-readiness/issue-register.md) |
| — | Redaction residuals (Luhn-valid numeric IDs, landlines without prefix, values nested > 64 levels / cyclic, bean `toString` text in `message`, unparseable `raw(...)` text, top-level `traceId`/`spanId` hex exemption, custom `LogstashMarker` subclasses and serializers) | repository owner (acceptance at phase closure) | `dc.platform.logging.redaction.patterns`, `LogSanitizer` beans | [review round 2](../../production-readiness/phase-02-logging-observability/review-round-2.md) |
| HI-0004 | Book chapters still narrate older behaviour in places | repository owner | Module pages are current | [issue register](../../production-readiness/issue-register.md) |
