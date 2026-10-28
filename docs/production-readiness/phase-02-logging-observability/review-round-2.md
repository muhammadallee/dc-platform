# Phase 2 — Review round 2 (Gate D)

| Item | Value |
|---|---|
| Reviewed commit | `0ffc3e1` (diff from phase start `a33168d`, `docs/production-readiness` excluded; isolated worktree) |
| Verbatim I/O | [`codex/r2-review.prompt.md`](codex/r2-review.prompt.md) → [`codex/r2-review.out.md`](codex/r2-review.out.md); response [`codex/r2-response.md`](codex/r2-response.md); re-evaluation [`codex/r2-reassess.prompt.md`](codex/r2-reassess.prompt.md) → [`codex/r2-reassess.out.md`](codex/r2-reassess.out.md) |
| Verification passes | 1: [`codex/v2-verify.prompt.md`](codex/v2-verify.prompt.md) → [`codex/v2-verify.out.md`](codex/v2-verify.out.md) (`0ffc3e1..e1d2842`); 2: [`codex/v3-verify.prompt.md`](codex/v3-verify.prompt.md) → [`codex/v3-verify.out.md`](codex/v3-verify.out.md) (`e1d2842..f5ce14c`); 3: [`codex/v4-verify.prompt.md`](codex/v4-verify.prompt.md) → [`codex/v4-verify.out.md`](codex/v4-verify.out.md) (`f5ce14c..7a7c962`) |
| Codex scope note | Source and dependency-bytecode review; Codex's sandbox could not run the JVM, Maven or Docker |
| Reproduction | Every finding was reproduced red before fixing through the real `PlatformJsonEncoder` (`EncoderRedactionTest`): 16 tests, 13 failures + 3 errors on `0ffc3e1` (`evidence-raw/d1-red.log`); the `1E+100000000` case took 195 s |

## Round-1 dispositions

| Round-1 item | Codex round 2 | Re-evaluation | Closed by |
|---|---|---|---|
| F1 quoted secrets / cookies | PARTIAL (quoted cookie pairs) | accept | R2-F2 |
| F2 short credentials / JWT | PARTIAL (whitespace before `{`) | accept | R2-F7 |
| F3 non-string credential values | PARTIAL (boolean, binary bypass the value masker) | accept | R2-F3 |
| F4 PAN boundaries | CONFIRMED | — | — |
| F5 sanitized message | PARTIAL (other argument shapes, built-in masking) | accept | R2-F4, R2-F5 |
| F6 e-mail regex cost | CONFIRMED | — | — |
| F7 OTLP exporter | CONFIRMED | — | — |

## Findings

| # | Finding | Severity | Claude | Re-evaluation | Verification | Disposition |
|---|---|---|---|---|---|---|
| R2-F1 | Long quoted values overflow the stack (recursive alternation) | High | Agree | accept | pass 1 accept | FIXED `e3788c1` — unrolled possessive `QUOTED`; an unterminated quote runs to end of input |
| R2-F2 | Quoted cookie pairs bypass header masking | High | Agree | accept | pass 1 accept; regression (quoted-key and numeric cookie fields) fixed `f5ce14c` | FIXED — separate `COOKIE_HEADER` (to end of line) and `COOKIE_FIELD` (quoted key, `:` or `=`, quoted or bare value) |
| R2-F3 | Boolean / binary credentials skip the value masker | High | Agree | accept | pass 1 accept | FIXED `e3788c1` — `CredentialFieldMasker` replaces the whole field, containers included |
| R2-F4 | Structured credentials leak through `message` | High | Agree | accept | pass 1 **reject** (16-deep nesting skipped masking) | FIXED — `SanitizedMessageView` always runs, credential `Kv`/markers render `key=****`; depth cutoff now masks (`f5ce14c`) |
| R2-F5 | Sanitizers inconsistent across `entries`, nested values, arrays, numbers | High | Agree | accept (bean limitation documented) | pass 1 **reject** (same depth cutoff; null array elements) | FIXED — one `sanitizedTree` traversal; `Arrays.asList` keeps nulls (`f5ce14c`) |
| R2-F6 | Reserved names (`logger`, `level`, …) bypass masking | High | Agree | accept | pass 1 accept | FIXED `e3788c1` — exemptions removed; only top-level W3C hex `traceId`/`spanId` exempt |
| R2-F7 | JWT header with leading whitespace | High | Agree | accept | pass 1 accept | FIXED `e3788c1` — decode-based detection, JSON-whitespace trim, outer-brace check |
| R2-F8 | Unguarded `toString()` in the message view | Medium | Agree | accept + stderr concern | pass 1 **reject** (`Error` subclasses escape; second rendering reaches SLF4J `Reporter.error`) | FIXED `f5ce14c` — catch `Throwable` (rethrow `OutOfMemoryError`), render once, fixed `[unrenderable]` placeholder; stderr asserted |
| R2-F9 | Huge `BigDecimal` exponent expanded for PAN check | High | Agree | accept | pass 1 accept | FIXED `e3788c1` — digit count by `precision - scale` in `long`, `bitLength() <= 64` |
| R2-F10 | Capability gauge missing: config ordered before registries | Medium | Agree (pre-existing) | accept | pass 1 accept | FIXED `66467c1` — `afterName` metrics + composite registry; scrape asserts `platform_capability_active{` |

