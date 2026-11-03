# Phase 7 — Security: closure

**Verdict: PASS, pending the owner's decision on four Low residuals, HI-0100 to HI-0103 (below).** Earlier owner decisions: D131 (HI-0099 to fix here), HC7 (no users, so breaking changes need only a CHANGELOG entry).

| Item | Value |
|---|---|
| Phase start / final code commit | `c7373c6` / `d56d0a0` (consumer 3×, C2, Phases 1–6 regressions, golden path and downstream compile on `3509a93`, which adds docs only) |
| Modules changed | security-autoconfigure, security-authz-autoconfigure, restclient-autoconfigure, test-api (`TestJwtIssuer`), archetype, example-golden-path, hardening harness, docs |

## Gates

| Gate | Result | Evidence |
|---|---|---|
| A scope + baseline | wrong issuer, wrong audience, missing issuer, missing `exp` all accepted; 60 s skew; unlimited unknown-`kid` fetches; a key-server outage a bare 500; a narrow consumer chain left other paths open; authz inert without an accessor | [`scope.md`](scope.md) |
| B implement + targeted tests | strict validation, bounded JWKS, outage 503, fail-closed checks, role mapping, opt-in relay, HI-0099 | `evidence-raw/` |
| C round 1 | REJECT, 20: 16 fixed, 3 corrected in scope, 1 residual (HI-0100) | [`review-round-1.md`](review-round-1.md) |
| D round 2 and verification | REJECT, 12 and 4 test gaps; pass 1 REJECT, 8; pass 2 REJECT, 2 High; **pass 3 ACCEPT** on the final code | [`review-round-2.md`](review-round-2.md) |
| E register | G7-01 to G7-05 closed; HI-0099 FIXED; HI-0100 to HI-0103 OPEN, Low | [`../issue-register.md`](../issue-register.md) |
| F consumer | three closing runs **170/170** each | [`consumer-run.md`](consumer-run.md), [`evidence/gate-f-closing-1..3`](evidence/) |
| G composition C2 | **61/61** on Oracle | [`consumer-run.md`](consumer-run.md), [`evidence/gate-g-c2`](evidence/gate-g-c2/) |
| F regressions (§3.10) | Phase 1 **92/92**, 2 **76/76**, 3 **92/92**, 4 **106/106**, 5 **63/63**, 6 **182/182**: every consumer, since every one now needs a token | [`evidence/`](evidence/) |
| Flakiness (§3.11) | security, authz and restclient suites, 3 consecutive runs, no reruns | `evidence-raw/flaky-1..3.log` |
| Targeted tests | security-autoconfigure 80, authz-autoconfigure 23, restclient-autoconfigure 108; 0 failures, coverage gates met | `evidence-raw/flaky-3.log` |
| Downstream (§3.9) | `-am -amd test-compile`: 63 modules, BUILD SUCCESS | `evidence-raw/downstream-final.log` |
| Golden path | **9/9** | [`evidence/golden-path-summary-3509a93.tsv`](evidence/golden-path-summary-3509a93.tsv) |
| H samples + operations | written | [`../../samples/security/`](../../samples/security/) |
| CI (PR #2) | green on the closure commit; tagged `hardening/phase-07-closed` | GitHub Actions |

Gaps: G7-01 issuer, audience, algorithms and skew (R1); G7-02 exposure (R5); G7-03 fail closed (R4); G7-04 propagation (R6, AC17); G7-05 JWKS outage, unknown `kid`, rotation (R2, AC5 to AC8, AC22).

## §7 contracts

| § | Result |
|---|---|
| 7.1 override | `JwtDecoder`, `JwtAuthenticationConverter`, `CurrentUserAccessor`, `PermissionEvaluatorProvider` and a full chain replace the platform's, tested. The advisor deliberately does not back off: HI-0101 |
| 7.2 default snapshot | properties table in `security.md`, defaults in `SecurityProperties` |
| 7.3 kill switch | `DEFAULT_ON`; `enabled`, `mode=disabled`, `authz.enabled` tested |
| 7.4 outage posture | down, hung, HTML, redirect, empty set, down at startup: the response recorded in [`operations`](../../samples/security/operations.md) |
| 7.5 health | the key server is in no health group |
| 7.6 propagation | `@Async`, 200 concurrent, two principals, no bleed (AC17, C2) |
| 7.7 error surface | only 401, 403, 503 bodies, exact generic text, no reason in the challenge |
| 7.8 redaction | no token, principal or JWT-shaped string in any log, consumer and C2 (AC18, AC20) |
| 7.9 observability | `platform_security_token_rejected_total`, `platform_security_jwks_fetch_total{outcome}`, no user-derived tag; Spring Security's own observations stay; spans not asserted |
| 7.10 shutdown | in flight answered, exit 143 |
| 7.11 startup | key server down at startup: same time, 503 on token use |

## Consumer-visible changes

See CHANGELOG `## [Unreleased]`, phase 7. Every consumer needs `issuer-uri`, `jwk-set-uri` and `audiences`, and a token for `info`, `prometheus` and `dependencies`.

## For the owner: four Low residuals

Codex agreed in pass 2 and pass 3 that each is Low.

1. **HI-0100:** a numeric `sub`, or an `nbf` so large it overflows to the past, is accepted. Nimbus parses claims before the platform sees them. Needs a token signed by your own provider, which could simply issue a valid one.
2. **HI-0101:** the `@RequiresPermission` advisor cannot be replaced by a bean of its name; startup fails instead. Plan §7.1 asks for replacement. Claude disagrees with changing it: that back-off is what let an unrelated bean switch enforcement off in round 1. The decision is replaceable through `PermissionEvaluatorProvider`.
3. **HI-0102:** if an earlier filter chose UTF-16 and took the writer without committing, a 401, 403 or 503 body is UTF-16. Needs an unusual filter; the request is still refused.
4. **HI-0103:** test gaps, no defect shown: an auto-configuration placed between ours and Boot's, several providers through enforcement, the 1 MiB key-set cap, spans.

Claude's recommendation: accept all four.

## Open (non-blocking)

- Not exercised: TLS to the key server (checked by scheme), Keycloak HA or restarts, load. Excluded by the plan: mTLS, opaque tokens, introspection, sessions, CSRF, several issuers.
- Parked by the owner: HI-0071, HI-0079, HI-0086. To review at the end of all phases: HI-0088 (D125).
