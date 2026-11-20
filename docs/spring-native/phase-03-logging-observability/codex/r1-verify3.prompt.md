# Codex verification 3 — Spring-native Phase 3, round 1

Your verification 2 (`codex/r1-verify2.out.md`) accepted the revised decision 2. It found A, C and E still partly
resolved and listed four new issues. All are addressed at **`d5a27ab`**: `git diff 474ceaa..d5a27ab`. Review read-only.
Logging runs 164 tests and observability 51, all passing.

| Item | Change | Test |
|---|---|---|
| A | `server.port` is bound with Boot's `Binder` from its original value, as Boot binds it | `aTypedPortValueIsBoundAsBootBindsIt` (a `Double` 8081.0 source), `portsAreComparedAsNumbers` |
| C | ECS's five object names are exempt only while `logging.structured.format.console` or `.file` is `ecs`. The customizer cannot tell which of the two formats it serves, so a mixed ECS-and-other setup exempts both; scope decision 2 states this | `ecsObjectNamesAreExemptOnlyUnderEcs` (logstash, a raw writer named `log`) |
| E | A record accessor or bean getter is charged before it is invoked. Loops check the budget before calling `hasNext()` | `failedGettersAndFilteringIteratorsAreChargedToTheBudget` |
| New 1, 3 | Decision 2 (scope §2) is extended, as you offered: whatever a service customizer's own processors produce (value or name processors, root or nested, in any order) is the service's responsibility | — |
| New 2 | Only the logging event (`ILoggingEvent`, Log4j `LogEvent`) and Boot's own writers pass the processor untouched. Any other object from a logging library is masked text, never walked through its getters | `aLoggingLibraryObjectIsMaskedText` |
| New 4 | The post-processor notes which bridge properties are unset. A new listener, `LoggingFormatOwnership`, ordered just after Boot's `LoggingApplicationListener`, records the value Boot actually wrote. The next start clears a property only while it still holds that value. If logging fails to start, nothing is recorded | `formatsAnEarlierPlatformStartLeftBehindAreClearedWhateverComesNext` (a listener's GELF change, a later service value, an unrelated property) |

## What I'm asking for

- For A, C and E, and new issues 1–4: **resolved**, **partly resolved** or **not resolved**, with your reason.
- List **every** new issue `474ceaa..d5a27ab` introduces, in this one answer, with severity, file and line, a failure
  scenario and a fix. Say "none" if none.
