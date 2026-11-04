# Phase 2 — Review round 1 (Gate C)

| Item | Value |
|---|---|
| Reviewed commit | `482ea5a` (diff from phase start `a33168d`, `docs/production-readiness` excluded; isolated worktree) |
| Verbatim I/O | [`codex/r1-review.prompt.md`](codex/r1-review.prompt.md) → [`codex/r1-review.out.md`](codex/r1-review.out.md); response [`codex/r1-response.md`](codex/r1-response.md); re-evaluation [`codex/r1-reassess.prompt.md`](codex/r1-reassess.prompt.md) → [`codex/r1-reassess.out.md`](codex/r1-reassess.out.md) |
| Codex scope note | Source and dependency-metadata review; Codex's sandbox could not run the JVM, Maven or Docker |

## Findings

| # | Finding | Severity | Claude | Re-evaluation | Disposition |
|---|---|---|---|---|---|
| F1 | Quoted secrets (`password="a b"`, `'x'`) and later cookie pairs only partly masked | High | Agree | accept | FIXED — quoted values (with escapes) masked whole, quotes kept; `Cookie:`/`Set-Cookie:` masked to end of line |
| F2 | Length floors let `Basic YTpi`, `Bearer abc` and a JWT with payload `e30` through | High | Partly agree (proposed a Bearer prose heuristic) | **reject** the heuristic (RFC 6750 allows letters-only tokens); Basic and JWT changes sound | FIXED — Claude conceded: everything after `Bearer` is masked (prose too); `Basic` masked when it decodes to `user:password` or is ≥ 16 characters; JWT header `e[wy]` + structural minimum, payload ≥ 2, signature optional |
| F3 | Non-string values under credential names unmasked; numeric PANs only for `Long`/`BigInteger` | High | Agree | maintain concern: `BigDecimal("…​.00")` | FIXED — credential name (field or any ancestor) wins for every type; `Integer`/`Long`/`BigInteger`/integral `BigDecimal` checked for PANs. The `….00` case was already covered by `stripTrailingZeros()`; now pinned by a test |
| F4 | PAN pattern cuts hex trace IDs; a following number hides a card | High | Agree | maintain concern: trailing hyphen; layouts narrower than the docs said | FIXED — word boundaries on numeric patterns (trailing hyphen is a boundary); contiguous 13–19 digits or the 4-4-4-4(+1..3) / 4-6-5 layouts with a consistent separator; docs now name the layouts |
| F5 | Sanitized structured value survives in the formatted `message` | High | Agree | accept | FIXED — `SanitizedMessageView` re-renders the message from sanitized `Kv`/`kv(...)` arguments (event-local view; no mutation) |
| F6 | E-mail regex quadratic on long input | High | Agree | accept | FIXED — token-start anchored and bounded local part/domain, `@` fast path; 200 000-character inputs under 750 ms (test) |
| F7 | OTLP switch exports no traces: starter lacks `opentelemetry-exporter-otlp` | High | Agree (pre-existing) | accept | FIXED — exporter added to `platform-starter-observability`; `OtlpExportSwitchTest` (exporter bean only with the switch on). Collector receipt: Gate F (AC11) |

### Concerns raised in the re-evaluation

| Concern | Disposition |
|---|---|
| A field merely named `traceId`/`spanId` would bypass masking (`kv("traceId", "password=…")`) | Agree — FIXED: the exemption applies only when the value is a 32- or 16-character lowercase hex ID; credential ancestors still win |
| Sanitizers would run twice after the message view | Disagree, with a test: the message rendering and the structured field each apply the sanitizers once to the **raw** value; `aNonIdempotentSanitizerRunsOncePerOutput` (appending sanitizer → `x!` in both, never `x!!`) |
| JWT headers do not always start `eyJ` (RFC 7515 permits whitespace) | Agree — FIXED: `e[wy]` (`{"` / `{ ` → `ey`, `{\n` → `ew`) with a 10-character header minimum (the smallest header `{"alg":"x"}` is 15); `ewogICJhbGciOiAibm9uZSIKfQ.e30.sig` masked, `eyes.of.the storm` not |

## Acceptance criteria after round 1

| AC | Status |
|---|---|
| AC1, AC6, AC15, AC16 | accepted (AC16: `OtlpExportSwitchTest.headSamplingStaysBootsDefault` pins `0.1`) |
| AC5 | slice gaps closed by F1–F4; consumer redaction proof at Gate F |
| AC8 | slice: single WARN asserted, unmatched paths never become uri values; 400 calls per scope amendment A2; consumer at Gate F |
| AC13 | `ObservabilityProperties` and logging EPP snapshots added; running-app kill switches: observability (`PlatformEndpointWebTest$KillSwitch`: Boot's exposure, no platform groups) and logging (`JsonLogOutputTest.killSwitchRestoresBootsOwnLogging`) |
| AC14 | slice: `theOnlyRootAppenderIsASynchronousConsoleAppenderWithThePlatformEncoder`; shutdown lines at Gate F |
| AC2, AC3, AC4, AC7, AC9, AC10, AC11, AC12 | Gate F (consumer): security fixture (401/404 matrix, token scrape), real DB stop/start and recovery, real collector receipt, collector down/slow budgets, hostile exception messages, every log line correlated to its request |

## Targeted tests after the fixes

logging-api 5, logging-autoconfigure 99, observability-autoconfigure 37 (with the starter) — 0 failures,
0 skipped, JaCoCo gates met (`evidence-raw/c3.log`, `evidence-raw/c4.log`).
