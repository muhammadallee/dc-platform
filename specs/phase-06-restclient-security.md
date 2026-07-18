# Phase 6 — REST Client & Security (P1, size M, no Docker)

## A. REST client (`restclient/`): api, autoconfigure, starter
- restclient-api:
```java
/** Factory for platform-conventional RestClient builders. Inject this instead of RestClient.Builder.
 *  Conventions applied: correlation header, auth token relay (if security present), Observation,
 *  sane timeouts, error → RemoteCallException mapping. @since 0.2.0 */
public interface PlatformRestClientFactory {
    RestClient.Builder builder(String clientName);   // clientName tags metrics & config lookup
}
/** Thrown for non-2xx replies. Carries status, body snippet (truncated 1KB), remote correlationId. */
public class RemoteCallException extends PlatformException { … }
@FunctionalInterface public interface PlatformRestClientCustomizer { void customize(String name, RestClient.Builder b); }
```
- autoconfigure: `RestClientProperties` (`acme.platform.restclient`): `defaults.connect-timeout=2s`,
  `defaults.read-timeout=10s`, per-client override map `clients.<name>.*`, `propagate-correlation=true`.
  Implementation uses JDK HttpClient request factory; applies customizers ordered; token relay applied
  ONLY `@ConditionalOnClass(OAuth2 …)` + `@ConditionalOnBean` — guarded optional edge to security api.
- Tests: matrix; MockWebServer (okhttp mockwebserver — pin) asserting headers, timeout config, error mapping.

## B. Security (`security/`): security-api, security-autoconfigure, starter-security, authz-api, authz-spi, authz-autoconfigure, starter-authz
- security-api: `SecurityCustomizer { void customize(HttpSecurity http) }` (ordered),
  `CurrentUser` record (subject, tenant?, roles, claims map) + `CurrentUserAccessor` interface.
- security-autoconfigure (`acme.platform.security`): `mode=resource-server|disabled` (default resource-server),
  `permit-paths` list (defaults: actuator health/info, /v3/api-docs/**, swagger).
  Baseline `SecurityFilterChain` (`@ConditionalOnMissingBean(SecurityFilterChain.class)`):
  stateless, JWT resource server (issuer from standard `spring.security.oauth2.resourceserver.jwt.*`),
  security headers, permit-paths, everything else authenticated; applies `SecurityCustomizer`s;
  method security enabled. `CurrentUserAccessor` impl reading Jwt principal.
  **Local execution:** `mode=disabled` profile-`local` default? NO — explicit only; instead tests use
  `spring-security-test` JWT mocks; docs show a dev-issuer docker option. Comment rationale (secure-by-default).
- authz-api: `@RequiresPermission("orders:read")` annotation; authz-spi: `PermissionEvaluatorProvider`
  (`boolean hasPermission(CurrentUser u, String permission)`); autoconfigure: method-security aspect
  bridging annotation→provider; default provider maps permissions from a roles claim
  (`RolesClaimPermissionProvider`, configurable claim name). starter-authz.
- Tests: matrix; `@WebMvcTest`+jwt() post-processors: 401 anon, 200 authed, 403 missing permission;
  customizer ordering test.

Acceptance: root verify; scratch app: anonymous 401 w/ ProblemDetail, mock-jwt 200, `@RequiresPermission` enforced.
Docs; BOM; CHANGELOG. **Tag 0.2.0-M1 optional.**
