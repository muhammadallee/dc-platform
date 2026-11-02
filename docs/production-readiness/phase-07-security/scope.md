# Phase 7 — Security: scope

| Item | Value |
|---|---|
| Phase start commit | `c7373c6` (Phase 6 closed, residuals decided, D131) |
| Capability under test | `platform-security-api`, `-autoconfigure`, `platform-starter-security`; `platform-security-authz-*` |
| Also fixed here | HI-0099 (restclient, D131); the token relay default |
| Owner decisions | HD3: Oracle only. HD5: `traceparent` only. HD6: readiness pod-local. HC7: breaking changes need only a CHANGELOG entry |
| Certified dependencies | core, errors, logging, observability, data, restclient, resilience |
| Phase 0 gaps | G7-01 to G7-05 |
| Identity provider in the runs | Signed test JWTs and a scripted JWKS server for every attack and outage, since a real IdP will not sign a bad token. **Keycloak 26.4 in a container** for one real token, for the role mapping |

## 1. Baseline (`c7373c6`)

Targeted build of security and authz with `-am`: 117 tests, 0 failures, 0 skipped (`evidence-raw/baseline.log`).

Reproduced with a temporary test on a real server, a real JWKS endpoint and real signed tokens, using only `jwk-set-uri`, as the archetype does (`evidence-raw/baseline-probe.log`):

| Probe | Result | Meaning |
|---|---|---|
| Wrong issuer | **200** | G7-01. `jwk-set-uri` alone validates no issuer |
| No issuer | **200** | G7-01 |
| Wrong audience | **200** | G7-01 |
| No expiry | **200** | A token that never expires is accepted |
| 30 s expired, or 30 s before `nbf` | 200 | 60 s skew, not configurable. 90 s: 401 |
| `alg=none`, HS256 signed with the public key, bad signature | 401 | Holds |
| Unknown `kid`, 5 requests | **5 JWKS fetches** | No rate limit: anyone can make the service hammer the IdP |
| JWKS 500, known `kid` | 200 | Cached key used |
| JWKS 500, unknown `kid`; cold start, JWKS 500 | **500**, not a ProblemDetail | Fails closed, with the wrong status and shape |
| A second JWKS failure, within seconds | 500 at once | Hang not reached by the probe: the fetch timeout is still unknown, so it gets its own test |
| Consumer chain for `/admin/**` only: `/admin` | 403 | |
| Same app: `/secure`, anonymous | **200** | **Fails open.** Any consumer chain removes the platform's for all paths |
| `mode=disabled` or `enabled=false`, no chain | 401 | Boot's default chain stays. Closed |

By reading (each gets a test at Gate B):

| Defect | Where |
|---|---|
| `/actuator/info`, `/actuator/health`, `/v3/api-docs/**`, swagger open by default | `SecurityProperties`, G7-02 |
| `@RequiresPermission` is silently inert with no `CurrentUserAccessor` bean: `mode=disabled`, or no security starter. **Fails open** | `requiresPermissionAdvisor`, G7-03 |
| Keycloak roles (`realm_access.roles`, `resource_access.<client>.roles`) are not mapped at all; `hasRole` and `@RequiresPermission` cannot see them | default `JwtAuthenticationConverter` |
| The token is relayed to every REST client, including third parties, unless a consumer builds around it | `OAuth2TokenRelayCustomizer` |
| Bare token quoted into the error log on malformed JSON | HI-0099, D131 |
| JWKS fetch has no timeout we set | default `NimbusJwtDecoder` |
| Principal over `@Async`: Spring's accessor exists; no test | G7-04 |

## 2. Requirements

### R1 Token validation (G7-01)
1. Startup fails unless **both** `spring.security.oauth2.resourceserver.jwt.issuer-uri` and `.jwk-set-uri` and at least one of `.audiences` are set, naming the missing one. No discovery: nothing is contacted at startup. A consumer's own `JwtDecoder` bean replaces all of R1 and R2.
2. Signature, `iss`, `aud`, `exp`, `nbf` validated. `exp` and `sub` are required.
3. `.jws-algorithms` is the allow-list, default `RS256`. `none` and any HMAC algorithm fail startup.
4. `dc.platform.security.jwt.clock-skew`, default 30 s; above 5 min fails startup.
5. A JWKS URL that is not `https` fails startup, unless loopback or `dc.platform.security.jwt.allow-insecure-jwks=true`.
6. Every rejection is the same 401 body and bare `WWW-Authenticate: Bearer`.

### R2 JWKS (G7-05)
1. Fetch: 2 s connect, 2 s read, the sum as a limit on the whole fetch, 1 MiB limit, no redirects. A response with no keys or not JSON is a failed fetch. RSA keys under 2048 bits are ignored. Properties under `dc.platform.security.jwt.jwks.*`.
2. Cached 5 min, so a rotated-out key stops working within that; refreshed on an unknown `kid`, at most twice per 30 s, which is how Nimbus's limiter works.
3. JWKS down: cached keys keep working for up to 1 h beyond their TTL (`outage-tolerance`), then rejected.
4. Unknown `kid` and JWKS reachable, or rate limited: 401. Unknown `kid` and JWKS down, or no key ever fetched: **503 `DC-SEC-0503`, `retryable=true`**. Never reaches the application.
5. Rotation: a new `kid` is accepted without a restart.

### R3 Roles (Keycloak)
1. Authorities: `SCOPE_x` from `scope`/`scp` as Spring does; `ROLE_x` from `realm_access.roles`, `roles`, and `resource_access.<client>.roles`, only for `dc.platform.security.jwt.resource-client`, default `spring.application.name`. Another client's roles grant nothing.
2. `@RequiresPermission("x")` is granted by a role `x` from any of those. Malformed claims grant nothing and do not fail the request.
3. A consumer's `JwtAuthenticationConverter` bean backs the mapping off.

