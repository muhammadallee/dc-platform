# Phase 1 — Review round 1 (Gate C)

| Item | Value |
|---|---|
| Reviewed commit | `70a04ce` (phase start `f231689`) |
| Reviewer | Codex `codex-cli 0.164.0`, model `gpt-6-astra`, read-only, isolated worktree without `docs/production-readiness` |
| Verbatim I/O | [`codex/r1-review.prompt.md`](codex/r1-review.prompt.md) → [`codex/r1-review.out.md`](codex/r1-review.out.md); response [`codex/r1-response.md`](codex/r1-response.md); reassessment [`codex/r1-reassess.prompt.md`](codex/r1-reassess.prompt.md) → [`codex/r1-reassess.out.md`](codex/r1-reassess.out.md) |
| Fix commit | see `git log` after `70a04ce` (`fix(...)` commits tagged `[hardening phase-01 gate C]`) |

## Findings and dispositions

| # | Codex | Severity | Claude | Codex reassessment | Disposition | Fix / test |
|---|---|---|---|---|---|---|
| F1 | Rejected bearer tokens bypass the platform writer | High | Agree (verified) | accept | **FIXED** | Entry point + denied handler set on `oauth2ResourceServer`; `SecurityProblemBodyTest` (malformed/expired/untrusted → full 401 body; proven to fail with 3/5 without the fix); consumer: malformed/expired/untrusted against a real JWKS |
| F2 | Customizer gets null `source` for framework errors | High | Agree (verified) | accept | **FIXED** | Decoration moved to `handleExceptionInternal` with the exception; error page passes the escaping exception or a `ServletErrorDispatch`; `ErrorContractIntegrationTest` customizer dereferences `source` |
| F3 | Registration override leaves a second filter | High | Agree | accept | **FIXED** | Filter bean also backs off on `platformCorrelationFilterRegistration`; matrix test |
| F4 | Empty snapshot keeps the caller's context | High | Agree | accept | **FIXED** | `@PlatformInternal RequestContext.replace` (isolating scope); accessor uses it; tests on already-contextual threads (reads, MDC, exact restore) |
| F5 | ASYNC redispatch loses `traceId` | Medium | Agree | accept | **FIXED** | Filter runs on ASYNC + ERROR dispatches (registration dispatcher types); consumer `async-ok` / `async-fail` keep the trace |
| F6 | Minimal service loses correlation | Medium | Agree | accept | **FIXED** | `platform-starter-core` brings the OTel bridge + Boot tracing (no exporter); `example-minimal` `TraceCorrelationTest` (no observability starter) |
| F7 | Security body lacks `type`/`instance` | Medium | Agree | accept | **FIXED** | Writer receives the request; `type` from `dc.platform.errors.type-base-uri`; full-field assertions on 401 and 403 (slice + consumer) |
| F8 | Diagnostics: zero ids not marked; hex-only input logged raw | Medium | Agree | maintain concern → addressed | **FIXED** | Rejection = the propagator did not continue the named trace (D95) — no second rule set; marker carries length only; cases: hex-only, zero trace id, zero parent id, version `ff`, upper case, hostile control chars |
| F9 | Servlet error dispatch outside the contract | Medium | Partly agree | accept | **FIXED** | `PlatformErrorController` (D94); `ErrorContractIntegrationTest` (sendError JSON/HTML/none, filter-chain crash); consumer `/probe/duplicate` with JSON and HTML `Accept` |
| F10 | Uniqueness gate is format-sensitive | Medium | Partly agree | maintain concern → addressed | **FIXED** | JDK parser (`com.sun.source`) over every platform module's sources; non-literal construction fails closed; fixtures: multi-line, qualified, comment, string, unicode-escape duplicate, constant argument |

## Found by the consumer run during the fixes (not in Codex's list)

| ID | Severity | Finding | Disposition |
|---|---|---|---|
| HI-0011 | High | The security chain re-authorized the servlet ERROR dispatch: an anonymous request whose filter answered `sendError(409)` (or a filter-chain 500) came back as **401** | **FIXED** — ERROR dispatch permitted (D94); `ErrorDispatchSecurityTest` (real server) |
| HI-0012 | High | A method-security `AccessDeniedException` thrown inside a controller was caught by the errors catch-all and returned as **500** | **FIXED** — security exceptions rethrown to Spring Security; consumer `/secured/admin` → 403; `securityDenialsPassThroughToSpringSecurityInsteadOfBecoming500` |

## Acceptance-criteria notes

| AC | Codex | Disposition |
|---|---|---|
| AC1–AC2 | accept | Consumer asserts the fresh trace in a ProblemDetail and that the error log line carries the same trace |
| AC3 | maintain concern | Addressed by D95; application-reachable malformed values exercised in the consumer (malformed, oversized). CR/LF/control characters are rejected by Tomcat's HTTP parser before the application (no request reaches the filter), so they are covered at unit level only — recorded as the container boundary, not counted as an application pass |
| AC8 | maintain concern | Slice: 200 concurrent error bodies × 3 rounds (`twoHundredConcurrentErrorsEachCarryTheirOwnTraceId`); consumer: 600 concurrent context calls and 600 concurrent error bodies per run, 0 bleed, 3 consecutive runs |
| AC9 | accept | F4 tests |
| AC12 | accept | Consumer: unknown exception and 5xx `PlatformException`, both generic bodies, both details in the log on the request's trace |
| AC13 | maintain concern → addressed | Consumer 403 with a real JWKS-validated token lacking authority |
| AC15 | maintain concern | Metadata + no-effect tests added. **Scope amendment A1** (no `replacement` where no successor exists) — **pending the owner's acceptance** (see `scope.md` §9) |
| AC16 | reject → addressed | Type-based AND name-based override for both advices (D93), real override tests incl. the validation handler, descriptor guard by name (shared type), error page override |
| AC17 | maintain concern | Downstream `-amd test-compile` re-run after the fixes: exit 0, 86 modules (`evidence-raw/downstream-compile-r1.log`, sha256 `997e0eff…c900`); golden path re-run before Gate D closes |
| AC18 | accept | Exit 143 required; bounded wait for the handler-entry log line before SIGTERM |
