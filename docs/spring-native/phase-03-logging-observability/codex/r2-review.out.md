Round 1 is **not fully closed**. I reviewed `0f17c18` read-only against the amended contract and local Boot 4.1.2 bytecode. No files changed. Maven/Docker suites were not run; the attempted Java probe could not initialize the sandbox’s JDK security configuration.

| Question | Result |
|---|---|
| 1. Round 1 fixes hold? | Remaining masking defects and regressions: findings 1–6. |
| 2. CARRIED guarantees provided and proven? | Gaps: findings 1–8. |
| 3. Live documentation matches implementation? | Mismatches: findings 9–19. No additional finding in the new lab itself. |
| 4. Platform types required? | **None** in the ported logging/observability runtime path, probe or new lab. Documentation still recommends removed types: finding 14. Existing archetype test-kit imports remain. |

1. **High — Repeated sanitization can expand a small value until memory exhaustion.**  
   [PlatformValueMasker.java:175](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:175)

   Every container is copied recursively, then Boot invokes the processor again on its descendants, recursively copying their subtrees again. With extra pattern `\S`, replacement text `****` becomes sixteen stars on the next pass. A modestly nested value therefore causes exponential text expansion despite remaining below 10,000 values and depth 64. Preliminary key-value sanitization adds another pass.

   **Fix:** Apply pattern masking once per logical value and carry sanitized containers through serialization without reprocessing their descendants. Test nested values with a pattern that also matches the replacement text.

2. **High — The package exemption still lets ordinary Boot logging objects bypass masking.**  
   [PlatformValueMasker.java:239](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:239)

   Removing the Boot JSON package exemption left `org.springframework.boot.logging.*` exempt. With Boot’s `logstash` format, log a `LogFile` obtained from a resolver containing `logging.file.name=alice@example.com`. It passes unchanged, and Boot writes that email through `LogFile.toString()`. A flat customizer-added member has the same problem, without any service processor.

   **Fix:** Restrict exemptions to actual writer infrastructure at its required positions. Extend `anOrdinaryBootJsonObjectIsData` to ordinary Boot logging objects and flat customizer additions.

3. **High — A context prefix can remove credential-name protection from MDC.**  
   [PlatformLogstashFormatter.java:78](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:78)

   Set `logging.structured.json.context.prefix=ctx`, then put `pwd=correct horse battery staple` in MDC. Unlike key-value pairs, MDC is not sanitized before `ContextPairs` changes its name to `ctx_pwd`. The masker recognizes exact `pwd`, but not suffix `pwd`, so the credential is emitted unchanged. The rename tests do not exercise prefixes.

   **Fix:** Preserve original MDC names for masking before prefixing, and cover configured context prefixes across supported formats.

4. **High — Numeric paths still skip required pattern masking.**  
   [PlatformValueMasker.java:472](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:472)

   At default settings, `addKeyValue("nationalId", 784199012345671L)` exposes an Emirates ID: numbers receive the card check, but `scalar()` skips all text patterns when no extra patterns exist. This is an unfulfilled CARRIED guarantee, although the numeric-Emirates-ID gap predates this phase.

   Separately, non-finite numbers return before extra-pattern processing. Under Boot’s `logstash` format, pattern `^NaN$` does not mask `addKeyValue("value", Double.NaN)`.

   **Fix:** Apply default and configured patterns to every scalar’s safe representation, including non-finite values. Add numeric Emirates-ID and alternative-format non-finite tests.

5. **High — The argument budget is applied after an unbounded rendering pass.**  
   [PlatformLogstashFormatter.java:92](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:92)  
   [StructuredLogMaskingTest.java:580](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/StructuredLogMaskingTest.java:580)

   Logback calls `prepareForDeferredProcessing()` before encoding, which formats the original arguments. Logging a million-entry collection traverses it before the 10,000-value guard; an endless `AbstractMap` argument can hang in its ordinary `toString()` indefinitely. The budget tests call `encoder.encode()` directly and bypass this pass.

   This concerns traversal bounds, not the accepted SLF4J stderr limitation.

   **Fix:** Protect arguments before original-event preparation, and test the budget through the real appender.

6. **Medium — Ordinary MDC/key-value collisions discard entire events.**  
   [PlatformLogstashFormatter.java:77](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:77)

   Put `tenant=dubai` in MDC, then log `addKeyValue("tenant", "dubai")`. Both are submitted independently to Boot’s writer, which rejects duplicate member names. The event is lost. MDC entries named `service`, `message` or other formatter fields also collide. Strict filtering does not resolve collisions between allowed entries.

   **Fix:** Merge context entries with deterministic precedence and protect formatter-owned names before writing. Add real-appender collision tests.

7. **Medium — AC12 measures latency but never asserts it.**  
   [checks.py:253](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/tooling/hardening/scenarios/phase-02/checks.py:253)  
   [phase-02-logging-observability.sh:235](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/tooling/hardening/scenarios/phase-02-logging-observability.sh:235)

   `latency()` passes solely when every response is HTTP 200. Neither it nor the scenario compares outage/slow-collector latency with baseline. I verified in memory that 300 responses taking **10 seconds each** produce a passing result. Thus the named consumer proof does not establish “requests unaffected.”

   **Fix:** Enforce an explicit latency tolerance against baseline, with a suitable absolute ceiling.

8. **Low — The named OTLP proof establishes bean creation, not export.**  
   [OtlpExportSwitchTest.java:39](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/OtlpExportSwitchTest.java:39)

   The CARRIED row says `otlp.enabled=true` “really exports spans,” but its named test deliberately sends nothing and checks only that an exporter bean exists. A broken span-processing/export pipeline can pass it.

   **Fix:** Name AC11 as the export proof in the carry-over table, or make this test create a sampled span and verify receipt.