### R4 Fail closed (G7-03)
1. After startup, while the capability is enabled, the last `SecurityFilterChain` must match every request; otherwise startup fails, naming it. A consumer's full chain is accepted.
2. `@RequiresPermission` with `authz` enabled and no `CurrentUserAccessor` fails startup, with a `FailureAnalyzer` message.
3. Neither `permit-paths` nor a `SecurityCustomizer` can open every path: the built chain is asked about an unnamed path, and startup fails if an anonymous caller would get in.
4. Kill-switch tier: `DEFAULT_ON`. `enabled=false` or `mode=disabled` with no chain stays closed (Boot's own chain).

### R5 Exposure (G7-02)
1. Default `permit-paths`: only `/actuator/health/liveness` and `/actuator/health/readiness`. `info`, aggregate health, `dependencies`, `prometheus`, OpenAPI and swagger need a token.
2. Health details never shown to anonymous callers.
3. The same holds on a separate `management.server.port`.

### R6 Context (G7-04, §7.6)
1. The principal is readable through `CurrentUserAccessor` in `@Async` and platform-built executors, cleared after each request, and never bleeds between concurrent principals.
2. No principal, subject or claim in MDC or logs by the platform.

### R7 Tokens stay out (§7.8)
1. No token, valid or rejected, in logs, spans or response bodies, at the default log levels. Debug logging from Spring Security is out of scope, and stated as a limit.
2. Token relay becomes opt-in, per client: `dc.platform.restclient.token-relay.clients`, a list, default empty.
3. HI-0099: a parse failure on a success body keeps the exception type, and no text from the body. Position is dropped, since it would take a Jackson dependency here.

### R8 Observability (§7.9)
`platform.security.token.rejected` and `platform.security.jwks.fetch{outcome}` counters with no user-derived tags. A failed JWKS fetch logs one WARN with no URL query and no token.

## 3. §7 contracts

| § | Plan |
|---|---|
| 7.1 override | `SecurityFilterChain`, `JwtDecoder`, `JwtAuthenticationConverter`, `CurrentUserAccessor`, `PermissionEvaluatorProvider` |
| 7.2 snapshot | New `theDefaultsAreWhatTheDocumentationSays` for both property records |
| 7.3 kill switch | `DEFAULT_ON` |
| 7.4 outage | IdP slow, silent, refusing, failing: R2; the request's response recorded |
| 7.5 health | The IdP is not a health contributor, so readiness cannot depend on it |
| 7.6 propagation | R6 |
| 7.7 error surface | Only 401, 403, 503 bodies with exact generic text |
| 7.8 redaction | R7 |
| 7.9 observability | R8; Spring Security's own chain observations stay |
| 7.10 shutdown | In-flight authenticated requests answered |
| 7.11 startup | JWKS down at startup: starts, same time, 503 on token use |

## 4. Acceptance criteria (Gate F)

| AC | Scenario |
|---|---|
| AC1 | Valid token: 200, principal read back |
| AC2 | Expired; not yet valid; inside and outside the skew |
| AC3 | Wrong issuer, wrong audience, no `exp`, no `sub` |
| AC4 | Bad signature, `alg=none`, HS256 with the public key, unlisted RS512 |
| AC5 | Unknown `kid`: 401; 20 of them: at most 2 JWKS fetches |
| AC6 | JWKS down: known `kid` 200, fresh `kid` 503; recovery |
| AC7 | JWKS hung: bounded answer |
| AC8 | Rotation: new key accepted, old key rejected after the TTL |
| AC9 | No token; garbage token: same 401 as every rejection |
| AC10 | Permission: 401, 403, 200; `hasRole`; another client's role grants nothing; path rule |
| AC11 | Real Keycloak token: realm and client roles map; a 401 with the wrong audience |
| AC12 | Actuator matrix: anonymous and with a token, same port and separate port |
| AC13 | OpenAPI and swagger need a token |
| AC14 | Explicitly public endpoint, by `permit-paths` and by `SecurityCustomizer` |
| AC15 | Startup failures: missing issuer, audience, `alg=none`, HS256, excess skew, http JWKS, narrow chain, authz with no accessor |
| AC16 | Custom full chain replaces the platform's |
| AC17 | Principal over `@Async`; 2 principals, 200 concurrent requests, no bleed in context or MDC |
| AC18 | All tokens used in the run absent from logs, spans and bodies |
| AC19 | Relay off by default, on per client |
| AC20 | Malformed JSON with a bare token: absent from logs (HI-0099) |
| AC21 | SIGTERM with a request in flight: answered, exit 143 |
| AC22 | JWKS down at startup: ready in similar time; slow JWKS: same |

## 5. Composition checkpoint C2 (Gate G)

App with security, restclient, Oracle data, observability, and the scenarios of plan §9: authenticated request → DB write → outbound call on one trace, principal in context and not in logs; downstream 401, 403, 5xx mapped without its body or token; 401 not retried; breaker open while the DB is healthy; DB slow while the downstream is healthy; two principals concurrently, no bleed; shutdown with outbound calls in flight.

## 6. Exclusions

- TLS to the JWKS endpoint is checked by scheme and not run. mTLS, token introspection, opaque tokens, sessions, CSRF (no cookies), multi-issuer. CORS stays with the application.
- Keycloak HA, or Keycloak restarts. RAC remains `BLOCKED` (HD4).
- Log levels below the default.

## 7. Priorities

P0: R1, R2, R4, R5, R7.1. P1: R3, R6, R7.2, R7.3, R8.
