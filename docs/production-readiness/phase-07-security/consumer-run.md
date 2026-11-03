# Phase 7 — Consumer run (Gate F) and composition checkpoint C2 (Gate G)

| Item | Value |
|---|---|
| Code | `d56d0a0` (runs record `3509a93`, which adds docs only) |
| Scenario | `tooling/hardening/scenarios/phase-07-security.sh`; scripted identity provider `phase-07/Idp.java`, probe `phase-07/probe/SecurityProbe.java`, realm `phase-07/keycloak-realm.json`, bleed checks `phase-07/bleed.py` |
| Consumer | Archetype-generated, prod profile, public API only, with the authz and restclient starters. Security is this phase's subject |
| Infrastructure | The scripted provider on the host (loopback, RS256, real key sets); Keycloak in Docker for AC11 |
| Closing runs | **170 of 170, three times** (`security-20261103-130614`, `-132345`, `-134025`); [`evidence/gate-f-closing-1..3`](evidence/) |
| C2 | **61 of 61** (`c2-20261103-135711`), Oracle Free with security, restclient and observability; [`evidence/gate-g-c2`](evidence/gate-g-c2/) |
| Startup | Ready in 9 to 12 s; 9 s with the key server down |
| Stop | Exit 143 in 2 to 5 s, a request in flight answered |
| Cleanup | No containers left, checked by the harness |

## Acceptance criteria

| AC | Result | How |
|---|---|---|
| AC1 | pass | 200, the subject read back, nothing of it in the MDC |
| AC2 | pass | Expired and future 401; inside the skew 200, both `exp` and `nbf` |
| AC3 | pass | Wrong and missing issuer, wrong and missing audience, no `exp`, no `sub`: 401, one body, no reason in the challenge |
| AC4 | pass | Forged, `alg=none`, HS256 with the public key, RS512 unlisted: 401 |
| AC5 | pass | 21 unknown-`kid` requests in 6 s: 6 fetches, bound 8 (2 per 2 s cooldown in the run; 21 without a limit) |
| AC6 | pass | Down: cached key 200, fresh key 503 `retryable`; an HTML page and a redirect are outages too; recovery |
| AC7 | pass | Hung key server: 503 in 2 s |
| AC8 | pass | New key 200; removed key 401 after the cache time; back 200 |
| AC9 | pass | No token, garbage, a token in the query: the same 401 |
| AC10 | pass | 401, 403 with a generic body, 200 by realm, plain and client role; another client's role 403; scope and its absence |
| AC11 | pass | Real Keycloak token: subject, realm and client roles; another client's role grants nothing; wrong audience 401 |
| AC12 | pass | Same port and a management port: only liveness and readiness open, anonymous readiness without details; with a token 200 |
| AC13 | pass | `v3/api-docs`, swagger UI: 401 |
| AC14 | pass | Open by `permit-paths` and by a customizer; the rest 401; encoded traversal 400 |
| AC15 | pass | Ten setups refused in 9 to 10 s, each naming its setting. Authz with no accessor, one-method and abstaining wildcards, a throwing matcher: slices |
| AC16 | pass | Own full chain: its open path 200, its denied path 403 |
| AC17 | pass | `@Async` keeps the principal; 200 concurrent, two principals, no bleed in context or MDC; anonymous afterwards 401 |
| AC18 | pass | None of 30 tokens, nor anything JWT-shaped, nor a principal field, in a log; the Keycloak token too |
| AC19 | pass | An unnamed client carries no token, a named one does |
| AC20 | pass | Malformed downstream JSON quoting a token: 500, not in the body, in no log (HI-0099) |
| AC21 | pass | SIGTERM with a request in flight: answered, exit 143 |
| AC22 | pass | Key server down at startup: ready in 9 s, nothing fetched, 503 then 200 once back |
| R8 | pass | Rejections and failed fetches counted; no user-derived tag |

## C2

| Check | Result |
|---|---|
| Request → DB write → outbound call | One trace at the downstream and on the stored row; token relayed to the named client; principal on the row, not in a log |
| Downstream 401, 403, 500 | 500 `retryable: false`, no downstream body or token, sent once |
| Downstream 503 | 503 `retryable: true`, sent 3 times |
| Breaker open, DB healthy | Nothing sent; DB requests and readiness 200; `dependencies` shows db UP and the breaker open |
| DB slow, downstream healthy | An outbound call needs no database: 512 ms |
| Two principals, 200 chains | No wrong principal, no repeated trace, 100 rows each, each principal's own token downstream |
| SIGTERM with a call in flight | Finished, exit 143 |

## Trials before the closing runs

| Trial | Code | Result | Lesson |
|---|---|---|---|
| 1 | `5f0eea3` | 163 of 165 | Both my checks: the 403 body check matched `clerk` in the instance path; the AC5 bound assumed faster requests |
| 2 | `5f0eea3` | 165 of 165; C2 61 of 61 | Then Codex round 2 found two High ways round the open-path check |
| 3 | `abe70ec` | 170 of 170; C2 61 of 61 | Then verification pass 2 found a JDK-proxy permission bypass and an abstaining rule passing the check, neither of which a run of this app could show |

**A clean run was not the finish**, again: two clean trials preceded fixes for four High findings, all reached only by reading.

## Regressions on the same code

| Phase | Result | Note |
|---|---|---|
| 1 | 92 of 92 | |
| 2 | 76 of 76 | One more than Phase 2's own run: anonymous `info` is 401 |
| 3 | 92 of 92 | The first run failed AC6: the harness polled `dependencies` without a token. Fixed in the harness, rerun |
| 4 | 106 of 106 | |
| 5 | 63 of 63 | |
| 6 | 182 of 182 | |
| Golden path | 9 of 9 | |

## Limits

See [`verification`](../../samples/security/verification.md): no TLS, no spans, the 1 MiB cap not run, one Keycloak never restarted, no load.
