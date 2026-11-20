# Codex reassessment — Spring-native Phase 3, round 1

You reviewed `7cf2476` in round 1. Your answer is `docs/spring-native/phase-03-logging-observability/codex/r1-review.out.md`,
21 findings. Claude's replies and fixes are at **`29d8173`** (branch `native/phase-03-logging-observability`). Review
read-only. Changes since your review: `git diff 7cf2476..29d8173`. The updated scope is
`docs/spring-native/phase-03-logging-observability/scope.md`; read its §2 decisions 1, 2 and 4, the §3 table and the
§6 carry-over table. The design changed in one place, decision 1. The platform now names its own formatter,
`PlatformLogstashFormatter`, in `logging.structured.format.console` and `.file`, in place of Boot's `logstash` format
with renames.

Your round-1 review was read through git: its worktree was a stale checkout at another commit. Your citations match
`7cf2476`, so the review stands. For this pass, the working tree is at `29d8173`.

## Claude's replies

Test names are in `logging/platform-logging-autoconfigure/src/test/.../StructuredLogMaskingTest.java` (Boot's own
`StructuredLogEncoder` with the platform defaults), `RedactedJsonLogOutputTest.java` (a real application and console
appender), and the observability module's `PlatformObservabilityEnvironmentPostProcessorTest` and
`ZeroConfigAcceptanceTest`. Module runs at `29d8173`: logging 152 tests, observability 44, migrations 4, all passing.

1. **Agree (Critical).** If `management.server.port` comes only from the platform default and equals `server.port`,
   startup now fails with a message naming both properties. An explicit same-port setting stays the service's choice.
   Tests: `anApplicationPortOnThePlatformsManagementPortFailsStartup`, `anExplicitSamePortIsTheServicesChoice`,
   `anApplicationPortOnTheManagementPortRefusesToStart`. Commit `1318ff1`.
2. **Agree.** A `WritableJson` a service logs as a key-value pair or a message argument becomes masked text. The
   platform's formatter sanitizes each key-value pair before Boot writes it. The value processor itself leaves
   `WritableJson` alone: Boot writes its own nested objects (ECS, GELF) as `WritableJson` through the same processor, so
   converting them there broke those formats. Test `writableJsonIsMaskedText`. Commit `1afe444`.
