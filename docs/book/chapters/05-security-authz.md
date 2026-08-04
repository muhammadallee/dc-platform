# Chapter 5 — Security and Authorization

> **Capabilities covered:** `security`, `authz`
>
> Authenticated by default, and permission checks that survive a refactor.
>
> **Starters:** `platform-starter-security`, `platform-starter-security-authz` ·
> **Reference:** [modules/security.md](../../modules/security.md) ·
> [modules/authz.md](../../modules/authz.md)

---

## 1. Introduction and Business Value

Two capabilities, two questions:

- **Security** answers *"who are you?"* — authentication. It builds a stateless JWT resource-server
  filter chain where everything is authenticated unless explicitly permitted.
- **Authorization** answers *"may you do this?"* — `@RequiresPermission("orders:read")` next to the
  method it protects.

They are separate capabilities and separate starters, and — importantly — the authz starter does
**not** transitively drag security in. §2.6 explains why that separation matters more than it looks.

### The problem it solves

The most common and most damaging security defect in a service estate is not a clever exploit. It is
an endpoint that shipped unauthenticated **because nobody remembered to protect it**.

That is a structural failure, not a discipline failure. Consider the two possible defaults:

| Default | New endpoint is | Failure mode |
|---|---|---|
| **Allow, protect explicitly** | Open | **Silent.** It works, tests pass, and the gap is found by an attacker or an auditor, months later |
| **Deny, permit explicitly** | Protected | **Loud.** The developer gets a 401 on their own machine, on the day they write the endpoint |

The second default cannot fail quietly. That is its entire value, and it is why the platform's chain
ends with `anyRequest().authenticated()` and a short, explicit permit list.

### Why 401 and 403 are the hardest responses to get right

[Chapter 2](02-errors-validation.md) established that the security filter chain runs **before**
`DispatcherServlet`, so `@RestControllerAdvice` cannot reach it. The consequence in practice:

```
  Every other error:   {"type":"...","title":"DC-ORDER-0404","status":404,"code":"...","correlationId":"..."}
  401 and 403:         <html><body><h1>Whitelabel Error Page</h1>...   or an empty body
```

A client now needs two parsers, and the two statuses it most needs to handle correctly — refresh the
token, versus show a permissions message — are the two with no machine-readable body. Every consumer
of every service carries that special case, forever.

The platform installs a dedicated `AuthenticationEntryPoint` and `AccessDeniedHandler` pair that write
the same RFC-9457 shape. One parser for every failure mode.

### Impact of absence

| Without these capabilities | What actually happens |
|---|---|
| No deny-by-default chain | Endpoints ship unauthenticated by omission — the single most damaging common defect |
| No RFC-9457 auth failures | 401/403 return HTML or nothing; every client special-cases them permanently |
| No `CurrentUser` abstraction | Every consumer parses JWT claims directly; changing IdP becomes a fleet-wide refactor |
| No `@RequiresPermission` | Permission checks are scattered `if` statements — easy to omit, impossible to audit in a diff |
| Wrong 401/403 distinction | Clients retry authentication loops against a permanent denial |
| Profile-triggered disabling | A misapplied profile silently removes authentication from production |

---

## 2. Core Concepts and Underlying Principles

### 2.1 Resource server, not authorization server

Two roles in OAuth 2.0 / OpenID Connect, and it is worth being precise about which one your service
plays:

| Role | Does | Is your service? |
|---|---|---|
| **Authorization server** (IdP) | Authenticates humans, issues tokens, publishes signing keys | **No** |
| **Resource server** | Validates tokens, serves protected resources | **Yes** |

Your service never sees a password. It receives `Authorization: Bearer <jwt>`, verifies the signature
against the IdP's published keys, checks issuer/audience/expiry, and reads the claims.

The verification is **local and offline** after key fetch — no call to the IdP per request. Spring
caches the JSON Web Key Set from `jwk-set-uri` and refreshes on key rotation. This is what makes JWT
validation cheap enough to do on every request.

!!! note "The platform uses Spring Security's own property namespace"
    ```yaml
    spring:
      security:
        oauth2:
          resourceserver:
            jwt:
              issuer-uri: https://idp.example.gov.ae/realms/dc
    ```
    Not `dc.platform.security.jwt.*`. Where the ecosystem already has a good property, the platform
    uses it — so existing Spring Security knowledge, documentation, and Stack Overflow answers apply
    verbatim, and there is no parallel dialect to keep in sync. `issuer-uri` is preferred over
    `jwk-set-uri` because it discovers the key set *and* validates the issuer claim.

### 2.2 The filter chain the platform builds

```java
http.csrf(csrf -> csrf.disable())
    .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
    .headers(Customizer.withDefaults())
    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
    .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint(new ProblemDetailAuthenticationEntryPoint())
            .accessDeniedHandler(new ProblemDetailAccessDeniedHandler()));

// customizers run HERE — while the matcher registry is still open

http.authorizeHttpRequests(auth -> {
    properties.permitPaths().forEach(path -> auth.requestMatchers(path).permitAll());
    auth.anyRequest().authenticated();
});
```

Line by line, because every line is a decision:

- **`csrf.disable()`** — CSRF attacks exploit *ambient credentials*: a cookie the browser attaches
  automatically. A bearer token must be attached deliberately by JavaScript, so there is nothing
  ambient to exploit. Disabling CSRF for a stateless bearer-token API is correct, not a shortcut.
  **It would be wrong the moment you add cookie-based sessions.**