### Acceptance-criteria rows

| Row | Disposition |
|---|---|
| AC5 / R3, AC6 | Closed by R2-F1..F9; pass 1 rejected them on R2-F4/F5/F8 and the regressions, re-verified in pass 2 |
| R5.1 / AC7 | pass 1 accept (R2-F10) |
| R7.2 docs | Re-evaluation maintained the concern until `docs/llms-full.txt` was regenerated; FIXED `e1d2842` (book chapters 3, 6, 7, 9, 11, 13, security model, `llms-full.txt`, `properties.md`); pass 1 accept |

## Found by the Gate F trial (not by Codex)

| Issue | Disposition |
|---|---|
| `basic Basic <cred>` / `bearer Bearer <tok>`: the first scheme word consumed the match, the real credential stayed visible | FIXED `f5ce14c` — lookahead token match with a scheme-mask loop (HI-0044) |
| Compound credential names (`session-token`, `db_password`) unmasked in MDC and fields | FIXED `f5ce14c` — `CREDENTIAL_SUFFIXES` on the normalized name; D102 addendum (HI-0044) |
| Boot banner wrote non-JSON lines to stdout | FIXED `f5ce14c` — D108, `spring.main.banner-mode=off` in JSON mode, user setting wins (HI-0045) |
| CI: wall-clock budget 0.75 s exceeded on the shared runner (0.80 s, 1.08 s) | FIXED `8334836` — asserts linear scaling (4× input under 8× time) with a 5 s ceiling |

## Verification pass 2 (`f5ce14c`)

Accepted: R2-F4, R2-F5, R2-F8 (+ stderr concern), AC6, pass-1 regressions 1–4, banner off (D108).
Rejected: AC5, the scheme-overlap change and the compound-name change. Every rejection and
regression was reproduced red before fixing (`evidence-raw/d10-red.log`: 5 encoder failures, and the
cycle test hung until killed) and fixed in `6d3e4dd`; `docs/llms-full.txt` regenerated in `7a7c962`.

| Item | Finding | Disposition |
|---|---|---|
| Scheme overlap | `Bearer bearer` / `Bearer BEARER` left visible: the fix exempted a token equal to the scheme word | FIXED — the scheme word is skipped only when another scheme + token follows it |
| Compound names | `sessionCookie=abc123`, `{"sessionCookie":"sid=abc123"}` visible: text keys lacked the `cookie` suffix | FIXED — `cookie` added to the `KEY_VALUE` suffixes; exact `Cookie:` headers still mask to end of line |
| Regression 2 | Depth cutoff 16 masked ordinary nested values (R3.3) in `message` | FIXED — depth 64, identity cycle detection; fail closed beyond (D102 addendum) |
| Regression 3 | `StructuredArguments.toString` catches a throwing `toString` and reports it, unmasked, to the Logback status manager, outside the view's guard | FIXED — the view renders `SingleFieldAppendingMarker` and `ObjectFieldsAppendingMarker` (`fields(bean)`, found during the fix) values itself, mirroring that helper without its catch; status list asserted |

## Verification pass 3 (`7a7c962`)

Accepted: the scheme-overlap change, compound names, pass-2 regressions 1–3. Rejected: AC5. Reproduced
red (`evidence-raw/d11-red.log`, `d11b.log`), fixed in `85bf2ad`, `llms-full.txt` in `890367f`.

| Item | Finding | Disposition |
|---|---|---|
| Regression 1 | A marker nested in a value (`kv("payload", wrap(kv("entity", bad), 16))`) still rendered through its own `toString` → status-manager leak | FIXED — a nested `Kv`/marker is rendered by the view like a top-level one (same guard, depth, cycle path) |
| Regression 2 | Chained markers (`append(...).and(...)`) lost their references in `message` | FIXED — rendered as `LogstashMarker.toString`: own text, then each reference `, `-joined |
| Found while fixing | A `Kv` nested in a value was serialized as a record (`{"key":"token","value":"tk-1"}`), so its credential name escaped the field masker and the value reached the JSON | FIXED — `KvJsonSerializer` writes `{"<key>": value}`, registered after any configured `JsonFactoryDecorator` |

