# The Platform Security Model

> Authentication, authorization, secrets, transport, and audit as one coherent story rather than five
> capabilities.

Security is not a capability. It is a property that emerges — or fails to — from decisions spread
across [core](../chapters/01-core.md), [errors](../chapters/02-errors-validation.md),
[security](../chapters/05-security-authz.md), [restclient](../chapters/06-restclient-resilience.md),
[storage and files](../chapters/11-storage-files.md), [audit](../chapters/12-audit.md), and
[flags](../chapters/13-ratelimit-flags.md).

This chapter assembles them into one picture, states plainly what the platform does **not** do, and
gives you a review checklist.

---

## 1. The principles

Five, and every specific control below follows from one of them.

**1. Deny by default.** A new endpoint is protected the day it is written, not the day someone
remembers. Allow-by-default fails *silently*, months later; deny-by-default fails *loudly*, on the
developer's own machine.

**2. Never trust the client.** Not the extension, not the `Content-Type`, not the filename, not the
declared size, not `X-Forwarded-For`, not a correlation id. All are attacker-controlled.

**3. Defence in depth where the blast radius justifies it.** Storage keys are validated twice
— once provider-independently, once by resolving the path and checking containment — because the
consequence of a miss is arbitrary file write.

**4. Fail in the direction that costs less.** Authorization fails closed; a rate limiter fails open.
Neither is a house style; each was chosen from what a wrong answer costs.

**5. Make the security decision visible.** `@RequiresPermission` next to the method, not an `if` in
the body. `mode: disabled` explicit, never implied by a profile. A permitted path in one list, not
scattered across matchers.

---

## 2. The layers, end to end

```
   client
     |
     |  TLS  (terminated upstream — the platform assumes it, does not provide it)
     v
   +---------------------------------------------------------------+
   |  CorrelationIdFilter            HIGHEST_PRECEDENCE            |
   |    a typed, validated id — even on requests that fail auth     |
   +---------------------------------------------------------------+
     |
     v
   +---------------------------------------------------------------+
   |  Rate-limit filter (opt-in)     429 + Retry-After              |
   +---------------------------------------------------------------+
     |
     v
   +---------------------------------------------------------------+
   |  Spring Security filter chain                                  |
   |    stateless - CSRF off (no ambient credentials)               |
   |    security headers - JWT resource server                      |
   |    SecurityCustomizers  <- may OPEN paths, cannot blanket-permit|
   |    permit-paths                                                |
   |    anyRequest().authenticated()                                |
   |                                                                |
   |    401/403 written HERE as RFC-9457 — advice cannot reach them  |
   +---------------------------------------------------------------+
     |
     v
   +---------------------------------------------------------------+
   |  DispatcherServlet -> controller                               |
   |    @Valid          input shape, @SafeText control chars        |
   |    @RequiresPermission   401 vs 403, correctly distinguished   |
   |    @RateLimited    per-operation quota                         |
   |    @Idempotent     duplicate effects                           |
   |    @Audited        who did what, success AND failure           |
   +---------------------------------------------------------------+
     |
     v
   +---------------------------------------------------------------+
   |  Outbound: token relay (guarded), correlation propagation      |
   |  Storage:  key validation x2, magic-byte sniff, sanitised names|
   +---------------------------------------------------------------+
```

Two ordering facts carry most of the weight:

- **Correlation is outermost**, so a rejected authentication still produces a traceable log line.
  Most hand-rolled implementations put the correlation filter after security and lose exactly the
  population an incident is about.
- **401 and 403 are produced before `DispatcherServlet`**, so `@RestControllerAdvice` provably cannot
  reach them. The platform installs a dedicated entry-point and denied-handler pair; without that, the
  two statuses clients most need to parse are the two with no machine-readable body.

---

## 3. Authentication

**Your service is a resource server, never an authorization server.** It never sees a password. It
receives `Authorization: Bearer <jwt>`, verifies the signature against the IdP's published keys
(cached, so no per-request IdP call), checks issuer/audience/expiry, and reads the claims.

Configuration uses **Spring Security's own** property namespace — `issuer-uri` is preferred over
`jwk-set-uri` because it discovers the key set *and* validates the issuer claim.

### `CurrentUser`, and why it matters for security specifically

Five capabilities depend on identity: [audit](../chapters/12-audit.md) (the actor),
[data](../chapters/08-data.md) (audit columns), [flags](../chapters/13-ratelimit-flags.md) (targeting),
[restclient](../chapters/06-restclient-resilience.md) (token relay), and authorization (the principal).

