# Operations

## Posture

| Key server (JWKS) state | What a request gets | Verified |
|---|---|---|
| Up | Keys fetched on first use, cached 5 min | CONSUMER |
| Down, the token's key cached | 200, for 1 h beyond the cache time, and at most one more cache time after that | CONSUMER; the bound by test |
| Down, the key not cached | 503 `DC-SEC-0503`, `retryable: true` | CONSUMER |
| Hung | 503 within the fetch budget, about 2 s | CONSUMER |
| Answers an HTML page, an empty set or a redirect | Treated as down | CONSUMER |
| Down at startup | The service starts as fast; 503 until it is back | CONSUMER |
| Flooded with unknown `kid`s | At most 2 fetches each 30 s; the requests get 401 | CONSUMER |

The identity provider is never contacted to check a token: only its keys are fetched.

## Health

| Group | Includes the key server? | Why |
|---|---|---|
| liveness, readiness | No | It would take every replica out at once |
| dependencies | No | Watch the fetch metric instead |

Anonymous calls get liveness and readiness without details. Everything else under `/actuator` needs a token, on a separate management port too.

## Metrics, and alerts to set

| Metric | Alert when |
|---|---|
| `platform_security_jwks_fetch_total{outcome="failure"}` | Rising for more than a few minutes: cached keys run out after the outage tolerance |
| `platform_security_token_rejected_total` | Well above normal: a misconfigured caller, or someone probing |
| `http_server_requests_seconds_count{status="503"}` | Any with `DC-SEC-0503` in the logs: tokens cannot be checked |

A WARN says when keys cannot be fetched, naming the host and port, never the path, query or credentials. No log carries a token or the principal.

## Switches

Tier `DEFAULT_ON`. `dc.platform.security.mode=disabled` or `dc.platform.security.enabled=false` leaves security to the application; with no chain of its own, Boot's default refuses every request. `dc.platform.authz.enabled=false` makes `@RequiresPermission` do nothing. Escape hatches: `allow-partial-chains` and `skip-open-path-check`, each with a WARN at startup; `jwt.allow-insecure-jwks`, silent.

## Rotating keys

Publish the new key first and sign with it after one cache time (5 min). Remove the old key once its tokens have expired: a removed key keeps working for up to one cache time.

## Shutdown

Requests in flight finish during the web server's drain; nothing of security's needs stopping.

## How to replace it

Your own `SecurityFilterChain` replaces the platform's for every path, and must have no `securityMatcher` on its last chain. Your own `JwtDecoder` replaces all of the validation; your own `JwtAuthenticationConverter` the role mapping; your own `PermissionEvaluatorProvider` the permission decision.

## Cost

One key fetch per cache time, plus at most 2 per 30 s for unknown `kid`s. Validation is local: a signature check per request.