9. **Medium — Chapter 3’s collector endpoint property is wrong.**  
   [03-logging-observability.md:453](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/book/chapters/03-logging-observability.md:453)

   Copying §4.5 sets `management.tracing.export.otlp.endpoint`, which Boot 4.1.2 does not bind. The configured remote collector receives no traces; the exporter uses its default endpoint.

   **Fix:** Use `management.opentelemetry.tracing.export.otlp.endpoint`, retaining sampling under `management.tracing.sampling`.

10. **High — Live guidance still promises removed actuator authentication.**  
    [03-logging-observability.md:638](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/book/chapters/03-logging-observability.md:638)  
    [operations.md:11](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/samples/logging-observability/operations.md:11)

    The chapter says the security chain protects actuator and that `env`/`configprops` are exposed and authenticated by default. The sample still describes bearer-protected Prometheus; its troubleshooting and open-issues pages repeat that posture. This train has no web security, and those configuration endpoints are not exposed by default. A reader relying on these statements can expose unauthenticated management endpoints.

    **Fix:** Replace these statements with the actual exposure defaults and management-network access requirements; remove the obsolete token mitigation.

11. **Medium — The chapter’s exercise contradicts pod-local readiness.**  
    [03-logging-observability.md:818](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/book/chapters/03-logging-observability.md:818)

    The exercise expects readiness DOWN after stopping the database, and line 825 says readiness includes `db`. The code includes only `readinessState`; `dependencies` becomes DOWN. The monitoring table at line 558 repeats the wrong dependency explanation.

    **Fix:** Expect both probes UP and `dependencies` DOWN, and update the associated hints and monitoring advice.

12. **Medium — The primer tells readers to register logging customizers as beans.**  
    [01-spring-boot.md:103](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/book/primer/01-spring-boot.md:103)

    It says a `StructuredLoggingJsonMembersCustomizer` bean is collected through `ObjectProvider`. Logging initializes before those beans exist; declaring that bean alone does not install it.

    **Fix:** Document `logging.structured.json.customizer` or `spring.factories`, and distinguish this startup mechanism from bean customizers.

13. **Medium — The sample’s verification command targets a deleted module.**  
    [commands.md:44](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/samples/logging-observability/commands.md:44)

    The documented Maven command selects `logging/platform-logging-api`, so it fails reactor-project selection at this commit. The same page also says the consumer adds the parked platform REST-client starter, whereas it now adds Boot’s REST-client starter.

    **Fix:** Remove the deleted module from the command and update the consumer setup description.

14. **Medium — Live recommendations still require removed platform types.**  
    [open-issues.md:9](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/samples/logging-observability/open-issues.md:9)  
    [03-logging-observability.md:637](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/book/chapters/03-logging-observability.md:637)

    The sample recommends `LogSanitizer` beans as a mitigation. The chapter recommends `@SafeText` and `CorrelationId` for injection protection. Those types are absent, so following these instructions cannot compile against the current train.

    **Fix:** Replace the recommendations with the supported extra-pattern/Boot-customizer mechanisms and current Spring validation and tracing guidance.

15. **Low — The chapter still explains the removed encoder/XML implementation.**  
    [03-logging-observability.md:653](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/book/chapters/03-logging-observability.md:653)  
    [03-logging-observability.md:689](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/book/chapters/03-logging-observability.md:689)

    It describes Logstash `[ignore]` mappings and claims the post-processor writes a resolved `dc.platform.logging.service-name` for XML. Neither happens now. An operator following that explanation searches for nonexistent configuration and property-source values.

    **Fix:** Explain the formatter’s member selection and `serviceName()` resolution directly.

16. **Low — The structured-field exercise checks the wrong event property.**  
    [03-logging-observability.md:799](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/book/chapters/03-logging-observability.md:799)

    The exercise uses `addKeyValue("customerId", ...)` but tells readers to inspect message arguments. SLF4J fluent fields are in `ILoggingEvent.getKeyValuePairs()`, so the suggested assertion fails despite correct logging.

    **Fix:** Assert the event’s key-value pairs or the encoded JSON field.

17. **Low — The sample documents the wrong database-outage response.**  
    [operations.md:10](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/samples/logging-observability/operations.md:10)

    It promises HTTP 500, while the current consumer explicitly requires HTTP 503, code `DC-DATA-0503`, and `retryable=true`. A reader derives incorrect retry or alert behavior.

    **Fix:** Update the operational response description to the current mapping.

18. **Medium — The chapter promises fail-soft startup configuration that the contract rejects.**  
    [03-logging-observability.md:671](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/book/chapters/03-logging-observability.md:671)

    It says malformed appender configuration must degrade logging without stopping the service. An invalid enabled redaction pattern such as `[` deliberately fails logging initialization/startup, as required by §6.

    **Fix:** Distinguish recoverable value-rendering failures from invalid startup configuration, which fails fast.

19. **Low — The logging-only zero-config table overpromises trace fields.**  
    [logging.md:71](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2-0947/docs/modules/logging.md:71)

    The table says trace fields work with only the logging starter and no additional setup. That starter brings core auto-configuration, but not the tracing bridge supplied by the core/observability starters. A plain Boot web application adding only this starter does not acquire request tracing.

    **Fix:** State that logging emits existing MDC trace fields and identify the tracing starter prerequisite.

I found no additional defect in the management-port collision fix, accepted same-JVM cleanup behavior, REST-client dependency repair, golden-path/smoke port changes, outbox management-URL repair, or `@PlatformWebTest`’s management-port override.
