# Phase 1 — Review round 2 (Gate D) and verification pass

| Item | Value |
|---|---|
| Reviewed commit | `952d0a1` (fresh review from phase start `f231689`) |
| Verbatim I/O | [`codex/r2-review.prompt.md`](codex/r2-review.prompt.md) → [`codex/r2-review.out.md`](codex/r2-review.out.md); response [`codex/r2-response.md`](codex/r2-response.md); re-evaluation [`codex/r2-reassess.prompt.md`](codex/r2-reassess.prompt.md) → [`codex/r2-reassess.out.md`](codex/r2-reassess.out.md); verification pass [`codex/v1-verify.prompt.md`](codex/v1-verify.prompt.md) → [`codex/v1-verify.out.md`](codex/v1-verify.out.md) |
| Fix commits | `8f62fcf` (Round 2 fixes), `e2be16d` (verification follow-ups) |

## Round 1 dispositions confirmed by Codex

F1, F2, F3, F5–F10 **resolved**; F4 **partially resolved** in Round 2 → closed by F11.

## Round 2 findings

| # | Finding | Severity | Claude | Re-evaluation | Verification pass | Disposition |
|---|---|---|---|---|---|---|
| F11 | Boot's default decorator lets an empty snapshot inherit the caller's context | High | Agree | accept | **accept** | FIXED — explicit "no context" capture; test through the real decorator |
| F12 | Core capability descriptor not overridable | High | Agree | maintain concern (requirement) | **reject — full closure**: functional fix accepted; R4.3 reconciliation open | FIXED functionally; **requirement amendment A3 pending owner** |
| F13 | Native MVC method validation omits `errors[]` | Medium | Agree | accept | **accept** | FIXED |
| F14 | Framework 5xx not logged | Medium | Agree | accept | **accept (implementation)**; consumer assertion tightened in `e2be16d` | FIXED |
| F15 | Direct `/error` omits `instance` | Medium | Agree | accept | **accept** | FIXED |

## Acceptance criteria after the verification pass

| AC | Verification pass | After `e2be16d` |
|---|---|---|
| AC1–AC3, AC8, AC11, AC12, R3.1 | accept | — |
| AC9 | reject — race (no wait for the recorder) | Bounded wait for every dispatch's exit record; 3 consecutive passes |
| AC16 / R4.3 | reject — reconciliation open; registration not inspected in a started container | `RegistrationOverrideContainerTest` (started container); reconciliation = amendment **A3 pending owner** |
| AC17 | reject — final evidence unverified | Downstream compile on `e2be16d`: 86 modules exit 0 (`evidence-raw/downstream-compile-final.log`, sha256 `dc68a4de…6955`); golden path on `e2be16d`: 8/8, 815 s (`evidence/golden-path-summary-e2be16d.tsv`) |
| R4.1 / A1 | reject — owner acceptance pending (Low, non-blocking) | **A1 pending owner** |

## Unresolved disputes (verbatim positions)

- **R4.3 / F12 / AC16** — Codex: "D93/D96 explain that choice but do not explicitly reconcile R4.3. The
  functional fix is accepted; the requirement discrepancy remains." Claude: type-based back-off for
  shared Spring/platform types (`CapabilityDescriptor`, `ApplicationRunner`, `FilterRegistrationBean`)
  would silently drop the platform bean whenever a consumer defines an unrelated bean of that type;
  name-based override is the safe mechanism. → owner decision on amendment A3.
- **R4.1 / A1** — no successor property exists for the removed keys; Codex agrees a replacement would
  mislead but requires explicit owner acceptance. → owner decision on amendment A1.
