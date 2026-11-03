# Security

Only callers holding a token your identity provider issued for this service get in. Everyone else gets a 401 that says nothing about why.

## What it solves

A resource server that checks a token's signature but not who it was issued by or for will accept a token minted for another service. One that fetches keys for every unknown `kid` can be made to hammer the identity provider. One permit rule too wide opens the whole service. The platform checks issuer, audience, expiry and subject, limits key fetches, keeps working through a key-server outage, and refuses to start when a setting would open everything.

## When to use it

For every HTTP service: a generated service has it already. Not for browser sessions, opaque tokens, mTLS or several issuers: see [`open-issues.md`](open-issues.md).

## Minimal setup

A generated service has the starter and placeholders. Supply the three values your identity provider gives you, outside the jar:

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: https://idp.example/realms/main
  jwk-set-uri: https://idp.example/realms/main/protocol/openid-connect/certs
  audiences: orders-service
```

Without any of the three, the service does not start, and the message names the missing key.

Read the caller in your code without touching JWT types:

```java
@RestController
class MyOrders {
    private final CurrentUserAccessor users;
    private final OrderRepository orders;

    MyOrders(CurrentUserAccessor users, OrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    @GetMapping("/orders/mine")
    List<Order> mine() {
        CurrentUser user = users.currentUser().orElseThrow();
        return orders.findByOwner(user.subject());
    }
}
```

## Walkthrough: what a request gets

| The request carries… | Answer |
|---|---|
| A valid token for this audience | Your controller runs |
| No token, a garbage token, a forged signature, `alg=none`, an expired token, the wrong issuer or audience, no `sub` | The same 401 `DC-SEC-0401`, and `WWW-Authenticate: Bearer` with no reason |
| A valid token, but the key server is down and the token's key is not cached | 503 `DC-SEC-0503`, `retryable: true` |
| A valid token without the permission a method asks for | 403 `DC-SEC-0403` |

Only `/actuator/health/liveness` and `/actuator/health/readiness` are open. `info`, `prometheus`, the other health groups, OpenAPI and swagger need a token.

## Permissions

Add `platform-starter-security-authz`, then:

```java
@RequiresPermission("orders:approve")
@PostMapping("/orders/{id}/approval")
void approve(@PathVariable String id) { ... }
```

`orders:approve` is granted by a `roles` claim entry, a Keycloak realm role, or a role of **this service's** Keycloak client (`resource_access.<spring.application.name>`). A role of another client grants nothing. On a type, it applies to every method, including inherited ones; a method's own annotation wins over its type's.

## Opening a path

```yaml
dc.platform.security.permit-paths: /actuator/health/liveness,/actuator/health/readiness,/public/**
```

Or, for more than a path, a `SecurityCustomizer` bean. Either way, a rule that opens every path, for any one method, fails startup.

## Calling another service with the caller's token

Off by default: whoever receives a token can replay it. Name the clients that need it:

```yaml
dc.platform.restclient.token-relay.clients: inventory
```

## Next

- [`commands.md`](commands.md): reproduce the checks. [`verification.md`](verification.md): what was measured, and its limits.
- [`operations.md`](operations.md): the key-server outage, metrics, alerts. [`troubleshooting.md`](troubleshooting.md). [`open-issues.md`](open-issues.md).
- Every property: [`docs/modules/security.md`](../../modules/security.md), [`docs/modules/authz.md`](../../modules/authz.md).
