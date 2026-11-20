# Codex verification 2 — Spring-native Phase 3, round 1

Your verification (`codex/r1-verify.out.md`) resolved B, D, F, G, H and I. It found A, C, E, J and K partly resolved,
and new defects 5–13, all but two of them in the final-pass JSON parser. Changes since `0df444a` are at **`474ceaa`**:
`git diff 0df444a..474ceaa`. Review read-only. Logging runs 161 tests and observability 50, all passing.

**K, and with it 4–11:** the final-pass parser (`FinalLinePass`) is removed. It turned masking into a JSON-parsing
problem. Instead, as you offered in your reassessment, decision 2 in `scope.md` §2 now states what the guarantee covers,
and the carry-over table (§6) has a row for it:
- every value a service logs, and every flat member its own customizer adds, is masked;
- a nested object its customizer adds is masked whole, unless it is named like one of the five objects Boot's ECS format
  writes;
- there, and for whatever the customizer's own nested value processors produce, the output is the service's
  responsibility, because the customizer is service code running inside the writer.

**A:** `server.port` is converted with Boot's `ApplicationConversionService`, the same conversion Boot applies.
`portsAreComparedAsNumbers` adds `0x1f91`, `#1f91` and `8 081`.

**C:** the exempt names are exactly ECS's deferred objects: `log`, `process`, `service`, `ecs`, `error`. `event` and the
other speculative names are gone. Boot's Logstash and GELF formats write no deferred objects, so in those formats every
`WritableJson` is masked whole. The one remaining case is a `WritableJson` a service logs under one of those five
names while it uses Boot's ECS format. `scope.md` decision 2 states it as the limit of that format; the platform's own
format sanitizes key-value pairs before Boot writes them.

**E:**
- Every visited value is charged before the credential check.
- Maps, iterables, arrays, records and beans check the budget before they fetch or read the next element.
- The budget stays per value: each argument and each key-value pair is bounded by 10,000 values.
- Test `credentialNamedEntriesAreChargedToTheBudgetToo` covers a million credential-named keys and an endless map.

**J (12, 13):**
- For each of the two bridge properties that was unset when the platform configured a start, the post-processor records
  the exact value Boot copies in from that environment.
- The next start clears the property only while it still holds that value, so a value the service set since is kept.
- Only `CONSOLE_LOG_STRUCTURED_FORMAT` and `FILE_LOG_STRUCTURED_FORMAT` are ever cleared, whatever the marker holds.
- Test: `formatsAnEarlierPlatformStartLeftBehindAreClearedWhateverComesNext`.

## What I'm asking for

- For A, C, E, J and K: **resolved**, **partly resolved** or **not resolved**, with your reason. For K and the C
  remainder, say whether you accept the revised decision 2.
- List **every** new issue `0df444a..474ceaa` introduces, in this one answer, with severity, file and line, a failure
  scenario and a fix. Say "none" if none.
