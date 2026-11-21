# Phase 3 — Codex review, round 1

Gate C of the capability recipe (plan §8). Prompts and answers, word for word, are in [`codex/`](codex/).

## The review

- **Reviewed commit:** `7cf2476`.
- **Prompt and answer:** [`r1-review.prompt.md`](codex/r1-review.prompt.md),
  [`r1-review.out.md`](codex/r1-review.out.md).
- **Findings:** 21: 1 Critical, 15 High, 5 Medium.
- **Worktree:** the review ran in a stale worktree checked out at `2c3fc78`; the `worktree add` failed with "already
  exists", and the run went ahead. Codex read the reviewed commit through git: every citation it makes
  (`PlatformLogMasking.java:74`, and others) exists only in `7cf2476`. So the review stands. Every later run uses a
  fresh worktree and checks `git rev-parse --short HEAD` before Codex starts.

## What changed in the design

- **A platform formatter.** A JSON value processor never sees the logging event, so it could not mask a message
  argument by its structure (finding 3). Boot's own `logstash` format with renames and an added `service` member also
  broke ECS and GELF (findings 12, 13).
  - The defaults now name the platform's own formatter, `PlatformLogstashFormatter`: Boot's Logstash layout with the
    platform's field names, written by Boot's JSON writer with every members customizer Boot applies to its own
    formats.
  - It renders the message from its pattern, each argument masked by its structure first, as v3's
    `SanitizedMessageView` did.
  - It sanitizes each key-value pair before Boot writes it.
  - Strict MDC mode filters only context keys.
- **A masker that owns its walk** (findings 2, 4–8, 14):
  - one walk turns every value into a JSON-safe copy;
  - names are kept, so credential names count at any depth;
  - records and beans by their properties;
  - character data, `Path` and JDK value types as masked text, read once;
  - every `Number` checked, extra patterns on numbers and booleans;
  - identity cycles and a per-value budget of 10,000 values.
- **Fail closed on the management port** (finding 1, Critical): startup fails when the application port takes the
  platform's management port, the two compared as Boot binds them.
- **Decision 2 narrowed, as Codex offered** (finding 10).
  - A final parse of the finished line ([`FinalLinePass`, removed](codex/r1-verify.out.md)) reopened masking as a
    JSON-parsing problem, with eight new findings.
  - Decision 2 now states what the guarantee covers. Whatever a service customizer's own processors produce is the
    service's. A customizer's nested object is masked whole, except under one of ECS's five object names while an ECS
    format is configured.
  - Codex accepted this in verification 2.

## The rounds

| Pass | Commit | Codex's verdict |
|---|---|---|
| Review | `7cf2476` | 21 findings |
| Reassessment of Claude's answers ([`r1-answer`](codex/r1-answer.out.md)) | `29d8173` | 11 resolved; it accepts the reply on the pre-existing SLF4J stderr report (finding 3); 11 issues A–K |
| Verification ([`r1-verify`](codex/r1-verify.out.md)) | `0df444a` | 6 resolved, 5 partly; 9 new, 8 of them in the final-pass parser |
| Verification 2 ([`r1-verify2`](codex/r1-verify2.out.md)) | `474ceaa` | accepts revised decision 2; A, C, E partly; 4 new |
| Verification 3 ([`r1-verify3`](codex/r1-verify3.out.md), medium effort) | `d5a27ab` | A, C, E and new 1–3 resolved, new 4 partly; it accepts the mixed-format ECS limit; 3 new (a package-wide Boot JSON exemption, two ownership edge cases) |

After verification 3 (cost rules, 2026-11-20: batch fixes, narrow the contract rather than add machinery):
- The Boot JSON package exemption is removed (test `anOrdinaryBootJsonObjectIsData`).
- The format-ownership listener and marker are removed. At every start the post-processor clears a format system
  property only while it holds the platform's own formatter, which is stateless and survives a failed start (test
  `thePlatformsFormatLeftByAnEarlierStartIsClearedAndNothingElse`; scope §6 states the limit).
- These go to round 2, which reviews the whole phase, instead of a fourth verification pass.

## Disagreements and limits

- **Finding 3, stderr.**
  - Logback's `OutputStreamAppender.subAppend` calls `prepareForDeferredProcessing()` before any encoder runs. So SLF4J
    formats the message itself and reports a throwing `toString()` on stderr.
  - v3 wrote through the same appender, so the gap predates this phase.
  - Codex: "I accept the reply about pre-existing stderr reporting: the appender prepares the event before encoding."
  - It is a documented limit.
- **Finding 10, a service customizer's own processors.** Covered by revised decision 2. Codex: "I accept revised
  decision 2 as a narrower contract, including responsibility for customizer-generated nested output and the explicitly
  documented ECS raw-value exception."
