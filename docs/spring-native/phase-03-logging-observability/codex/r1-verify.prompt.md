# Codex verification — Spring-native Phase 3, round 1 reassessment

Your reassessment (`docs/spring-native/phase-03-logging-observability/codex/r1-answer.out.md`) listed issues A–K. They
are addressed at **`0df444a`**. Changes since `29d8173`: `git diff 29d8173..0df444a`. Review read-only. The logging
module runs 160 tests and observability 47, all passing.

| Issue | Change | Tests |
|---|---|---|
| A | `server.port` is compared as a number (stripped, `Integer.valueOf`) | `portsAreComparedAsNumbers` (`08081`, `+8081`, ` 8081 `) |
| B, C | A `WritableJson` the walk meets is masked whole. In the value processor, a `WritableJson` is left to Boot only under one of its formats' object names (`log`, `process`, `service`, `ecs`, `error`, …); any other is masked in every format | `writableJsonIsMaskedWholeWhateverItsEscapes`, `bootsOtherFormatsStillWorkAndAreMasked` (adds a raw credential in ECS, GELF, logstash) |
| D | Character snapshots, map keys and the whole walk are guarded; a failure becomes `[unrenderable]` and nothing propagates | `throwingCharacterDataAndMapKeysCostOnlyTheirPlace` |
| E | Each container loop stops with one `****` when the budget is spent; `null` consumes budget too | `theBudgetStopsTheWalk` (1,000,000 elements, an endless `Iterable` argument) |
| F | A container removes itself from the ancestors only where it was added; beans have their own add/remove | `aBeanHoldingItselfEndsTheDescentMasked` |
| G | Only the exact JDK number classes are trusted; any other `Number` is read from one text snapshot into a `BigDecimal`, or masked text | `numberSubclassesAreReadFromTheirText` |
| H | Extra patterns reach booleans | `extraPatternsReachBooleans` |
| I | Key-value pairs go through `sanitizePair`, which keeps a `traceId`/`spanId` W3C hex ID as the MDC rule does | `keyValueCorrelationIdsAreKept` |
| J | The post-processor records which format system properties were unset when it configured a start, and the next start clears exactly those, whatever it then chooses; one a service set beforehand is never recorded | `formatsAnEarlierPlatformStartLeftBehindAreClearedWhateverComesNext` |
| K | The platform's format runs `FinalLinePass` over the finished line: every member name and string is decoded, a credential-named member is masked whole, every other string is pattern-masked (a top-level `traceId`/`spanId` hex ID excepted), and a line that is not one well-formed JSON object is replaced by a masked one. A service customizer's nested object is masked whole by the value processor (it is deferred JSON outside Boot's format objects) | `aValueANestedProcessorProducesIsMaskedToo` |

## What I'm asking for

- For each of A–K: **resolved**, **partly resolved** or **not resolved**, with your reason.
- List **every** new issue these changes introduce, especially in `FinalLinePass` (JSON decoding and encoding, depth
  tracking, malformed input) and the ownership marker, in this one answer, with severity, file and line, a failure
  scenario and a fix. Say "none" where you find none.
