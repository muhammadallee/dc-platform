# Security

Secure-by-default stateless JWT resource-server security: every request is authenticated unless
explicitly permitted, 401/403 responses are RFC-9457 problem bodies, and `CurrentUserAccessor`
exposes the authenticated principal without coupling application code to JWT/claims.

## What you get

- **Baseline `SecurityFilterChain`** — stateless (no session), permit-paths open (actuator
  health/info, `/v3/api-docs/**`, swagger by default), everything else authenticated, security
  headers on, method security enabled (`@EnableMethodSecurity`).
- **JWT resource server** — wired off the standard `spring.security.oauth2.resourceserver.jwt.*`
  properties (issuer-uri or jwk-set-uri); no platform-specific JWT configuration.
- **RFC-9457 401/403 bodies** — the security filter chain runs before the `DispatcherServlet`, so
  the errors capability's `@RestControllerAdvice` cannot shape these; a small internal
  entry-point/denied-handler pair writes the problem body directly.
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
          issuer-uri: https://issuer.example/
```

## Zero-config behavior

Anonymous requests to anything not in `permit-paths` get:

```json
{"status":401,"title":"Unauthorized","detail":"Full authentication is required to access this resource"}
```

A valid bearer JWT authenticates the request; `CurrentUserAccessor.currentUser()` returns the
principal derived from it.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.security.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.security.mode` | `resource-server` | `resource-server` builds the baseline chain; `disabled` leaves security to the application. Explicit only — never implied by a profile. |
| `dc.platform.security.permit-paths` | actuator health/info, `/v3/api-docs/**`, swagger | Request patterns open without authentication. |

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

- Define your own `SecurityFilterChain` bean to replace the baseline entirely.
- Define your own `CurrentUserAccessor` bean to replace the JWT-claims-based default.
- `dc.platform.security.mode=disabled` leaves security to the application (explicit only).
- `dc.platform.security.enabled=false` switches the capability off wholesale.

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
Quick smoke check: `curl -i localhost:8080/whatever` — a `401` with an
`application/problem+json` body proves the chain is active.