## Verification pass 4 (`b61cf2e`, checklist + implementation inventory)

Accepted: pass-3 regressions 1–2, nested `Kv` masking, `defer(...)`. Rejected: AC5 (`raw(...)` JSON
bypassing masking — pre-existing, normal API use) and `fields(List.of(marker))` (unusual composition);
three regressions (depth boundary off by one, primitive arrays by identity, `fields(Kv)` empty).
Reproduced red (`d13-red.log`), fixed in `91c2639` (HI-0048, HI-0049).

## Verification pass 5 (`edcd515`)

Accepted: `fields(...)` gap, pass-4 regressions 1–3. Rejected AC5: an escaped space in raw JSON hid
`Bearer` in `message`; raw parsing rounded numbers, dropped duplicate members and trailing text;
integral `double` card numbers unmasked (HI-0050); changed arrays inside lists by identity.
Reproduced red (`d14-red.log`), fixed in `6619ed7`.

## Verification pass 6 (`2ca797d`)

Accepted: pass-5 regressions 1–5. Rejected AC5: an escaped tab after `Bearer` re-escaped by
re-serialization; strict duplicate rejection sent escaped credential keys to the text-only fallback;
`double[]` card numbers in `message`; the numeric card check ran before `LogSanitizer` beans in
`message`. Reproduced red (`d15-red.log`), fixed in `4fcdba8` (streaming `RawJson` reader keeping
order, duplicates and exact numbers; message shows raw JSON decoded).

Before pass 7, Claude probed the remaining fallback itself (`d16-red.log`): unparseable raw text with an
escaped credential key (trailing comma; a 1 200-digit number) reached the text masks undecoded. D109:
such text is written as a JSON string, escapes decoded, under the pattern masks. A consumer's
`JsonGeneratorDecorator` is now chained, not replaced. The scaling test (flaky on CI at `2ca797d`,
0.54 s vs 0.06 s) takes the fastest of five runs per size, threshold 10×.

## Verification pass 7

Scope: the `2ca797d..HEAD` diff only. A finding reproducible with a plain `String` argument through
`{}` is the D102 text-mask baseline, not a structured-argument defect.

**Outcome rule (written before the result):**
- Only unusual-composition or baseline findings → Gate D closes; the residuals below go to the owner
  for acceptance.
- A normal-API leak in `message` rendering remains → no further patch-and-rerun. Passes 4–6 each
  found 3–5 items, mostly in code the previous pass added; that is a design question for the owner:
  (a) keep extending the message view type by type, or (b) fail closed — `message` renders scalars
  only and every container, raw value, bean or nested marker reads `key=****`, the JSON field being the
  readable source (changes the documented message contract, AC6).

**Residuals — ACCEPTED by the owner 2026-10-28** (not to be re-raised): depth > 64 / cycles read `****` in
`message`; bean `toString` prose in `message` gets text masks only; top-level W3C `traceId`/`spanId`
exemption; unparseable raw text gets text masks only (D109); console format unmasked (D107); custom
`LogstashMarker` subclasses and custom serializers are not certified.

**Result** (`codex/v8-verify.out.md`, checkout `739a08d`; source-established, no tests executed by
Codex): all four pass-6 items **accepted**: escaped tab after `Bearer` in raw; duplicates keep
credential masking; `double[]` PAN in `message`; sanitizers run before the card check. One
**regression, unusual composition**: `start()` wrapped the platform's own wrapper from an earlier
`start()`, so after `stop(); start();` a `LogSanitizer` ran twice (`ref-ref-7`). The regression came
from the `5f088f4` decorator chaining. Fixed in `81d0aa0`: the encoder remembers the decorators it
installed and re-wraps the consumer's originals on each start. Test
`restartingTheEncoderKeepsOneMaskingLayerAndTheConsumerDecorator` failed before the fix
(`d17-red.log`); logging verify 150 green (`d17.log`).

**Outcome:** the rule's first branch applies: no normal-API leak remains. Gate D closes. The
residuals above go to the owner for acceptance, at Gate H at the latest.

Evidence scan (plan §4.7) of this file and `codex/*`: the only token-shaped strings are the synthetic
JWT fixture already in committed test source and `hunter*`/`correct horse…` test values.

## Targeted tests after the fixes

logging-autoconfigure 150 (after pass 7, `evidence-raw/d17.log`; was 135 in `d10.log`), observability-autoconfigure 37 — 0 failures, 0 skipped, JaCoCo gates met
(`evidence-raw/d10.log`, `evidence-raw/d4.log`). CI green on `0a54804`.