- **`STATELESS`** — no `HttpSession`, no `JSESSIONID`. Every request carries its own credentials,
  which is what makes horizontal scaling free: any instance can serve any request.
- **`headers(withDefaults())`** — Spring Security's standard set: `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Cache-Control: no-cache`, and HSTS on HTTPS.
- **`oauth2ResourceServer().jwt()`** — the JWT validation described in §2.1.
- **`exceptionHandling(...)`** — the two handlers that fix the 401/403 body problem.

### 2.3 Why customizers run *before* `anyRequest()`

This is the most important structural detail in the chapter, and it is not an implementation
preference — it is the only ordering that works.

**Spring Security forbids adding `authorizeHttpRequests()` matchers after `anyRequest()`.** The
registry is closed by that terminal matcher; adding another throws at startup.

So if the platform applied customizers *after* its own `anyRequest().authenticated()`, a customizer
that wanted to permit one extra path would fail the context. The platform therefore applies them while
the registry is still open:

```
   platform baseline (csrf, session, headers, jwt, exception handling)
        |
        v
   SecurityCustomizer beans, in @Order        <-- your matchers land here
        |
        v
   permit-paths
   anyRequest().authenticated()               <-- terminal; registry closes
```

The security property this buys is worth stating explicitly: **a customizer can open specific paths
but cannot issue a blanket permit.** A customizer calling `auth.anyRequest().permitAll()` would be
followed by the platform's own `anyRequest()`, and Spring Security's first-match-wins ordering plus
the closed registry make an accidental fleet-wide open impossible to express. The safe ordering is
structurally enforced, not documented and hoped for.

### 2.4 Deny-by-default and the permit list

```
  /actuator/health          /actuator/health/**       /actuator/info
  /v3/api-docs/**           /swagger-ui/**            /swagger-ui.html
  ---------------------------------------------------------------
  everything else                                     authenticated
```

Six patterns, and each is there for a reason: health and info because orchestrators and load balancers
probe them without credentials; api-docs and swagger because a developer portal needs to read the
contract.

Note what is **not** permitted: `/actuator/env`, `/actuator/configprops`, `/actuator/metrics`,
`/actuator/prometheus`, and `/actuator/platform` all require authentication, even though
[observability](03-logging-observability.md) exposes them over HTTP. That split — exposed but
authenticated — is deliberate, and §5.5 covers what it means for your metrics scraper.

!!! warning "`permit-paths` replaces the list; it does not extend it"
    It is a `List<String>` property. Setting it in YAML **overwrites the default six**, so a service
    that adds `/webhooks/**` and forgets to re-list the rest breaks its own health probes. Prefer a
    `SecurityCustomizer` for additions — §4.4.

### 2.5 `CurrentUser` — why not just inject `Jwt`

Spring Security will happily give you the token:

```java
@GetMapping("/me")
String me(@AuthenticationPrincipal Jwt jwt) {
    return jwt.getClaimAsString("sub");     // works, and couples you to JWT forever
}
```

The platform provides `CurrentUser` instead — `(subject, tenant, roles, claims)` — and the reason is
about *blast radius*, not elegance.

Every capability that needs identity depends on `CurrentUser`, not on `Jwt`:

| Capability | Uses identity for |
|---|---|
| [Audit](12-audit.md) | The actor on every audit event |
| [Data](08-data.md) | `@CreatedBy` / `@LastModifiedBy` on every row |
| [Flags](13-ratelimit-flags.md) | Subject and tenant in the evaluation context |
| [REST client](06-restclient-resilience.md) | Deciding whether to relay the bearer token |
| [Authz](#) | The principal a permission is evaluated against |

If those five depended on `Jwt`, changing IdP or token format — or adding opaque-token introspection
alongside JWT — would be a fleet-wide refactor touching every service. With `CurrentUser`, it is one
bean.

!!! success "Best practice — depend on `CurrentUserAccessor`, never on `Jwt`"
    In application code too. The day your organisation moves from one IdP to another, the services
    that read `CurrentUser` need no change and the services that parsed claims directly need a
    migration project.

### 2.6 Why authz does not depend on security

The authz starter does not pull the security starter in. Two independent reasons.

**It keeps the capabilities separately useful.** A service could plug in a different authentication
mechanism entirely and still want `@RequiresPermission`.

**It prevents a silently permissive half-wired state** — the more important one. Look at the
condition:

```java
@Bean
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@ConditionalOnBean(CurrentUserAccessor.class)
Advisor requiresPermissionAdvisor(...) { ... }
```

The advisor is created **only when a `CurrentUserAccessor` bean exists**. If authz activated without
an identity source, every `@RequiresPermission` check would evaluate against nothing — and would
either grant everything or deny everything. Both are catastrophic, and both are silent.

So: no identity source, no advisor. The annotation becomes inert, which is visible in testing, rather
than wrong, which is not.

!!! warning "Inert means the method runs unprotected"
    `@RequiresPermission` with no `CurrentUserAccessor` on the classpath is *documentation*, not a
    check. That is the trade the platform makes — a half-wired advisor is worse — but it means you must
    verify the advisor is actually active. §5.3 shows how.

### 2.7 401 versus 403, and why clients care

| Status | Means | Client should |
|---|---|---|
| **401 Unauthorized** | You are not authenticated — no token, expired, invalid signature | Refresh the token and retry |
| **403 Forbidden** | You *are* authenticated, but you may not do this | Stop. Show a permissions message |

Conflate them and clients misbehave in both directions: a client that treats 403 as 401 enters a
token-refresh loop against a permanent denial; a client that treats 401 as 403 tells the user "access
denied" when their session simply expired.

The platform's authz advisor throws two different exceptions to keep this correct:

- Unauthenticated → `InsufficientAuthenticationException` → the entry point → **401**
- Authenticated but unpermitted → `AccessDeniedException` → the denied handler → **403**

### 2.8 Spring AOP, and the self-invocation caveat again

`@RequiresPermission` is enforced by a Spring AOP `Advisor` — a pointcut (which methods) plus an
interceptor (what to do). Proxy-based, which brings the caveat you met in
[Chapter 2](02-errors-validation.md) and will meet again in Chapters
[10](10-coordination.md), [12](12-audit.md), and [13](13-ratelimit-flags.md):

```java
@Service
class OrderService {

    @RequiresPermission("orders:read")
    Order get(String id) { ... }

    List<Order> getAll(List<String> ids) {
        return ids.stream().map(this::get).toList();   // BYPASSES the check entirely
    }
}
```

`this.get(...)` does not go through the proxy. No advisor, no permission check.

!!! warning "Self-invocation is a security bypass, not just a missing feature"
    For [caching](09-cache-redis.md) a bypassed proxy costs performance. Here it costs a permission
    check. Annotate at the boundary the caller actually reaches, and treat any internal call to an
    annotated method as a finding in review.

The platform's advisor is registered with `@Role(ROLE_INFRASTRUCTURE)` and an
`InfrastructureAdvisorAutoProxyCreator` — using the same cooperative escalation protocol
`@EnableMethodSecurity` uses, so proxying works whether or not method security is separately enabled,
and the two never register competing proxy creators. No `aspectjweaver`, no load-time weaving, no
agent flags.

---

## 3. Feature Reference

### 3.1 Security — public API

Package `ae.gov.dubaicustoms.platform.security`.

| Type | Kind | Status | Purpose |
|---|---|---|---|
| `CurrentUser` | record | STABLE | `(subject, tenant, roles, claims)` — the platform-neutral principal |
| `CurrentUserAccessor` | interface | STABLE | `Optional<CurrentUser> currentUser()` |
| `SecurityCustomizer` | functional interface | STABLE | `void customize(HttpSecurity http) throws Exception` |

#### `CurrentUser`

| Component | Type | Null? | Notes |
|---|---|---|---|
| `subject` | `String` | never | The JWT `sub` claim |
| `tenant` | `String` | **may be null** | Null when the token carries none |
| `roles` | `Set<String>` | never | May be empty. Defensively copied, immutable |
| `claims` | `Map<String, Object>` | never | The full claim set. Defensively copied, immutable |

!!! warning "`tenant` is the one nullable component"
    `subject`, `roles`, and `claims` are validated non-null at construction. `tenant` is not. A
    multi-tenant service must handle its absence explicitly rather than assuming it.

### 3.2 Authorization — public API and SPI

| Type | Module | Status | Purpose |
|---|---|---|---|
| `@RequiresPermission` | authz-api | STABLE | `String value()`. Targets `METHOD` and `TYPE` |
| `PermissionEvaluatorProvider` | authz-**spi** | **EXPERIMENTAL** | `boolean hasPermission(CurrentUser, String)` |

!!! note "The SPI is marked EXPERIMENTAL"
    `@API(status = EXPERIMENTAL)` — the platform reserves the right to change this contract in a minor
    release. It is usable and tested; it is not yet frozen. If you build a provider against it, expect
    to revisit it. Consumer-facing `@RequiresPermission` is STABLE.

**Implementation requirements for a provider**, from the interface: thread-safe, and **fast** — it
runs on the request thread for every annotated invocation. `user` and `permission` are never null.

### 3.3 The default provider

`RolesClaimPermissionProvider` grants a permission when the string appears in the configured claim
(`dc.platform.authz.roles-claim`, default `roles`), handling both a collection and a single string,
and falling back to `CurrentUser.roles()` when the claim is absent or not in that shape.

```
  Token claim  roles: ["orders:read", "orders:write"]
  @RequiresPermission("orders:read")   ->  granted
  @RequiresPermission("orders:delete") ->  denied, 403
```

!!! note "It is exact string matching, not a hierarchy"
    `orders:*` does not grant `orders:read`. `admin` does not grant everything. If you want wildcards,
    role hierarchies, or resource-scoped permissions, that is what the SPI is for — §4.7.

Multiple providers **compose with any-grant-wins**: the advisor collects every
`PermissionEvaluatorProvider` bean in `@Order` and grants if any returns true. Useful for migration —
run a claim-based provider and an entitlement-service provider side by side while you move.

### 3.4 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.security.enabled` | Boolean | `true` | Kill switch for the capability | Essentially never |
| `dc.platform.security.mode` | `resource-server` \| `disabled` | `resource-server` | `disabled` leaves security entirely to the application | Only when you are building the chain yourself |
| `dc.platform.security.permit-paths` | `List<String>` | The six patterns in §2.4 | Patterns open without authentication | **Prefer a customizer** — this replaces the list |
| `dc.platform.authz.enabled` | Boolean | `true` | Kill switch | Essentially never |
| `dc.platform.authz.roles-claim` | String | `roles` | The claim the default provider reads | When your IdP puts permissions elsewhere — `scope`, `permissions`, `realm_access.roles` |

Plus Spring Security's own, which you must set:

| Key | Meaning |
|---|---|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | **Preferred.** Discovers keys *and* validates the issuer claim |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | Keys only — no issuer validation |

!!! warning "`mode: disabled` is explicit-only, and never implied by a profile"
    This is a deliberate defence against a well-documented production breach pattern: a `local` profile
    leaks into a production deployment through a misconfigured environment variable, and authentication
    silently disappears. There is no profile, no environment, and no convenience flag that disables
    platform security. It takes an explicit property, which shows up in `/actuator/env` with the source
    that set it.

### 3.5 Auto-configuration

| Class | Activates when | Contributes | Backs off when |
|---|---|---|---|
| `PlatformSecurityAutoConfiguration` | Servlet web app, `SecurityCustomizer` + `HttpSecurity` on classpath, `security.enabled != false` | `platformSecurityFilterChain`, `currentUserAccessor`, `securityCapabilityDescriptor` | You define **any** `SecurityFilterChain` bean, or `mode: disabled` |
| `PlatformAuthzAutoConfiguration` | `RequiresPermission` + `PermissionEvaluatorProvider` + `CurrentUserAccessor` on classpath, `authz.enabled != false` | `permissionEvaluatorProvider`, `requiresPermissionAdvisor`, `authzCapabilityDescriptor` | You define a `PermissionEvaluatorProvider` bean; the **advisor** additionally requires a `CurrentUserAccessor` *bean* |

!!! note "Boot's own security auto-configuration backs off automatically"
    No ordering is needed. Spring Boot's `SecurityAutoConfiguration` stands down the moment any
    `SecurityFilterChain` bean exists — the platform's chain is just such a bean, so Boot's default
    (which would generate a password and log it at startup) never applies.

The authz auto-configuration is ordered `afterName` the security one — by **string, not class
literal**, because the dependency constitution gives an autoconfigure module no allowance to reference
another capability's autoconfigure class, even same-capability. Ordering by name respects the
constitution while still getting `currentUserAccessor` registered before the advisor's
`@ConditionalOnBean` is evaluated. Boot only sees *previously processed* auto-configurations when
evaluating bean conditions, which is exactly why the ordering is required.

### 3.6 Failure analysis

`SecurityNoIssuerFailureAnalyzer` turns the most common misconfiguration — resource server configured
with no issuer — into a Boot *Description / Action* block naming the property to set, instead of a
`NoSuchBeanDefinitionException` for `JwtDecoder`.

### 3.7 Extension points

| Extension | How | Effect |
|---|---|---|
| Open extra paths | `SecurityCustomizer` bean | Applied in `@Order`, before `anyRequest()` |
| Replace the principal shape | `CurrentUserAccessor` bean | Platform's backs off; every capability follows |
| Change permission evaluation | `PermissionEvaluatorProvider` bean | Platform's default backs off |
| Add a second evaluation source | Another provider bean | Composes, any-grant-wins |
| Replace the chain entirely | Any `SecurityFilterChain` bean | Platform's backs off |
| Change the claim read | `dc.platform.authz.roles-claim` | No code |

---

## 4. How-to Guide

### 4.1 Add the capabilities

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-security-authz</artifactId>
</dependency>
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://idp.example.gov.ae/realms/dc
```

Boot the service. Every endpoint except the permit list now returns 401 without a token:

```bash
curl -i localhost:8080/orders/123
# HTTP/1.1 401
# Content-Type: application/problem+json
# {"type":"...","title":"...","status":401,"correlationId":"9f2c..."}
```

Note the correlation id on a 401 — that is [Chapter 1](01-core.md)'s highest-precedence filter
earning its place.

### 4.2 Read the current user

```java snippet:book-05-current-user
@Service
class DeclarationService {

    private final CurrentUserAccessor accessor;

    DeclarationService(CurrentUserAccessor accessor) {
        this.accessor = accessor;
    }

    String submittedBy() {
        return accessor.currentUser()
                .map(CurrentUser::subject)
                .orElse("anonymous");
    }

    Optional<String> tenant() {
        // tenant is the one nullable component — Optional.ofNullable, not map alone.
        return accessor.currentUser().flatMap(user -> Optional.ofNullable(user.tenant()));
    }
}
```

!!! warning "Do not inject `Jwt`"
    `@AuthenticationPrincipal Jwt jwt` works and couples your service to the token format. §2.5.

### 4.3 Require a permission

```java snippet:book-05-requires-permission
@Service
class OrderQueryService {

    @RequiresPermission("orders:read")
    Optional<String> findOrder(String orderId) {
        return Optional.of(orderId);
    }

    @RequiresPermission("orders:write")
    void cancelOrder(String orderId) {
        // ...
    }
}
```

Type-level works too, and applies to every method:

```java
@Service
@RequiresPermission("admin:manage")
class AdminService { ... }
```

!!! success "Best practice — name permissions `resource:action`"
    `orders:read`, `orders:write`, `declarations:approve`. It reads well at the call site, it groups
    naturally in an IdP's role list, and it leaves room for `resource:action:scope` later without a
    rename. Whatever you choose, choose it once — permission strings are a contract with your IdP
    configuration, and renaming one means a coordinated change.

### 4.4 Open an extra path

Prefer a customizer over `permit-paths`, because it **adds** rather than replaces:

```java snippet:book-05-security-customizer
@Configuration
class WebhookSecurity {

    @Bean
    @Order(10)
    SecurityCustomizer webhookEndpoint() {
        return http -> http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/webhooks/carrier/**").permitAll());
    }
}
```

!!! warning "A permitted path is a path anyone on the network can reach"
    A webhook endpoint that is `permitAll()` needs its own authentication — an HMAC signature check, a
    shared secret, mutual TLS. `permitAll()` means "the platform chain will not check this", not "this
    is safe". Write the check.

### 4.5 Use a different claim

Keycloak, Auth0, and Entra all put permissions in different places:

```yaml
dc:
  platform:
    authz:
      roles-claim: permissions      # or: scope, realm_access.roles, ...
```

If your IdP nests them (`realm_access.roles`) rather than exposing a flat claim, a flat claim name will
not reach it — write a provider (§4.7) or configure a `JwtAuthenticationConverter` to flatten them.

### 4.6 Test without an IdP

`platform-starter-test` ships `TestTokens`, and `spring-security-test` provides the `jwt()` request
post-processor. No live issuer, no network:

```java
@PlatformWebTest
class OrderControllerTest {

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/orders/1"))
               .andExpect(status().isUnauthorized())
               .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void withoutPermissionIsForbidden() throws Exception {
        mockMvc.perform(get("/orders/1").with(jwt().jwt(j -> j.claim("roles", List.of("orders:write")))))
               .andExpect(status().isForbidden());
    }

    @Test
    void withPermissionSucceeds() throws Exception {
        mockMvc.perform(get("/orders/1").with(jwt().jwt(j -> j.claim("roles", List.of("orders:read")))))
               .andExpect(status().isOk());
    }
}
```

!!! success "Best practice — test all three states, always"
    Unauthenticated (401), authenticated-without-permission (403), authenticated-with-permission (200).
    The middle case is the one that catches a missing or bypassed annotation, and it is the one most
    often omitted.

### 4.7 Plug in an entitlement service

```java snippet:book-05-permission-provider
@Configuration
class EntitlementAuthorization {

    @Bean
    PermissionEvaluatorProvider entitlementProvider(EntitlementClient client) {
        return (user, permission) -> client.allows(user.subject(), permission);
    }
}

interface EntitlementClient {
    boolean allows(String subject, String permission);
}
```

The default claim-based provider backs off. Every `@RequiresPermission` in the service now consults
your entitlement service — **without a single annotated method changing**. That is the value of the
SPI seam.

!!! warning "This provider is on the request path for every annotated call"
    A remote call per permission check will dominate your latency. Cache aggressively
    ([Chapter 9](09-cache-redis.md)), and decide deliberately what happens when the entitlement service
    is down: fail closed (403, secure, unavailable) or fail open (permit, available, insecure). There
    is no safe default — pick one and write it down. Contrast
    [rate limiting](13-ratelimit-flags.md), which fails *open* deliberately, and note that
    authorization is the opposite case.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| All | `issuer-uri` pointing at that environment's IdP | The only required setting |
| All | `mode: resource-server` (the default) | Never `disabled` |
| Local | A local IdP, or slice tests with `jwt()` | The archetype ships a placeholder so the app boots offline |
| Production | Review `permit-paths` | Especially api-docs and swagger — see §5.5 |
| Production | Confirm the actuator split | Health and info open; env, metrics, platform authenticated |

!!! warning "The archetype's placeholder issuer is a placeholder"
    The generated `application.yml` ships a placeholder `jwk-set-uri` so a fresh service boots without
    an IdP. It validates nothing. Replace it before the service reaches any shared environment, and
    check for it in code review — it is the single most likely thing to be forgotten between
    "generated" and "deployed".

### 5.2 What to monitor

| Signal | Where | Alert when |
|---|---|---|
| 401 rate | `http_server_requests{status="401"}` | A spike means an expiring credential, a rotated key, or an attack |
| 403 rate | `http_server_requests{status="403"}` | A spike after a release means a permission mapping changed |
| 401 on previously-working paths | Metrics + logs | Usually a `permit-paths` change or a customizer ordering change |
| JWT decode failures | Logs, `JwtDecoder` at DEBUG | Sustained failures mean clock skew, key rotation, or a wrong issuer |
| `/actuator/platform` shows `security[ACTIVE] (resource-server)` | Endpoint | It reports `disabled` — investigate immediately |

!!! tip "The most valuable security alert is a *drop* in 401s"
    A sudden fall to zero on a service that normally sees some means authentication may have stopped
    being enforced — a `mode: disabled` that reached production, or a customizer that opened more than
    intended. Rising 401s are noisy and usually benign; a cliff is not.

### 5.3 Troubleshooting

**Everything returns 401, including things that should work.**

| Cause | Check |
|---|---|
| No `issuer-uri` configured | The `FailureAnalyzer` should have failed startup — check the boot log |
| Wrong issuer | The `iss` claim must match exactly, trailing slash included |
| Clock skew | Token `exp`/`nbf` versus server time. A few minutes of skew is enough |
| Key rotation | The IdP rotated signing keys and the cache is stale. Check `jwk-set-uri` reachability |
| Missing `Authorization` header | `curl -v` and confirm it is actually being sent |

**`@RequiresPermission` is not being enforced.** Work through in this order:

1. **Is the advisor active?** No `CurrentUserAccessor` bean means no advisor (§2.6). Check
   `/actuator/beans` for `requiresPermissionAdvisor`. This is the most common cause and the most
   dangerous, because the method runs unprotected.
2. **Self-invocation?** An internal `this.method()` call bypasses the proxy (§2.8).
3. **Is the bean proxied?** A `final` class or `final` method cannot be proxied.
4. **Is the claim right?** `roles-claim` must match what your IdP actually issues. Decode the token.
5. **Is authz enabled?** `dc.platform.authz.enabled`.

**A 403 that should be a 200.** Decode the token and inspect the claim named by `roles-claim`. The
default provider does **exact string matching** — `orders:*` does not grant `orders:read`, and the
claim must be a collection of strings or a single string.

**401 when you expected 403.** The request was not authenticated at all. That is the distinction
working correctly (§2.7) — check the token, not the permission.

**Health checks started failing after a config change.** `permit-paths` was set and replaced the
default six. §2.4.

### 5.4 Scaling and performance

- **JWT validation is local.** Signature verification against a cached key, plus claim checks.
  Microseconds. There is no per-request IdP call.
- **The key set is fetched on demand and cached.** The first request after a key rotation pays a
  fetch. If the `jwk-set-uri` is unreachable, that request fails — the IdP is a startup-and-rotation
  dependency, not a per-request one.
- **Stateless scales linearly.** No session replication, no sticky sessions.
- **A remote `PermissionEvaluatorProvider` changes all of this.** It puts a network call on the
  request path for every annotated invocation. §4.7.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| `permit-paths` includes api-docs and swagger | An unauthenticated caller can read your full API surface. Fine internally; reconsider at an external boundary |
| Metrics endpoints require authentication | Your Prometheus scraper needs credentials, or a network-level exception. Plan for it |
| `csrf.disable()` is correct **only** while stateless | Add cookie-based sessions and you must re-enable it |
| `permitAll()` is not "safe" | It means the platform chain will not check. Webhooks need their own authentication |
| Tokens in logs | A bearer token in a log line is a live credential. Never log headers wholesale; see [`LogSanitizer`](03-logging-observability.md) |
| `mode: disabled` in any deployed environment | Should fail a deployment gate. Assert on it in a test |
| Permission strings are IdP configuration | Renaming one requires a coordinated change. Treat them as a published contract |
| A `SecurityFilterChain` bean anywhere in your app | Silently replaces the platform's entire chain, including the RFC-9457 handlers |

!!! warning "The most dangerous accidental change in this chapter"
    Declaring **any** `SecurityFilterChain` bean — perhaps copied from a tutorial to add one CORS rule
    — replaces the platform's chain wholesale. You lose deny-by-default, the problem-shaped 401/403
    bodies, and the stateless posture, silently and completely. Use a `SecurityCustomizer` instead;
    reach for a full chain only when you intend to own all of it.

---

## 6. Deep Dive

### 6.1 What `JwtCurrentUserAccessor` actually does

It reads the `Authentication` from the `SecurityContextHolder`, checks the principal is a `Jwt`, and
maps:

```
  Jwt.getSubject()                    ->  subject
  Jwt claim "tenant"                  ->  tenant   (null when absent)
  granted authorities, stripped        ->  roles
  Jwt.getClaims()                     ->  claims   (defensively copied)
```

Two consequences worth knowing:

- **`roles` comes from granted authorities**, which Spring Security derives via its
  `JwtAuthenticationConverter` — by default from the `scope`/`scp` claim with a `SCOPE_` prefix. That
  is *not* the same source as `dc.platform.authz.roles-claim`, which the default provider reads
  directly from `claims`. The provider tries the configured claim first and falls back to `roles`,
  which is why both paths matter.
- **`SecurityContextHolder` is thread-local**, so `CurrentUser` has the same thread-boundary behaviour
  as [`RequestContext`](01-core.md) — it does not follow work onto another thread.

### 6.2 Why the advisor is `ROLE_INFRASTRUCTURE`

```java
@Bean
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
Advisor requiresPermissionAdvisor(...)
```

`InfrastructureAdvisorAutoProxyCreator` — the proxy creator the platform registers, and the one
`@EnableMethodSecurity` uses — considers **only** advisors with this role. A plain application-role
`Advisor` bean is silently ignored.

That is a genuinely useful trap to know about: if you write your own `Advisor` and it never fires, the
role is the first thing to check. It also explains why the two mechanisms cooperate rather than
fighting — both go through `AopConfigUtils`'s escalation protocol, so whichever registers first wins
and the other defers, and you never end up with two proxy creators wrapping the same bean.

### 6.3 Ordering by name, and the constitution

```java
@AutoConfiguration(afterName = "ae.gov.dubaicustoms.platform.security.autoconfigure.PlatformSecurityAutoConfiguration")
```

A string, not a class literal — and that is not stylistic. The
[dependency constitution](../../concepts/constitution.md) gives an autoconfigure module no allowance
to depend on another capability's autoconfigure module. A class literal would require exactly that
dependency and would fail the enforcer.

Ordering by name achieves the same effect with no compile-time edge. It is a small, honest example of
the constitution shaping the code rather than being documented and ignored — and the comment in the
source says so explicitly.

Why the ordering is needed at all: Boot evaluates `@ConditionalOnBean` against **previously
processed** auto-configurations only. Without the ordering, `requiresPermissionAdvisor`'s
`@ConditionalOnBean(CurrentUserAccessor.class)` might be evaluated before
`PlatformSecurityAutoConfiguration` has registered `currentUserAccessor`, and the advisor would
silently not be created.

!!! warning "This is why `@ConditionalOnBean` between auto-configurations is fragile"
    The general lesson, worth carrying to your own auto-configurations: `@ConditionalOnBean` is
    reliable against *user* beans (registered first) and fragile between auto-configurations. If you
    need it, order explicitly. [Primer 1](../primer/01-spring-boot.md) §2 makes the same point.

### 6.4 The `permit-paths` list-replacement trap, generalised

`List<String>` binding in Spring Boot **replaces**, it does not merge. Setting a list property in YAML
discards the default entirely.

This is standard Boot behaviour and it catches people on every list-valued property, but it is
particularly sharp here because the failure is a broken health probe rather than an error:

```yaml
# WRONG — health probes now require authentication
dc.platform.security.permit-paths:
  - /webhooks/**

# Right, but brittle — you now own the platform's list forever
dc.platform.security.permit-paths:
  - /actuator/health
  - /actuator/health/**
  - /actuator/info
  - /v3/api-docs/**
  - /swagger-ui/**
  - /swagger-ui.html
  - /webhooks/**
```

The second form works and means every future change to the platform's default list has to be manually
merged into your service. A `SecurityCustomizer` (§4.4) adds without owning.

### 6.5 What `mode: disabled` actually does

It is not "turn off authentication". It is "the platform contributes no chain":

```java
@ConditionalOnProperty(prefix = "dc.platform.security", name = "mode",
                       havingValue = "resource-server", matchIfMissing = true)
```

Both `platformSecurityFilterChain` and `currentUserAccessor` carry that condition. With
`mode: disabled`, neither exists — and Boot's own `SecurityAutoConfiguration`, no longer backing off,
applies its default chain: HTTP Basic with a generated password logged at startup.

So `disabled` gives you Boot's default security, not *no* security. And because `currentUserAccessor`
is also gone, the authz advisor's `@ConditionalOnBean` fails and `@RequiresPermission` goes inert.
Disabling security disables authorization too, silently. That coupling is worth knowing before you set
the property.

### 6.6 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Declaring a `SecurityFilterChain` for one small change | Every tutorial shows a full chain | `SecurityCustomizer`. §5.5 |
| Setting `permit-paths` to add one path | It reads like it appends | It replaces. Use a customizer |
| Injecting `Jwt` instead of `CurrentUser` | Fewer keystrokes | Couples every consumer to the token format |
| Self-invocation past `@RequiresPermission` | Nothing warns you | A security bypass. Annotate at the reachable boundary |
| `@RequiresPermission` with no `CurrentUserAccessor` | The annotation compiles | The advisor never exists; the method is unprotected |
| Assuming `orders:*` grants `orders:read` | Wildcards look natural | Exact matching. Write a provider if you need patterns |
| Leaving the archetype's placeholder issuer | It boots fine | It validates nothing. Check in review |
| Forgetting the metrics scraper needs credentials | Health is open, so metrics feels like it should be | It is authenticated by design |
| Logging request headers | Debugging | A bearer token is a live credential |
| Treating 403 as retryable | Both are "auth errors" | 401 refresh-and-retry, 403 stop |
| A slow `PermissionEvaluatorProvider` | It is just a bean | It runs on every annotated call. Cache it |

---

## 7. Exercises and Hands-on Labs

Starting point: [`examples/example-golden-path`](../../examples.md), which is authenticated by
default and has `@RequiresPermission` in use.

### Lab 1 — Basic: prove deny-by-default

**Goal.** Confirm the chain is what §2.4 says it is, and see the problem-shaped auth failures.

**Steps.**

1. Boot and confirm `/actuator/platform` reports `security[ACTIVE] (resource-server)`.
2. `curl -i` a business endpoint with no token. Note the status, content type, and body.
3. `curl -i /actuator/health`, then `/actuator/env`. Explain the difference in one sentence.
4. Add a brand-new `@GetMapping("/lab/unprotected")` returning a string. Do **not** touch any security
   configuration. `curl -i` it.
5. Compare the 401 body with a 404 body from the same service, field by field.

**Expected outcome.** Step 2 gives 401 with `application/problem+json` and a `correlationId`. Step 3:
health is permitted, env is not. **Step 4 is the point of the lab** — a brand-new endpoint is protected
without anyone doing anything. Step 5 shows one shape for every failure.

**Hints.**

- Step 4's endpoint returning 401 is success, not a bug.
- The `correlationId` on a 401 comes from [Chapter 1](01-core.md)'s highest-precedence filter. Note
  which lines in the log carry it.

**How to verify.** A `@PlatformWebTest` asserting 401 and
`content().contentType("application/problem+json")` for an unauthenticated request.

### Lab 2 — Intermediate: permissions, and the three states

**Goal.** Wire `@RequiresPermission`, exercise 401/403/200, and find the self-invocation bypass.

**Steps.**

1. Annotate a read method `@RequiresPermission("orders:read")` and a write method
   `@RequiresPermission("orders:write")`.
2. Write three tests per method: no token (401), token with the *wrong* permission (403), token with
   the right one (200).
3. Add a method on the same class that calls the annotated one via `this.`. Call it with a token that
   has **no** permissions. What status?
4. Fix it. Write down which fix you would want to find in a year.
5. Change `roles-claim` to `permissions` without changing the test tokens. Predict the result, then
   run it.
6. Add a second `PermissionEvaluatorProvider` that grants `orders:read` unconditionally. Run the 403
   test again and explain the result.

**Expected outcome.** Step 3 returns **200** — the permission check was bypassed entirely. That is a
security bug you produced in four lines, which is the lesson. Step 5 gives 403 everywhere. Step 6
gives 200, because providers compose with any-grant-wins.

**Hints.**

- Step 3's fix options: move the annotation to the reachable boundary, split the class, or
  self-inject. The first is almost always right.
- Step 6 is how you would migrate from claim-based to entitlement-based authorization without a big
  bang — worth noting as a real technique.

**How to verify.** Nine tests (three methods × three states), all passing, including one that asserts
the *bypassing* method is now also protected.

### Lab 3 — Advanced: replace the identity and the evaluator

**Goal.** Exercise both SPI seams and understand the blast radius of each.

**Steps.**

1. Write a `CurrentUserAccessor` that reads identity from a custom header instead of a JWT (test
   profile only — this is a lab, not a pattern).
2. Boot and confirm the platform's `JwtCurrentUserAccessor` backed off. Which bean does
   `/actuator/beans` show?
3. Confirm `@RequiresPermission` still works against your accessor, unchanged.
4. Write a `PermissionEvaluatorProvider` backed by an in-memory map. Confirm the default backs off.
5. Add a deliberate 200 ms sleep to your provider. Measure endpoint latency with and without it across
   a method that makes three annotated calls.
6. Decide what your provider does when its backing store is unavailable. Implement fail-closed. Then
   implement fail-open. Argue for one, in writing.
7. Add `@Audited` from [Chapter 12](12-audit.md) to a method and confirm the actor is *your*
   accessor's subject — proving the abstraction reaches other capabilities.

**Expected outcome.** Both defaults back off cleanly. Step 5 shows latency multiplying by the number
of annotated calls, not requests. Step 7 is the payoff: one bean changed identity for audit, data
auditing, flags, and authz simultaneously.

**Hints.**

- Step 1 must not reach production. Guard it with `@Profile("test")` and say so in a comment.
- Step 6 has no right answer, which is why it is the exercise. Note that
  [rate limiting](13-ratelimit-flags.md) fails open deliberately and authorization generally should
  not.

**How to verify.** A test asserting your accessor is the one in the context, that permission checks
still return 403 correctly, and that an audit event records your subject.

---

## 8. Checklist / Quick Reference

**Add them**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-security-authz</artifactId>
</dependency>
```

```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: https://idp.example.gov.ae/realms/dc
```

**API**

```java
accessor.currentUser()                  // Optional<CurrentUser>
    .map(CurrentUser::subject)          // never null
    .orElse("anonymous");
// user.tenant() may be null; roles() and claims() never are

@RequiresPermission("orders:read")      // method or type
SecurityCustomizer c = http -> http.authorizeHttpRequests(a -> a.requestMatchers("/x/**").permitAll());
PermissionEvaluatorProvider p = (user, permission) -> ...;   // EXPERIMENTAL
```

**Properties**

| Key | Default |
|---|---|
| `dc.platform.security.enabled` | `true` |
| `dc.platform.security.mode` | `resource-server` (never implied `disabled`) |
| `dc.platform.security.permit-paths` | health, health/**, info, api-docs/**, swagger-ui/**, swagger-ui.html |
| `dc.platform.authz.enabled` | `true` |
| `dc.platform.authz.roles-claim` | `roles` |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | **you must set this** |

**Status meanings**

| Status | Means | Client action |
|---|---|---|
| 401 | Not authenticated | Refresh token, retry |
| 403 | Authenticated, not permitted | Stop |

**Diagnose it**

```bash
curl -s localhost:8080/actuator/platform | jq          # security[ACTIVE] (resource-server)?
curl -i localhost:8080/your/endpoint                   # 401 problem+json?
curl -s localhost:8080/actuator/beans | jq '.. | select(.=="requiresPermissionAdvisor")?'
curl -s localhost:8080/actuator/env/dc.platform.security.mode
```

**Rules of thumb**

- Never declare a `SecurityFilterChain` for a small change — use a `SecurityCustomizer`.
- `permit-paths` **replaces** the default list. Prefer a customizer.
- Depend on `CurrentUserAccessor`, never on `Jwt`.
- Self-invocation bypasses `@RequiresPermission`. That is a security bug, not a performance one.
- No `CurrentUserAccessor` bean means no advisor, and unprotected methods. Verify it exists.
- Permission matching is exact — no wildcards, no hierarchy.
- `permitAll()` on a webhook means you must write its authentication yourself.
- `mode: disabled` gives you Boot's default chain *and* silently disables authz.
- Test all three states: 401, 403, 200.
- Your metrics scraper needs credentials — metrics are authenticated by design.

---

**Next:** [Chapter 6 — Outbound Calls: REST Client and Resilience](06-restclient-resilience.md), where
the token this chapter validated gets relayed to the next service.

**Reference:** [modules/security.md](../../modules/security.md) ·
[modules/authz.md](../../modules/authz.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md) ·
[platform security model](../crosscutting/security-model.md)

[Back to the book](../index.md)