All five depend on `CurrentUser`, not on `Jwt`. The security consequence: changing IdP, adding opaque
token introspection, or altering the claim shape is **one bean**, not a fleet-wide refactor touching
every service — and a fleet-wide refactor of security-critical code is exactly where mistakes happen.

!!! warning "Everything downstream inherits the strength of this authentication"
    `created_by` on a row, `actor` on an audit event, and a per-user rate limit are all only as
    trustworthy as the token that produced them. A weak or unvalidated issuer does not just weaken
    authentication — it silently weakens your audit trail and your data provenance.

### The `mode: disabled` guard

Security is disabled only by an explicit property. **No profile, no environment, no convenience flag.**

That defends against a well-documented breach pattern: a `local` profile reaching production through a
misconfigured environment variable, taking authentication with it. The platform makes that impossible
to express.

!!! warning "`mode: disabled` also silently disables authorization"
    Both `platformSecurityFilterChain` and `currentUserAccessor` carry the mode condition. Without a
    `CurrentUserAccessor` bean, the authz advisor's `@ConditionalOnBean` fails and **every
    `@RequiresPermission` becomes inert** — the methods run unprotected. One property, two controls
    gone.

---

## 4. Authorization

`@RequiresPermission("orders:read")` sits next to the method it protects, so the decision is reviewable
in a diff. Scattered `if` statements are easy to omit and impossible to audit.

Evaluation is pluggable: the default reads a configurable claim off `CurrentUser`, and multiple
providers compose with **any-grant-wins** — which is how you migrate to a central entitlement service
without editing a single annotated method.

**401 versus 403 is distinguished correctly**: unauthenticated throws
`InsufficientAuthenticationException` (401); authenticated-but-unpermitted throws `AccessDeniedException`
(403). Conflating them makes clients retry authentication loops against permanent denials.

!!! warning "Three ways authorization silently does not apply"
    1. **No `CurrentUserAccessor` bean** → the advisor is never created. Methods run unprotected.
    2. **Self-invocation** → `this.method()` bypasses the proxy. A security bypass, not a performance
       one.
    3. **A cache hit above the check** → `@Cacheable` above `@RequiresPermission` returns the value
       without evaluating the permission.

    None of the three produces an error. All three are covered in
    [Chapter 5](../chapters/05-security-authz.md) and
    [Chapter 9](../chapters/09-cache-redis.md); they are repeated here because they are the security
    failures most likely to reach production.

---

## 5. Input

| Control | Defends against | Chapter |
|---|---|---|
| `@SafeText` | Log injection, terminal-escape smuggling | [2](../chapters/02-errors-validation.md) |
| `CorrelationId` format validation | Header-injected content reaching the log store | [1](../chapters/01-core.md) |
| `@Valid` on request bodies | Malformed input reaching the domain | [2](../chapters/02-errors-validation.md) |
| Magic-byte sniffing | Renamed executables passing upload validation | [11](../chapters/11-storage-files.md) |
| `SafeFilename.sanitize` | Path traversal on write, header injection on read | [11](../chapters/11-storage-files.md) |
| `KeyValidator` + path containment | Storage keys escaping their container | [11](../chapters/11-storage-files.md) |
| Servlet multipart limits | Oversized-upload DoS, before buffering | [11](../chapters/11-storage-files.md) |

Two of these are worth restating because the naive version is so plausible:

**A newline in a single-line text field is a forged log entry.** An attacker who can put
`\n2026-08-04 10:00:00 INFO Payment approved` into a remarks field has written a line your log store
will index as its own event. `@SafeText` rejects control characters at the boundary.

**Allow-list content types, never deny-list.** A deny-list of dangerous formats is unbounded and always
incomplete. An allow-list is finite and reviewable. This is deny-by-default applied to content.

!!! warning "What input validation does not cover"
    `@SafeText` is **not** an XSS defence — `<script>alert(1)</script>` contains no control characters.
    XSS is prevented by **output encoding** at the consumer. And magic-byte sniffing is **not**
    antivirus: a polyglot file, or a macro document, passes.

---

## 6. Output

The platform's error handling is a security control, and the design is deliberate:

- **Unmapped exceptions never leak.** A generic 500 detail; the real message — which routinely carries
  SQL, hostnames, and file paths — goes only to the correlated log.
