# Security

Secure-by-default stateless JWT resource-server security: every request is authenticated unless
explicitly permitted, 401/403 responses are RFC-9457 problem bodies, and `CurrentUserAccessor`
exposes the authenticated principal without coupling application code to JWT/claims.

## What you get

- **Baseline `SecurityFilterChain`** — stateless (no session), only the liveness and readiness probes open
  by default, everything else authenticated, including `info`, `prometheus`, OpenAPI and swagger. Security
  headers on, method security enabled (`@EnableMethodSecurity`).
- **Strict token validation** — signature, `iss`, `aud`, `exp` and `nbf` checked, `exp` and `sub` required, RS256 unless
  `jws-algorithms` says otherwise. **The application does not start** without `issuer-uri`, `jwk-set-uri` and `audiences`.
- **Signing keys** — fetched on first use with 2 s timeouts, cached 5 min, refreshed for an unknown `kid` at most twice per
  30 s. During an outage cached keys work for 1 h beyond the cache time, and for up to one more cache time (5 min) after that. A token that cannot be checked gets a 503 `DC-SEC-0503`, `retryable`.
- **Roles** — `ROLE_x` from `roles`, Keycloak's `realm_access.roles`, and `resource_access.<client>.roles` of this service's
  own client only; `SCOPE_x` from `scope`.
- **Fails closed** — startup fails if a request could match no filter chain.
- **RFC-9457 401/403 bodies** — the security filter chain runs before the `DispatcherServlet`, so
  the errors capability's `@RestControllerAdvice` cannot shape these; a small internal
  entry-point/denied-handler pair writes the problem body directly, with the full platform contract
  (`type` = `dc.platform.errors.type-base-uri` + code, `title`, `status`, `detail`, `instance`,
  `code` `DC-SEC-0401`/`DC-SEC-0403`, `traceId`, `timestamp`, `retryable`). The same body is used
  for missing credentials **and** for rejected bearer tokens (malformed, expired, untrusted key):
  the resource server's own entry point is replaced too. The challenge is `WWW-Authenticate: Bearer`
  without an error description, so the reason a token was rejected is never disclosed.
- **Error page not re-authorized** — the servlet ERROR dispatch is permitted, so a filter's
  `sendError(409)` or an exception escaping the filter chain keeps its real status for anonymous
  callers instead of turning into a 401.
- **`CurrentUser` / `CurrentUserAccessor`** — token-format-neutral principal (subject, tenant,
  roles, claims), independent of the underlying JWT shape.
- **`SecurityCustomizer` extension point** — ordered beans that extend the baseline chain (e.g.
  permit an extra path); they run before the platform's own catch-all, so they can open paths but
  cannot override authenticated-by-default.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-security</artifactId>
</dependency>
```

Also set the standard Spring Security resource-server properties for your IdP:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://issuer.example/realms/main
          jwk-set-uri: https://issuer.example/realms/main/protocol/openid-connect/certs
          audiences: orders-service
```

## Zero-config behavior

Anonymous requests to anything not in `permit-paths` get:

```json
{"type":"urn:dc:problem:DC-SEC-0401","title":"Unauthorized","status":401,
 "detail":"Full authentication is required to access this resource","instance":"/orders",
 "code":"DC-SEC-0401","traceId":"0af7651916cd43dd8448eb211c80319c",
 "timestamp":"2026-01-01T00:00:00Z","retryable":false}
```

A valid bearer JWT authenticates the request; `CurrentUserAccessor.currentUser()` returns the
principal derived from it.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.security.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.security.mode` | `resource-server` | `resource-server` builds the baseline chain; `disabled` leaves security to the application. Explicit only — never implied by a profile. |
| `dc.platform.security.permit-paths` | `/actuator/health/liveness`, `/actuator/health/readiness` | Request patterns open without authentication. |
| `dc.platform.security.allow-partial-chains` | `false` | Accept consumer chains that leave requests unmatched. Those requests get **no security**. Also needed if your final chain has a custom matcher that does cover everything. |
| `dc.platform.security.jwt.clock-skew` | `30s` | Tolerance on `exp` and `nbf`. At most 5 min. |
| `dc.platform.security.jwt.allow-insecure-jwks` | `false` | Accept a JWKS URL that is neither https nor loopback. |
| `dc.platform.security.jwt.resource-client` | `spring.application.name` | The `resource_access` entry roles are read from. |
| `dc.platform.security.jwt.jwks.connect-timeout`, `.read-timeout` | `2s` | One fetch of the keys. |
| `dc.platform.security.jwt.jwks.cache-ttl` | `5m` | How long a key removed at the provider keeps working. |
| `dc.platform.security.jwt.jwks.refresh-cooldown` | `30s` | The limiter's interval: at most 2 fetches in each. |
| `dc.platform.security.jwt.jwks.outage-tolerance` | `1h` | How long past `cache-ttl` cached keys work while fetches fail; up to one more `cache-ttl` on top. |
| `dc.platform.security.skip-open-path-check` | `false` | Skip the startup check for paths open to anonymous callers, with a warning. Only for a custom matcher that cannot answer it. |

(Hand-written until the generated reference lands in phase 14.)

## Customize

```java
@Bean
@Order(10)
SecurityCustomizer publicOrdersEndpoint() {
    return http -> http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/orders/public/**").permitAll());
}
```

Customizers run in `@Order` before the platform's own `anyRequest().authenticated()` is
registered — Spring Security forbids adding matchers after that call, so this is the only order
that lets customizers add exceptions safely.

## Replace / Disable

- Define your own `SecurityFilterChain` bean to replace the baseline entirely. It replaces it for **all** paths, so your last
  chain must have no `securityMatcher`, or startup fails. With no resource-server keys set, no decoder is created.
- The `@RequiresPermission` advisor cannot be replaced by a bean of the same name: that fails startup, so nothing can switch
  enforcement off by accident. Replace the decision with a `PermissionEvaluatorProvider`.
- `permit-paths` and `SecurityCustomizer` rules are checked at startup: an unnamed path open to anonymous callers with any method, or a
  matcher that cannot answer the check, fails startup; `skip-open-path-check` turns the check off. The check cannot see a rule that opens a large but named area, such as `/api/**`.
- Define your own `JwtDecoder` to replace all of the token validation, and your own `JwtAuthenticationConverter` to replace the
  role mapping. Boot's `authorities-claim-*` properties are not used unless you do.
- Define your own `CurrentUserAccessor` bean to replace the JWT-claims-based default.
- `dc.platform.security.mode=disabled` leaves security to the application (explicit only). With no chain of yours, Boot's
  own default stays and requests get a 401.
- `dc.platform.security.enabled=false` switches the capability off wholesale, with the same result. Tier: `DEFAULT_ON`.

## Testing

Use `spring-security-test`'s `jwt()` request post-processor with MockMvc — no live IdP needed:

```java
mockMvc.perform(get("/orders").with(jwt().jwt(b -> b.subject("alice")))).andExpect(status().isOk());
```

For manual runs against a real IdP, point `issuer-uri` at a local dev issuer (e.g. a Docker
Keycloak/mock-oidc container via `docker-compose.local.yml`); not required for the default test
suite.

## Local dev notes

No Docker, no network required for the default test suite (JWT decoding is mocked in tests).
Debug logging on `org.springframework.security` is not covered by the platform's guarantee that tokens stay out of logs.
Quick smoke check: `curl -i localhost:8080/whatever` — a `401` with an
`application/problem+json` body proves the chain is active.
