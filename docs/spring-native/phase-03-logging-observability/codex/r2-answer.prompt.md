# Codex reassessment — Spring-native Phase 3, round 2

Your round-2 review (`codex/r2-review.out.md`) listed 19 findings. Claude's replies and fixes are in **`8c3efef`**
(`git diff 0f17c18..8c3efef`). Review read-only. Test runs at that commit: logging 170, observability 51, errors 106,
all passing. The docs checks pass: snippets 26/26, the link check, and `mkdocs --strict`. Tests are in
`StructuredLogMaskingTest` unless named otherwise.

| # | Reply | Change and test |
|---|---|---|
| 1 | Agree | Extra patterns apply between the masks already in the text, so masking is idempotent. The masker's own copies (`SafeMap`, `SafeList`) are not walked again. Test: `maskingIsIdempotentSoNestedValuesDoNotGrow` |
| 2 | Agree | The `org.springframework.boot.logging` exemption is gone. Only the logging event, the masker's copies and ECS's deferred objects (under ECS) pass untouched. Test: `anOrdinaryBootLoggingObjectIsData` (`LogFile`) |
| 3 | Agree | The platform's format sanitizes each MDC value under its own name before Boot prefixes it. In Boot's formats, the customizer strips the configured `context.prefix` for the credential check. Test: `aContextPrefixKeepsCredentialNames`, in the platform's format and logstash |
| 4 | Agree | Integral numbers go through every pattern (a numeric Emirates ID). A fraction gets only the extra patterns, keeping v3's no-false-positive rule. A non-finite number is masked text. Test: `theDefaultPatternsReachNumbers` |
| 5 | Partly agree | It is outside the platform's reach and was the same in v3. Logback's `OutputStreamAppender.subAppend` calls `prepareForDeferredProcessing()` before any encoder, and SLF4J formats the original arguments there. This is the same mechanism as the accepted stderr limit. Now documented on the logging page and in scope §6: the appender's own formatting is unbounded, so log large structures as bounded key-value pairs, not as message arguments. The platform's rendering stays bounded |
| 6 | Agree | The MDC and key-value pairs become one context in the platform's format. A key-value pair wins over an MDC entry of the same name. An entry named like a layout member is left out. Test: `contextNameCollisionsNeverLoseTheLine` |
| 7 | Agree | The `latency` check takes a p99 bound. The scenario derives it from the baseline: at most three times the baseline p99, at most 250 ms above it, and never under 1 s. All four outage runs assert it |
| 8 | Agree | The carry-over row names AC11 (a real collector receives spans and metrics) as the export proof; the unit test proves the exporter bean |
| 9–19 | Agree | Fixed in chapter 3, the primer, the logging page and the sample. Covers: the OTLP endpoint property; no actuator authentication in 1.0.0; pod-local readiness; the formatter's fields and `service`; fail-fast invalid patterns; the key-value exercise; the tracing prerequisite; the sample's commands, the 503 mapping and the management-port scrape. `LogSanitizer`, `@SafeText` and `CorrelationId` are gone from the live pages |

## What I'm asking for

- For each finding: **resolved**, **partly resolved** or **not resolved**, with your reason. For 5, say whether you
  accept the documented limit.
- List **every** new issue `0f17c18..8c3efef` introduces, in this one answer, with severity, file and line, a failure
  scenario and a fix. Say "none" if none.