- **`rejectedValue` is redacted** for field names containing `password`, `secret`, or `token`.
- **Stack traces are off by default**, and the property name says what it is.

!!! warning "Three ways sensitive data still escapes through an error response"
    1. **Mapped exception messages are echoed verbatim.** `new NotFoundException(code, "user " + email
       + " not found")` puts an email in a response body and confirms account existence.
    2. **Constraint messages interpolating `{value}`** leak the rejected value — redaction covers
       `rejectedValue`, not messages.
    3. **A `ProblemDetailCustomizer`** that adds `source.toString()` re-opens everything the catch-all
       closed.

    Write `detail` as if it will be screenshotted into a ticket. It will be.

Related: never return JPA entities from controllers — it serialises internal columns and publishes them
in your [OpenAPI](../chapters/04-openapi.md) document.

---

## 7. Data in transit and at rest

### Outbound

**The token relay is destination-blind.** If the current request is authenticated, the bearer token is
attached to **every** outbound call from **every** factory-built client — including one pointed at a
third party.

That is the sharpest security edge in the platform, and it is existing behaviour rather than something
the book introduced. [Chapter 6](../chapters/06-restclient-resilience.md) §5.5 covers it in full.

!!! warning "The control, restated because it matters"
    If a client calls outside your trust boundary, either build it **without**
    `PlatformRestClientFactory`, or add a customizer that strips `Authorization` for that client name.
    Adopt a naming convention (`external-*`) that makes the boundary visible in the client name, and
    add a test that fails if such a client ever sends an `Authorization` header.

Also: `bodySnippet()` on a `RemoteCallException` contains the remote's response body. If they return
PII in an error, it is now in your exception and your logs.

### At rest

| Where | What the platform does | What it does not |
|---|---|---|
| Object storage | Key validation, checksums, sanitised names | **No encryption, no bucket policy** — the backend's job |
| Database | Audit columns, value types | No column encryption |
| Cache | Key namespacing | **No encryption.** Redis is often unencrypted internally |
| Broker | Correlation headers | No payload encryption; the broker retains what you publish |
| Audit sink | The event | **No tamper-evidence** — that is the destination's job |

!!! warning "Checksums detect corruption, not tampering"
    An attacker who can rewrite a stored object can rewrite its `sha256` tag. For tamper-evidence you
    need object lock, versioning, or a signed log — properties of the store, not of the platform.

---

## 8. Secrets

`System.getenv` is **banned by the conformance rules**. Secrets arrive as ordinary Spring `${...}`
placeholders populated by Spring Cloud Vault.

The reasoning: environment variables leak into process listings, crash dumps, and child processes, and
cannot be rotated without a redeploy. A placeholder gives one auditable delivery path and central
rotation.

!!! warning "The archetype ships a placeholder issuer that validates nothing"
    The generated `application.yml` carries a placeholder `jwk-set-uri` so a fresh service boots
    offline. **Replace it before the service reaches any shared environment**, and check for it in
    review — it is the single most likely thing to be forgotten between "generated" and "deployed".

---

## 9. The operational surface

The platform exposes `health,info,platform,metrics,prometheus` over HTTP, and its security chain
permits **only** `health` and `info`. Everything else — including `/actuator/env`,
`/actuator/configprops`, `/actuator/metrics`, and `/actuator/platform` — requires authentication.

Two consequences to plan for:

- **Your Prometheus scraper needs credentials**, or a network-level exception.
- **`/actuator/platformflags` writes.** `POST` and `DELETE` change application behaviour at runtime.
  Decide deliberately whether it is exposed and who can reach it.

!!! warning "api-docs and swagger are permitted by default"
    An unauthenticated caller can read your full API surface. That is usually right internally and
    is reconnaissance at an external boundary. Decide, rather than inherit.

---

## 10. Audit as a security control

Two properties make the audit trail useful as *evidence* rather than as logging:

- **Failures are recorded.** A failed privileged operation is the most security-relevant record there
  is. A trail of only successes is blind to exactly the attack you would investigate.
- **`correlationId` joins it to everything else.** An investigation starts at an audit record and
  follows the id into logs, error responses, and published messages.

!!! warning "The trail is best-effort, and a compliance conversation needs the accurate version"
    `record()` never blocks and never throws. A full queue **sheds** with a `DC-AUDIT-0500` warning.
    The worker is a **daemon** thread, so the queue tail may be lost at shutdown. And the sink chain
    falls back silently — a service that quietly used the log sink when the regime required a database
    is not compliant, it just does not know yet.

    [Chapter 12](../chapters/12-audit.md) gives the knobs to move toward completeness, and shows how to
    assert the selected sink at startup so the gap becomes a deployment failure.

