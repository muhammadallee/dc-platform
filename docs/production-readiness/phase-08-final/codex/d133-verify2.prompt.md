# Phase 8: verification of the dependency change (D133), pass 2

Static review only. Your pass 1 output is `codex/d133-verify.out.md`. The fixes are `git diff 097bf12 HEAD`:

- F1 (a consumer importing Boot's BOM above `platform-bom`): documented in `docs/book/appendix/d-compatibility.md` as an unsupported order, with the reason. `platform-service-parent` (the archetype's route) is not affected, as you confirmed. No build-time check is added.
- F2 (Zipkin exporter 1.62.0 beside SDK 1.67.0): `platform-service-parent` bans a direct `io.opentelemetry:opentelemetry-exporter-zipkin` dependency with a message pointing to OTLP; `docs/modules/observability.md` says Zipkin is unsupported. The exporter is discontinued at 1.64.0. OpenTelemetry stays at 1.67.0 because CVE-2026-118259 is CPU cost in parsing the inbound `tracestate` header, on every request. Checked: a POM with the parent and that dependency fails `validate` with the message.

1. Confirm each, or show it wrong with a trigger. Say if F1's documentation-only answer leaves a route a consumer is likely to take by default.
2. Any other route by which a service built on `platform-service-parent` gets a binary-incompatible OpenTelemetry artifact (anything Boot 4.1.2 manages under `io.opentelemetry` that the 1.67.0 BOM does not).

Report concrete defects only, each with file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
