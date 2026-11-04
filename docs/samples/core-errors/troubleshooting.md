# Troubleshooting

Each entry is labelled **observed** (it happened during hardening phase 1) or **anticipated**.

| Symptom | Cause | Fix | Label |
|---|---|---|---|
| Error bodies have no `traceId` | No tracer: tracing disabled, or the core starter excluded | Keep `platform-starter-core` (it brings the OTel bridge); do not set `management.tracing.enabled=false` | anticipated |
| Logs still show `correlationId`, not `traceId` | Old platform version, or a custom logback pattern | Upgrade; query on `traceId` | anticipated |
| Callers send `X-Correlation-Id` and correlate nothing | The header is ignored by design | Callers send `traceparent` | anticipated |
| A filter's `sendError(409)` came back as **401** for anonymous callers | The security chain re-authorized the error page (fixed in phase 1, HI-0011) | Upgrade | **observed** |
| A `@PreAuthorize` denial came back as **500** | The errors catch-all swallowed `AccessDeniedException` (fixed, HI-0012) | Upgrade | **observed** |
| A rejected bearer token got an empty 401 | Resource-server entry point bypassed the platform body (fixed, HI-0013) | Upgrade | **observed** (Codex R1) |
| App fails with "Port 8080 was already in use" in a container | Code calling `WebServerApplicationContext.getWebServer().getPort()` during startup starts the connector early | Resolve the port on first use, not in a constructor | **observed** (in the probe) |
| `LOGGING_LEVEL_…_CORRELATIONIDFILTER=DEBUG` has no effect | Environment-variable relaxed binding lower-cases the class name; Logback logger names are case-sensitive | Set the package level (`LOGGING_LEVEL_AE_GOV_DUBAICUSTOMS_PLATFORM_CORE_AUTOCONFIGURE=DEBUG`) or use `logging.level.<Class>` in YAML | **observed** |
| `@Async` work has no trace | Executor not built by Boot | Wrap with `ContextPropagatingTaskDecorator` | anticipated |
| `rejectedValue` missing in validation errors | Off by default (PII) | `dc.platform.errors.include-rejected-values=true` if you are sure the fields hold no personal data | anticipated |
