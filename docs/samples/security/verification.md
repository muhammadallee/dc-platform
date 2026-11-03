# Verification

On `3509a93`: three consumer runs at 170 of 170, against a scripted identity provider that signs real RS256 tokens and serves real key sets, and a real Keycloak. C2 at 61 of 61 on Oracle.

| Expected | Actual | Level |
|---|---|---|
| A valid token: 200, the subject read back, nothing of it in the MDC | Yes | CONSUMER |
| Expired, not yet valid: 401. Inside the 30 s skew: 200 | Yes | CONSUMER |
| Wrong or missing issuer, wrong or missing audience, no `exp`, no `sub`: 401 | Yes | CONSUMER |
| Forged signature, `alg=none`, HS256 signed with the public key, an unlisted RS512: 401 | Yes | CONSUMER |
| Every rejection: the same body, and a challenge with no reason | Yes | CONSUMER |
| 21 unknown-`kid` requests in 6 s: at most 8 key fetches | 6 fetches | CONSUMER |
| Key server down: a cached key 200, an uncached one 503 `retryable`; an HTML page or a redirect from it is an outage too | Yes | CONSUMER |
| Key server hung: answered within the fetch budget | 503 in 2 s | CONSUMER |
| Rotation: the new key accepted, a removed key rejected after the cache time | Yes | CONSUMER |
| Key server down at startup: starts as fast, nothing fetched, 503 until it is back | Ready in 9 s, as with it up | CONSUMER |
| `@RequiresPermission`: 401, 403, 200; realm, client and plain roles; another client's role refused; a scope is not a permission | Yes | CONSUMER |
| A real Keycloak token: realm and client roles map; the wrong audience is 401 | Yes | CONSUMER |
| Actuator, same port and a separate one: only liveness and readiness open; anonymous readiness shows no details | Yes | CONSUMER |
| OpenAPI and swagger: 401 without a token | Yes | CONSUMER |
| A path opened by `permit-paths` and by a customizer: open; the rest closed; an encoded traversal refused | Yes | CONSUMER |
| Settings that must not start: no issuer, no audience, no key URL, `alg=none`, HS256, a 10 min skew, an http key URL, a narrow chain of your own, a rule opening everything | Each refused in 9 to 10 s, naming the setting | CONSUMER |
| A rule opening everything for one method, one that abstains, a matcher that cannot answer | Refused at startup | SLICE |
| Authz with no `CurrentUserAccessor` | Refused at startup | SLICE |
| A permission through an interface proxy, on an inherited method | Enforced | SLICE |
| Your own full chain replaces the platform's | Yes | CONSUMER |
| The principal on an `@Async` worker; 200 concurrent requests for two users, no bleed in context or logs | Yes | CONSUMER |
| Token relay: none by default, the named client only | Yes | CONSUMER |
| A malformed downstream body quoting a token: not in the body, not in a log (HI-0099) | Yes | CONSUMER |
| SIGTERM with a request in flight: answered, exit 143 | Yes, in 2 to 5 s | CONSUMER |
| None of the 30 tokens used, nor anything JWT-shaped, in a log | Yes | CONSUMER |
| Rejections and failed fetches counted, with no user-derived tag | Yes | CONSUMER |
| C2: one trace from the request through the database write to the outbound call; the principal not logged; a downstream 401, 403 or 5xx mapped without its body or token; a 401 not retried; two users at once, no bleed | Yes | CONSUMER |
| A fresh service built on this | Golden path 9 of 9 | CONSUMER |

Regressions on the same code: Phase 1 92 of 92, Phase 2 76 of 76, Phase 3 92 of 92, Phase 4 106 of 106, Phase 5 63 of 63, Phase 6 182 of 182. 63 modules compile against it.

## Limits

- **No TLS in the runs.** The key URL must be https or loopback, which is checked by its scheme. The scripted provider is on loopback.
- **Spans are not asserted:** no exporter in the run.
- **The 1 MiB cap on a key set** is set and not run (HI-0103).
- **One Keycloak, never restarted.** No load test.
- The open-path check is a heuristic: a rule opening a large named area, such as `/api/**`, is not seen.