3. **Partly agree.**
   - **The JSON line: agree.** The platform's formatter renders the message from `event.getMessage()` and its
     arguments, each masked by its structure first (as v3's `SanitizedMessageView` did). A map argument's credential
     value is masked whole, and a throwing `toString()` becomes `[unrenderable]`. Tests
     `messageArgumentsAreMaskedByTheirStructure` and `aThrowingMessageArgumentCostsOnlyItsPlaceInTheJsonLine` (through
     a real console appender).
   - **The stderr report: it predates this phase.** Logback's `OutputStreamAppender.subAppend` calls
     `prepareForDeferredProcessing()`, and through it `getFormattedMessage()`, before any encoder runs. So SLF4J
     formats the message and reports a throwing `toString()` itself, whatever the encoder does. v3 wrote through the
     same `ConsoleAppender`, and its `EncoderRedactionTest` called `encode()` directly, never the appender.
   - **Evidence:** `javap -c ch.qos.logback.core.OutputStreamAppender` (logback-core 1.5.6), `subAppend`: `instanceof
     DeferredProcessingAware` → `prepareForDeferredProcessing()`. `LoggingEvent.prepareForDeferredProcessing()` calls
     `getFormattedMessage()`.
   - It is a documented limit (scope §6). Commit `1afe444`.
4. **Agree.** An application object becomes a map of its bean properties (public getters), so a getter-backed
   credential is masked by name. A getter that throws gives `[unrenderable]`. A record whose component cannot be read
   gives `[unrenderable]` for that component. Test `beanPropertiesKeepTheirNames`. Commit `1afe444`.
5. **Agree.**
   - A `Path` is masked text.
   - A `CharSequence` is read once, and the checked string is what Boot writes, for the `traceId`/`spanId` exemption
     too.
   - Test `textLikeValuesAreRenderedOnceAndMasked`. Commit `1afe444`.
6. **Agree.**
   - Any other `Number` is read through its decimal text and checked like the others.
   - A non-finite `double` or `float` becomes text.
   - Test `everyKindOfNumberIsChecked`. Commit `1afe444`.
7. **Agree.** A `char[]` is one masked text value. Test `characterArraysAreText`. Commit `1afe444`.
8. **Agree.** The extra patterns apply to a number's text; a match makes it masked text. Test
   `extraPatternsReachNumbers` covers top-level and nested values. Commit `1afe444`.
9. **Agree.** A member that `logging.structured.json.rename` renames to a credential name is masked whole. The
   platform's own format no longer renames anything. Test `aMemberRenamedToACredentialNameIsMasked`. Commit `1afe444`.
10. **Partly agree; Low, not High.** A service's own customizer is service code running inside the JSON writer. It can
    write anything, including a value it builds after the platform's processor has run, just as the service can log the
    secret in the first place. The platform does not run untrusted code here. The module page will say that a service's
    own customizer and its nested value processors are its responsibility. No code change.
11. **Agree.** Strict mode moved into the platform's formatter. It filters the MDC and key-value pairs only, to
    `traceId`, `spanId` and the listed keys, so an unlisted key named `stack_trace` or `tags` is left out. Test
    `strictModeFiltersOnlyContextKeysWhateverTheirNames`. Commit `1afe444`.
12. **Agree.** The platform no longer adds `service` through `logging.structured.json.add`; its formatter writes it.
    ECS and GELF stay valid. Test `bootsOtherFormatsStillWorkAndAreMasked`, which encodes ECS, GELF and Logstash with
    the platform's defaults. Commit `1afe444`.
13. **Agree.** With no path filter, strict mode no longer touches ECS or GELF fields; it applies to the platform's
    format. Same test. Commit `1afe444`.
14. **Agree.**
    - The walk tracks its ancestors by identity, so a value shared between branches is shown at each place.
    - It has a budget of 10,000 values per walk; the rest are masked.
    - Tests: `branchingCyclesAndLargeValuesStayBounded` (two self-references and 50,000 elements, under 5 s) and
      `aSharedButAcyclicValueIsShownAtEachPlace`.
    - Commit `1afe444`.
15. **Agree** (confirmed: Phase 2's run got a `RestClient.Builder` only from the parked starter). The scenario adds
    `spring-boot-starter-restclient` to the generated POM. Commit `29d8173`. The consumer run itself is Gate F.
16. **Agree.** The golden path now requires `logstash-logback-encoder` to be absent from the jar. Commit `29d8173`.
17. **Agree.** The patterns are read and compiled only when logging and redaction are both on. Test
    `anInvalidPatternFailsOnlyWhenMaskingIsOn`. Commit `1afe444`.
18. **Agree** (confirmed: Boot's `LoggingSystemProperties` sets a system property only when it is absent, and never
    clears it). In console mode, or with logging off, the post-processor clears a format system property that holds the
    platform's formatter. A value that is not the platform's is left alone. Tests
    `aFormatThePlatformSetInAnEarlierStartIsClearedForConsoleLogging`, and
    `consoleLoggingAfterAJsonStartInTheSameJvmIsConsole` (two starts, no cleanup between them). Commit `1afe444`.
19. **Agree.** Each outbox instance keeps its management URL, switched with its application URL. Commit `29d8173`.
20. **Agree**, and 21. **agree.** The recipe swap is reverted. An automated dependency swap cannot migrate a service's own
    Logback file or its `StructuredArguments` calls, so the adoption guide covers the move by hand. Commit `29d8173`.

## What I'm asking for

- For each finding, say **resolved**, **partly resolved** or **not resolved**, with your reason. For 3 and 10, say
  whether you accept the reply.
- List **every** new issue the changes since `7cf2476` introduced, and any round-1 finding a fix only moved elsewhere,
  in this one answer: the formatter, the masker's walk, the port check and the tooling.
- Severity, file and line, a concrete failure scenario and a proposed fix for each. Say "none" where you find none.
