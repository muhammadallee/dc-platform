# Troubleshooting

**observed** means seen during this phase; **anticipated** means reasoned, not seen.

| What you see | Why | What to do | |
|---|---|---|---|
| Startup fails naming `issuer-uri`, `audiences` or `jwk-set-uri` | The setting is missing | Supply it from the environment | **observed** |
| Startup fails naming `https` | The key URL is plain http and not loopback | Use https; for a test provider only, `jwt.allow-insecure-jwks=true` | **observed** |
| Startup fails naming `jws-algorithms` or `clock-skew` | `none`, an HMAC algorithm, or a skew over 5 min | Use RS256 or another asymmetric one; a skew of seconds | **observed** |
| Startup fails: `opens every path` | A permit path or customizer rule matches everything, for at least one method | Permit named paths. For a service meant to be open, define your own chain | **observed** |
| Startup fails: `does not match every request` | Your own chain has a `securityMatcher`, so other paths would have no security | Add a final chain with no matcher | **observed** |
| Startup fails: `failed on a check request` | A custom matcher in a customizer threw on the startup check | Use path or method matchers; or check the chain yourself and set `skip-open-path-check=true` | **observed** in tests |
| Startup fails: no `CurrentUserAccessor` | Authz without security | Add `platform-starter-security`, or your own accessor | **observed** in tests |
| A token that looks right gets 401 | Wrong `aud` or `iss`, expired, or signed by a key the provider no longer publishes. The 401 never says which | Decode the token and compare `iss` and `aud` with the settings; check the clock | **observed** |
| 503 `DC-SEC-0503` | The key server is unreachable and the token's key is not cached | Check the WARN and `platform_security_jwks_fetch_total{outcome="failure"}`; retry | **observed** |
| A Keycloak client role gives 403 | It is a role of another client | Set `dc.platform.security.jwt.resource-client` if this service's client is not named after `spring.application.name` | **observed** |
| `/actuator/info` or `/actuator/prometheus` is 401 | Only liveness and readiness are open | Scrape with a token, or add the path to `permit-paths` knowingly | **observed** |
| Just after a rotation, a few 401s for the new key | The unknown-`kid` refresh was spent within the last 30 s | Publish new keys a cache time before signing with them | **anticipated** |
| A rule that opens `/api/**` to everyone starts fine | The open-path check sees only rules that open unnamed paths | Review permit rules; the check is a backstop | **anticipated** |
