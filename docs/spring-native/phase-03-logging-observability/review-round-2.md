# Phase 3 — Codex review, round 2

Gate C of the capability recipe (plan §8), second round: a review of the whole phase after round 1. Prompts and
answers, word for word, are in [`codex/`](codex/).

## The review

- **Reviewed commit:** `0f17c18`.
- **Prompt and answer:** [`r2-review.prompt.md`](codex/r2-review.prompt.md),
  [`r2-review.out.md`](codex/r2-review.out.md).
- **Findings:** 19: 8 High, 7 Medium, 4 Low. Findings 1–6 concern masking. Finding 7 is about the consumer harness,
  finding 8 the OTLP proof, and findings 9–19 the live documentation.
- **Platform types:** Codex found none on the ported runtime path, the probe or the new lab (question 4).

## What changed in the design

The cost rules of 2026-11-20 apply: batch the fixes, narrow the contract rather than add machinery, and verify at
medium effort.

- **Mask once.** Text the masker produces is a `SafeText`. The masker's copies (`SafeMap`, `SafeList`) and their
  members are left alone after that, so every value is masked exactly once. The segmenting `outsideMasks` loop is
  gone, and extra patterns match the whole text again. A message's arguments are masked, then the finished message
  gets one more pass: two bounded passes.
- **Narrowed: patterns that could grow.** An extra pattern that matches empty text or the mask `****` itself fails
  startup. A pass that would grow text past four times its input (+64) fails closed to `****`.
- **Boot's prefix rule.** The credential check strips a context prefix the way Boot's `ContextPairs.joinWith` joins it,
  and strips GELF's leading underscore. Collisions are decided on the written name. A layout member's name is left
  out. Of two keys written under one name, the latest occurrence wins.
- **Numbers.** An integral number is checked first as plain digits, which mask the whole value on a match, and then as
  written. A `double` Emirates ID written as `7.84199012345671E14` is therefore caught.
- **Narrowed: format objects.** ECS's five objects and GELF's numeric `timestamp` pass untouched only when the console
  format, and the file format where a log file is written, are that one format. A mixed setup exempts nothing; the
  logging page says so.

## The rounds

| Pass | Commit | Codex's verdict |
|---|---|---|
| Review | `0f17c18` | 19 findings |
| Reassessment of Claude's answers ([`r2-answer`](codex/r2-answer.out.md)) | `8c3efef` | 15 resolved; it accepts the limit on finding 5; 1, 3, 4 and 6 partly; 4 new |
| Verification ([`r2-verify`](codex/r2-verify.out.md), medium effort) | `0d0dcf3` | 3, 4 and new 1, new 4 resolved; 1, 6 and new 2, new 3 partly; 5 new |
| Verification 2 ([`r2-verify2`](codex/r2-verify2.out.md), medium effort) | `7b4aec8` | it accepts both narrowings; new 1, 2, 3 and 5 resolved; new 4 and 6 partly; 2 new |
| Verification 3 ([`r2-verify3`](codex/r2-verify3.out.md), medium effort) | `1f69255` | both resolved; **no new issues** |

Verification 1's new issue 5, a null message template with arguments that threw, came from this round's own diff and
was fixed in the same round (test `theMessageIsMaskedOnceMoreAtMostAndANullTemplateIsAllowed`).

## Disagreements and limits

- **Finding 5, the argument budget.** Logback's appender renders the message before any encoder runs, so the formatter
  cannot bound that pass. Codex accepted this as a documented limit; it is the same gap as round 1's finding 3
  (HI-0157).
- **Two narrowings,** both accepted in verification 2:
  - Extra patterns must not match empty text or the mask.
  - Format objects are exempt only under a single format.

## Register

HI-0158 (AC12) and HI-0159 (live docs) were recorded at Gate E. Round 2's masking findings are
HI-0161..HI-0165. Finding 2 is HI-0151, and finding 5's limit is HI-0157.

Round 2 is **closed** at `1f69255`.
