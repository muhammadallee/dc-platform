# Codex review — Spring-native Phase 3 (logging + observability)

You are the independent reviewer for a Spring Boot 4.1.2 platform ("chassis") in this repository. Review read-only.

**Base commit:** `a0f58d5` (`develop`, start of Phase 3). **Reviewed commit:** `7cf2476` (branch
`native/phase-03-logging-observability`).

## What the phase must do

Read `docs/spring-native/plan.md` §4 (rules, especially 12, 13 and 15), §7 (the quality bar) and the section
"Phase 3 — Logging + observability, Spring-native". The phase's scope is
`docs/spring-native/phase-03-logging-observability/scope.md`. Its **carry-over table (§6) is a requirement**: every
`CARRIED` row must still hold, proven by the named test; every `RETIRED` row must really be covered by Spring or by
the named replacement. Treat every claim in `scope.md` as a claim to check, not as a fact. The test disposition (§7)
says which v3 tests were kept, ported or retired; check that nothing retired was still needed.

The design (decision D141 in `docs/decisions/decision-log.md`, evidence
`docs/spring-native/phase-03-logging-observability/evidence/gateA-structured-logging-spike.txt`):
- JSON logs are Spring Boot's structured logging in the Logstash format. `PlatformLoggingEnvironmentPostProcessor` sets
  the formats (console and file), renames, exclusions and a `service` member as lowest-precedence defaults.
- Masking is `PlatformLogMasking`, a `StructuredLoggingJsonMembersCustomizer` registered in `META-INF/spring.factories`,
  applying `PlatformValueMasker` (the v3 masker, now on Boot's `MemberPath`) as a JSON value processor, plus a path
  filter for strict MDC mode.
- `Kv`, `LogSanitizer`, `platform-logging-api`, `PlatformJsonEncoder`, `logback-platform.xml` and
  `logstash-logback-encoder` are removed.
- Actuator moves to `management.server.port` 8081 (ND13); the `dependencies` health group is `db,rabbit` (HI-0112).

## How to read the change

- Deleted files: `git diff --name-only --diff-filter=D a0f58d5..7cf2476`. Read what they did at `a0f58d5` where you need
  to compare, above all `PlatformJsonEncoder`, `SanitizedMessageView`, `CredentialFieldMasker`,
  `RawJsonMaskingGenerator` and the deleted `EncoderRedactionTest`.
- Everything else: `git diff --diff-filter=d a0f58d5..7cf2476`. Skip generated and evidence files: `docs/reference/*`,
  `docs/llms*.txt`, `docs/spring-native/phase-03-logging-observability/evidence/**`.
- The main code: `logging/platform-logging-autoconfigure` and `observability/platform-observability-autoconfigure` (main
  and test). Also the consumer harness (`tooling/hardening/lib/harness.sh`), the ported run
  (`tooling/hardening/scenarios/phase-02-logging-observability.sh`, `phase-02/probe/ObservabilityProbe.java`), the
  golden path and smoke scripts, `@PlatformWebTest`, the archetype, the adoption recipe and the `first-service` lab.
- **Docs are Gate H, not this round:** the module pages, samples and most book pages still describe v3 and will be
  rewritten before closure. Only report a docs problem in the files this commit changed.
- You may read any file in the repository, and Spring Boot's sources or jars in `~/.m2` to check how Boot behaves.

## The questions

1. **Is any guarantee lost?** For each `CARRIED` row, does the new code still provide it on every path the old code
   did? In particular, does every value Boot writes reach the masker: the message, MDC values, key-value pairs at any
   depth and of any type (maps, lists, arrays, primitive arrays, records, other objects, numbers, booleans, bytes),
   the stack trace, and members another customizer adds? Can anything reach stdout or a log file unmasked: the file
   appender, Boot's own `tags` or other members, a message argument, the ECS or GELF formats if a service picks one, a
   value Boot renders with `toString()` after the processor runs? Does the named test prove each row?
2. **Is any `RETIRED` row not really covered?** `LogSanitizer` (HI-0034) by the patterns property and a service's own
   customizer; `raw(...)`/Logstash markers (HI-0047, HI-0048) by their absence.
3. **What new risk is missing?** Look for: the order of Boot's own JSON customizers (include/exclude/rename) against the
   platform's value processor and path filter; strict mode seeing renamed or original names; the kill switches
   (`dc.platform.logging.enabled`, `redaction.enabled`) on every path; JVM-wide logging system properties; the
   management port default and `server.port=0`; actuator reachable on the application port by any route; tests or
   tools that still read actuator on the application port; a service's own Logback file; startup when a pattern is
   invalid; performance of the masker on large values.
4. **Rule 12 and contract 12:** does any service-facing path still need a platform type? Does the consumer probe, the
   archetype or an example import one?

## Output

For each finding: severity (Critical / High / Medium / Low, as defined in plan §6), file and line, a concrete failure
scenario, your reasoning, and a proposed fix. List **every** finding you can establish in this one answer. Say
explicitly when you find nothing for a question. Do not summarise the change back.
