# Phase 3 — Closure

**Verdict: PASS** (2026-11-21). Branch `native/phase-03-logging-observability` (PR #10), base `a0f58d5`. JSON logs
come from Spring Boot's structured logging, with the platform's format and masking as defaults. Service code logs with
SLF4J only: key-value pairs replace `Kv`, and `dc.platform.logging.redaction.patterns` replaces `LogSanitizer`.
Actuator runs on its own port, 8081. No platform type remains in service code.

## The gates

| Gate | Result |
|---|---|
| A | `scope.md`: baseline at `a0f58d5` (logging-api 5, logging-autoconfigure 150, observability 37, no skips); a structured-logging spike; decision D141 (Boot structured logging with a platform formatter and customizer, `Kv`/`LogSanitizer` removed, ND13 management port, `dependencies` = `db,rabbit`) |
| B | `platform-logging-api`, `PlatformJsonEncoder`, `logback-platform.xml` and logstash removed; every call site, the archetype, the consumer probe and the scripts moved ([`gateB-builds.txt`](evidence/gateB-builds.txt)) |
| C | Codex round 1: 21 findings (1 Critical, 15 High, 5 Medium). Round 1 closed after a reassessment and three verification passes, with revised decision 2 accepted ([`review-round-1.md`](review-round-1.md)) |
| D | Codex round 2: 19 findings, then 4 new ones in the reassessment. Closed at `1f69255` after three medium-effort verification passes, the last with no new issues. Two contract narrowings were accepted ([`review-round-2.md`](review-round-2.md)) |
| E | Issue register HI-0142 to HI-0166. HI-0109 is closed and HI-0112 fixed. HI-0166 was found by Claude in the Gate H full verify: a library object's getters were walked, so Hikari's connection line ran database metadata queries. It was fixed and resolved by three Codex verification passes (`codex/gateH-verify*.md`) |
| F | Consumer run, three in a row: **69 assertions each, 0 failed**, floor 65. Each ran from a fresh repository with no removed module, at `7464f08` ([evidence](evidence/gateF-consumer-runs.txt)). Phase 2's core-errors consumer, re-run on this code, gave 89/89 (floor 77) |
| G | Not this phase (Phases 6 and 9) |
| H | Module pages for logging and observability, book chapter 3, the observability strategy page, the primer and glossary rewritten. Sample `docs/samples/logging-observability` rewritten. New lab `logging-observability`; it and `first-service` pass `tooling/labs/run-lab.sh`. AC18 source scan clean. Full reactor `verify` exit 0. `mkdocs --strict` and `DocsLinkCheckTest` pass |

## Carry-over

Every row of [`scope.md`](scope.md) §6 holds at the final commit with its named test or consumer check. Removed types
and their replacements are listed in `CHANGELOG.md` and [`../expected-removals.md`](../expected-removals.md). AC1–AC18
are in [`../../samples/logging-observability/verification.md`](../../samples/logging-observability/verification.md).

## Documented limits

| Limit | Where |
|---|---|
| Logback's appender formats a message argument itself before any encoder runs; a throwing `toString()` is reported by SLF4J on stderr | HI-0157, logging page |
| A service's own Logback file, or its own customizer's processors, bypass the platform's masking | HI-0160, revised decision 2, logging page |
| Mixed ECS/GELF formats mask those formats' own objects | HI-0165, logging page |
| Extra patterns may not match empty text or `****` | HI-0161, logging page |
| Console output (`local`, `format=console`) is not masked | HI-0037, logging page |

## Carried forward

| Item | Where |
|---|---|
| Hibernate lazy collections and entities logged as values: iterating or reading them can load | Phase 4 (data) |
| Messaging carries the trace in its own header | Phase 6 (Spring AMQP observation) |
| The REST client's own observations | Phase 9 |
| Other capabilities' book and sample pages still describing removed types (bannered) | HI-0110: each capability phase |
| Management port without authentication; keep it inside the cluster | ND13; REST API phase (8) for basic auth |
