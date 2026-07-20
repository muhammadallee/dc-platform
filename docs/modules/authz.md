# Authz

Declarative permission enforcement: annotate a method (or a whole type) with `@RequiresPermission`
and the platform enforces it — anonymous callers get 401, callers lacking the permission get 403.

## What you get

- **`@RequiresPermission("orders:read")`** — method or type level; enforced by a Spring AOP
  advisor bridging to the ordered `PermissionEvaluatorProvider` beans.
- **`PermissionEvaluatorProvider` SPI** — pluggable evaluation strategy; the default
  `RolesClaimPermissionProvider` reads a configurable claim (default `roles`) off `CurrentUser`,
  falling back to the accessor-derived `roles()` set.
- **Correct status codes** — unauthenticated callers get
  `InsufficientAuthenticationException` (401); authenticated callers without the permission get
  `AccessDeniedException` (403); both are translated to RFC-9457 bodies by the security
  capability's baseline chain (the same entry-point/denied-handler pair errors and access-denials
  from the filter chain use).

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-security-authz</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-security</artifactId>
</dependency>
```

The authz starter does not pull in the security starter transitively (they are independently
useful capabilities): the advisor bean only activates once a `CurrentUserAccessor` bean actually
exists, so pair the two starters for a working setup.

## Zero-config behavior

```java
@RestController
class OrdersController {

    @RequiresPermission("orders:read")
    @GetMapping("/orders/{id}")
    Order get(String id) { ... }
}
```

Anonymous: 401. Authenticated JWT without `orders:read` in the `roles` claim (or granted
authority): 403. Authenticated JWT with it: 200.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.authz.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.authz.roles-claim` | `roles` | `CurrentUser.claims()` key the default provider reads permissions from. |

(Hand-written until the generated reference lands in phase 14.)

## Customize

```java
@Bean
PermissionEvaluatorProvider entitlementServiceProvider(EntitlementClient client) {
    return (user, permission) -> client.hasPermission(user.subject(), permission);
}
```
Replaces the default `RolesClaimPermissionProvider` (`@ConditionalOnMissingBean`). Multiple
providers may coexist; a permission is granted if any provider grants it.

## Replace / Disable

- Define your own `PermissionEvaluatorProvider` bean to replace the roles-claim default.
- `dc.platform.authz.enabled=false` switches the capability off wholesale — `@RequiresPermission`
  becomes inert.

## Testing

Use `spring-security-test`'s `jwt()` request post-processor with a `roles` claim:

```java
mockMvc.perform(get("/orders").with(jwt().jwt(b -> b.claim("roles", List.of("orders:read")))))
        .andExpect(status().isOk());
```

## Local dev notes

No Docker, no network. Depends on the security capability's `CurrentUserAccessor` bean being
present at runtime (pair with `platform-starter-security`).