---

## 11. What the platform does not do

Stating this plainly is more useful than any control listed above.

| Not provided | Whose job |
|---|---|
| **TLS termination** | Ingress, service mesh, or load balancer |
| **Mutual TLS between services** | Service mesh |
| **Encryption at rest** | The datastore, object store, or broker |
| **Secret storage** | Vault. The platform consumes placeholders |
| **Antivirus / content scanning** | A scanning service. Magic bytes are not antivirus |
| **Tamper-evident audit storage** | The sink's destination |
| **Bucket and database access policies** | Your infrastructure configuration |
| **Certificate pinning** | Standard JDK trust store by default |
| **An authorization server** | Your IdP |
| **Network segmentation, WAF, DDoS protection** | The perimeter |
| **Per-event authorization on a broker** | The broker, at destination granularity |

!!! success "The platform's honest scope"
    It makes the *application-layer* decisions consistent and hard to get wrong: authenticated by
    default, permissions next to the code, input validated at the boundary, output that does not leak,
    identity propagated safely, and actions recorded. Everything above is infrastructure, and the
    platform assumes it rather than replacing it.

---

## 12. Review checklist

Use this on a pull request or before a production deployment.

**Authentication**

- [ ] `issuer-uri` points at the right IdP for this environment — **not** the archetype placeholder
- [ ] `mode` is `resource-server`. Nothing sets `disabled`
- [ ] No `SecurityFilterChain` bean has been declared to make a small change
- [ ] `permit-paths` is unchanged, or a `SecurityCustomizer` was used instead of replacing the list

**Authorization**

- [ ] Every state-changing endpoint has `@RequiresPermission`, or a documented reason
- [ ] `requiresPermissionAdvisor` exists in the running context
- [ ] No annotated method is called via `this.` from within its own bean
- [ ] No `@Cacheable` sits above a `@RequiresPermission`
- [ ] Tests cover all three states: 401, 403, 200

**Input**

- [ ] Request bodies are `@Valid`; single-line text fields carry `@SafeText`
- [ ] Uploads validate by magic bytes against a **narrowed** allow-list
- [ ] Filenames are sanitised, and used as a *component* of a generated key
- [ ] `max-file-size` matches what the business actually accepts

**Output**

- [ ] `include-stacktrace` is `false` outside the `local` profile — asserted by a test
- [ ] No mapped exception message contains PII or confirms existence
- [ ] No constraint message interpolates `{value}` on a sensitive field
- [ ] Controllers return response records, not entities

**Outbound**

- [ ] Every client calling outside the trust boundary either avoids the factory or strips `Authorization`
- [ ] A test fails if an `external-*` client sends a bearer token
- [ ] Base URLs are treated as security-relevant configuration

**Secrets**

- [ ] No `System.getenv` — `PlatformConformanceTest` passes
- [ ] Every secret is a `${...}` placeholder from Vault

**Operational surface**

- [ ] The metrics scraper is authenticated, or excepted at the network layer
- [ ] `platformflags` exposure is a deliberate decision
- [ ] api-docs/swagger exposure is deliberate at an external boundary

**Audit**

- [ ] Privileged operations are `@Audited`, with a resource derived from **arguments**
- [ ] The selected sink is **asserted** at startup, not assumed
- [ ] `DC-AUDIT-0500` has an alert on first occurrence
- [ ] Audit retention is configured on the destination

**Multi-instance**

- [ ] Rate limits that must be cluster-wide use the Redis provider
- [ ] Scheduled work uses `@LockedSchedule` with a locking provider present
- [ ] The `@LockedSchedule` unlocked warning does not appear in a multi-replica environment

---

**Next:** [Observability Strategy](observability-strategy.md) — how the correlation id in this chapter
becomes an answer.

**Related:** [Chapter 1 — Core](../chapters/01-core.md) ·
[Chapter 2 — Errors and Validation](../chapters/02-errors-validation.md) ·
[Chapter 5 — Security and Authorization](../chapters/05-security-authz.md) ·
[Chapter 6 — REST Client and Resilience](../chapters/06-restclient-resilience.md) ·
[Chapter 11 — Storage and Files](../chapters/11-storage-files.md) ·
[Chapter 12 — Audit](../chapters/12-audit.md)

[Back to the book](../index.md)
