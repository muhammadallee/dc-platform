# Chapter 4 — OpenAPI: The Published Contract

> **Capabilities covered:** `openapi`
>
> The document your consumers generate clients from, and how the platform keeps it honest.
>
> **Starter:** `platform-starter-openapi` · **Reference:** [modules/openapi.md](../../modules/openapi.md)

---

This is a short chapter, deliberately. The capability is small — it contributes two beans and four
properties — but the thing it produces is the most externally visible artifact your service has. What
makes it worth its own chapter is one design decision that most OpenAPI setups get wrong, covered in
§2.3.

---

## 1. Introduction and Business Value

### The problem it solves

Someone has to integrate with your service. What do they read?

Without a machine-readable specification the answer is: a wiki page that was accurate two releases
ago, a Postman collection someone exported once, and a Slack thread. Integration becomes
reverse-engineering, and every refactor silently breaks a consumer who was relying on something you
did not know was load-bearing.

OpenAPI solves this by making the contract an artifact the *code* produces, so it cannot drift from
the code. Consumers generate typed clients from it. Gateways validate against it. Contract tests
assert on it.

### What the platform adds over plain springdoc

Adding `springdoc-openapi` to a Spring Boot service gets you a document. The platform adds three
things on top, and the third is the one that matters.

**Identity for free.** Title and version default from `spring.application.name` and
`info.app.version`. Without that default, the fleet fills up with documents titled "OpenAPI
definition v1.0", which identify nothing and cannot be told apart in a developer portal.

**The auth scheme, documented and correct.** Bearer JWT is declared as a security scheme and applied
as a requirement on every operation, matching what the [security](05-security-authz.md) capability
actually enforces. A generated client therefore wires credentials correctly on the first attempt
instead of returning 401s until someone reads the security chapter.

**The error model on every operation.** This is the big one. A plain springdoc document describes
your 200 responses in loving detail and says nothing at all about failure — so a generated client has
no error type and treats every non-2xx as an opaque blob. The platform appends the `ProblemDetail`
schema and the seven statuses the platform can actually produce to *every* operation, automatically.

### Why that last point is not a nicety

Consider what a consumer does with a generated client when there is no error model:

```java
try {
    orderClient.createOrder(request);
} catch (RestClientResponseException e) {
    // now what? parse e.getResponseBodyAsString() and hope
}
```

They cannot distinguish "the order already exists" (409, do not retry, show a message) from "the
database is down" (500, retry with backoff). So they write a string match against the response body,
which breaks the first time anyone rewords a message.

With the error model in the spec, the generated client has a `ProblemDetail` type with a `code` field
— the same stable code from [Chapter 2](02-errors-validation.md) — and the consumer can branch on it.

The alternative to appending it automatically is asking every team to document seven responses on
every operation by hand. That is pure duplication, it is skipped under deadline pressure, and it is
wrong the moment the platform's error contract changes.

### Impact of absence

| Without this capability | What actually happens |
|---|---|
| No published spec | Consumers integrate from Slack and reverse-engineering; refactors break them silently |
| No default identity | A portal full of "OpenAPI definition v1.0" entries |
| No documented auth scheme | Generated SDKs omit credentials; consumers guess, then file a support ticket |
| No error model | Clients treat all non-2xx as opaque and match on message strings |
| Hand-documented error responses | Duplicated across every operation in every service, skipped under pressure, stale after the first contract change |

---

## 2. Core Concepts and Underlying Principles

### 2.1 Code-first versus contract-first

Two philosophies, and the platform picks one.

**Contract-first** writes the OpenAPI YAML by hand, then generates server stubs from it. The spec is
authoritative; the code conforms. Strong for public APIs with external consumers and formal review,
and it lets the contract be designed before any code exists.

**Code-first** derives the spec from the running application's controllers, types, and annotations.
The code is authoritative; the spec is a projection.

The platform is **code-first**, for one reason that outweighs the others in an internal estate: a
hand-written spec drifts. It is a second artifact that must be updated in lockstep with the code, and
under deadline pressure it is the one that does not get updated. A derived spec cannot be wrong about
the shape of a response, because it is computed from the type that produces it.

!!! note "The platform's automatic error responses are the contract-first bit"
    There is one place the platform *asserts* rather than derives: the seven error statuses. Those
    cannot be derived from a controller, because they are produced by an exception handler somewhere
    else entirely. So the platform declares them — truthfully, because every service on the platform
    maps errors through the same handler. This is the narrow case where asserting beats deriving.

### 2.2 How springdoc actually builds the document

Understanding the pipeline explains every extension point in this chapter:

```
  GET /v3/api-docs
        |
        v
  springdoc scans the RequestMappingHandlerMapping
        |    every @RequestMapping method becomes an Operation
        |    every parameter and return type becomes a Schema
        v
  springdoc finds the OpenAPI bean            <-- platformOpenApi
        |    info, servers, security schemes come from here
        v
  springdoc applies every OpenApiCustomizer   <-- platformProblemDetailOpenApiCustomizer
        |    each one mutates the assembled document
        v
  serialise to JSON
```

Two facts follow, and both matter operationally:

- **The document is built per request, not at startup.** Nothing about it exists until someone asks
  for it.
- **Customizers see the fully-assembled document**, so they can add to every operation without
  knowing anything about your controllers.

### 2.3 Why generation happens at request time — the fail-soft property

This is the design decision worth the chapter.

Documentation must never be able to break request handling. If the document were assembled at startup
and a customizer threw, the application would fail to boot. A *documentation* bug would become a
*production outage* — which is an absurd trade, and one that plenty of hand-rolled setups make.

Because springdoc assembles at request time, a broken customizer breaks exactly one thing: the
response to `GET /v3/api-docs`. Your API keeps serving traffic. The openapi capability defines **no
error codes at all**, for the same reason [logging](03-logging-observability.md) does not — some
capabilities must not be able to take the service down.

!!! success "Best practice — the fail-soft posture generalises"
    When you add a capability that produces *observability or documentation output*, ask what happens
    if it throws. If the answer is "the service stops", restructure it. Nothing that merely *describes*
    the system should be able to stop it.

The cost is real and worth naming: the first request to `/v3/api-docs` after a restart is slow, and
a broken customizer is only discovered when someone fetches the document. §5.2 turns that into a
monitored check rather than a surprise.

### 2.4 `putIfAbsent` — the one line that makes automation safe

The customizer appends its seven responses with `putIfAbsent`, not `put`:

```java
for (String status : DOCUMENTED_STATUSES) {
    responses.putIfAbsent(status, new ApiResponse().description(...).content(problemContent));
}
```

That single word is what makes automatic documentation acceptable. If a controller already documents
409 — with a domain-specific description, or a different schema entirely — the platform leaves it
alone. Automation fills gaps; it never overwrites intent.

Without `putIfAbsent`, the platform would be silently rewriting hand-written documentation, and the
capability would have to be turned off by any team that wanted to say anything specific. That is how
useful automation turns into a thing teams disable.

### 2.5 The seven statuses, and why exactly those

| Status | Comes from | Chapter |
|---|---|---|
| 400 | Validation failure, `DC-CORE-0400` | [2](02-errors-validation.md) |
| 401 | Security chain — unauthenticated | [5](05-security-authz.md) |
| 403 | Security chain — authenticated but not permitted | [5](05-security-authz.md) |
| 404 | `NotFoundException` | [2](02-errors-validation.md) |
| 409 | `ConflictException`, and `@Idempotent` duplicates | [2](02-errors-validation.md), [10](10-coordination.md) |
| 422 | `BusinessException` default | [2](02-errors-validation.md) |
| 500 | Anything unmapped, `DC-CORE-0500` | [2](02-errors-validation.md) |

This is not a generic list of HTTP statuses — it is exactly the set the platform's exception handling
can produce. Every one of them is documented truthfully for every service, because every service maps
errors through the same handler.

!!! note "429 is deliberately absent"
    [Rate limiting](13-ratelimit-flags.md) is opt-in, so a 429 is not something *every* operation can
    return. Documenting it universally would be a lie. If you enable rate limiting, document 429 on the
    affected operations yourself — and `putIfAbsent` means the platform will not fight you.

---

## 3. Feature Reference

### 3.1 What the capability contributes

The openapi capability has **no public API types**. It contributes beans and configuration.

| Bean | Type | Purpose |
|---|---|---|
| `platformOpenApi` | `OpenAPI` | Info (title, version) and the bearer-JWT security scheme |
| `platformProblemDetailOpenApiCustomizer` | `OpenApiCustomizer` | Appends the `ProblemDetail` schema and the seven responses |
| `openapiCapabilityDescriptor` | `CapabilityDescriptor` | Reports `openapi[ACTIVE] (springdoc)` |

### 3.2 Endpoints

| Path | Serves | Controlled by |
|---|---|---|
| `/v3/api-docs` | The OpenAPI document as JSON | `springdoc.api-docs.enabled` |
| `/v3/api-docs.yaml` | The same document as YAML | Same |
| `/swagger-ui.html` | Swagger UI | `springdoc.swagger-ui.enabled` |

!!! note "Those are springdoc properties, not platform ones"
    They have no `dc.platform.` prefix because the platform does not own them. This is the convention
    throughout: where a third-party library already has a good property, the platform uses it rather
    than inventing a parallel dialect. See [Primer 1](../primer/01-spring-boot.md) §4.

### 3.3 The `ProblemDetail` schema

Registered once under `#/components/schemas/ProblemDetail` and referenced by all seven responses:

| Property | Type | Format |
|---|---|---|
| `type` | string | |
| `title` | string | |
| `status` | integer | |
| `detail` | string | |
| `instance` | string | |
| `code` | string | |
| `correlationId` | string | |
| `timestamp` | string | `date-time` |

!!! warning "`errors[]` is not in the schema"
    The validation `errors[]` array from [Chapter 2](02-errors-validation.md) appears in real 400
    bodies but is **not** declared here. A generated client will not have a typed accessor for it, and
    a strict validator could reject a real response as having an undeclared property. If your consumers
    depend on field-level validation detail, document the 400 response yourself on the affected
    operations — `putIfAbsent` will respect it. See §6.3.

### 3.4 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.openapi.enabled` | Boolean | `true` | Kill switch for the platform layer. springdoc still generates a document from your own beans | To take full manual control of the document |
| `dc.platform.openapi.title` | String | `""` → `spring.application.name` → `service` | Document title | When the human-facing name differs from the Spring application name |
| `dc.platform.openapi.version` | String | `""` → `info.app.version` → `dev` | Document version | When the API version is versioned independently of the build |
| `dc.platform.openapi.security-scheme` | `BEARER_JWT` \| `NONE` | `BEARER_JWT` | The auth scheme documented on every operation | `NONE` for a genuinely public API |

!!! warning "`version` falls back to `dev`, and `dev` in production is a smell"
    `info.app.version` is populated by Boot's build-info generation, which `platform-service-parent`
    configures. If your document says `dev` in a deployed environment, build-info is not being
    generated — which also means `/actuator/info` is missing the build metadata that diagnostics rely
    on. See [Chapter 14](14-testing-dx.md).

### 3.5 Auto-configuration

| Class | Activates when | Contributes | Backs off when |
|---|---|---|---|
| `PlatformOpenApiAutoConfiguration` | springdoc's `OpenAPI` and `OpenApiCustomizer` on the classpath **and** `openapi.enabled != false` | The three beans above | You define an `OpenAPI` bean (replaces identity + scheme wholesale), or a bean named `platformProblemDetailOpenApiCustomizer` (replaces the error appending) |

!!! note "Two independent back-off points"
    Defining your own `OpenAPI` bean replaces the identity and security scheme but **leaves the
    error-response customizer running** — those are separate beans with separate conditions. That is
    usually what you want: full control of the document's identity, while still getting the error model
    for free.

### 3.6 Extension points

| Extension | How | Effect |
|---|---|---|
| Replace identity and security scheme | Declare an `OpenAPI` bean | Yours wins; the error customizer still runs |
| Add documentation to every operation | Declare an `OpenApiCustomizer` bean with **any other name** | Runs alongside the platform's |
| Replace the error appending | Declare a bean named `platformProblemDetailOpenApiCustomizer` | The platform's backs off |
| Document a public API | `security-scheme: NONE` | No scheme, no requirement |
| Document one operation properly | Standard `@Operation`, `@ApiResponse` | `putIfAbsent` respects it |

---

## 4. How-to Guide

### 4.1 Add the capability

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-openapi</artifactId>
</dependency>
```

Nothing else. Boot the service:

```bash
curl -s localhost:8080/v3/api-docs | jq '.info, (.paths | keys)'
open http://localhost:8080/swagger-ui.html
```

Every operation already documents seven error responses and requires bearer auth.

### 4.2 Set a human-facing title

The default is `spring.application.name`, which is usually right — it is the name in every log line
and every meter tag. Override when the portal needs something a person would recognise:

```yaml
dc:
  platform:
    openapi:
      title: "Customs Declarations API"
      version: "2024-11"
```

!!! success "Best practice — version the API, not the build"
    `info.app.version` is the *build* version and changes on every release, including releases that do
    not change the contract. If consumers care about API versioning, set `openapi.version` to something
    that changes when the *contract* changes — a date, or a major number.

### 4.3 Document one operation properly

The platform's defaults are a floor. Improve any operation and the platform steps aside:

```java
@Operation(summary = "Cancel an order",
           description = "Cancels an order that has not yet shipped.")
@ApiResponse(responseCode = "200", description = "Cancelled")
@ApiResponse(responseCode = "409",
             description = "Order already shipped — code DC-ORDER-0021",
             content = @Content(mediaType = "application/problem+json",
                                schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
@PostMapping("/orders/{id}/cancel")
Order cancel(@PathVariable String id) { ... }
```

Your 409 description survives. The other six statuses are still appended.

!!! success "Best practice — document the codes, not just the statuses"
    "409 Conflict" tells a consumer nothing they could not guess. "409 — order already shipped, code
    `DC-ORDER-0021`" tells them exactly what to branch on. The codes are the stable part of your error
    contract; put them where consumers will read them.

### 4.4 Add a customizer of your own

```java snippet:book-04-openapi-customizer
@Configuration
class ApiDocumentationConfiguration {

    @Bean
    OpenApiCustomizer supportContactCustomizer() {
        return openApi -> openApi.getInfo()
                .description("Support: customs-platform@example.gov.ae")
                .termsOfService("https://example.gov.ae/api-terms");
    }
}
```

Any bean name other than `platformProblemDetailOpenApiCustomizer` runs *alongside* the platform's.

!!! warning "A customizer that throws breaks the document, not the service — but it does break the document"
    The fail-soft property protects your API, not your documentation. Guard everything:
    `openApi.getInfo()` is non-null only because the platform's `OpenAPI` bean set it. If someone
    replaces that bean, this customizer NPEs and `/v3/api-docs` starts returning 500.

### 4.5 Document a public API

```yaml
dc:
  platform:
    openapi:
      security-scheme: NONE
```

No scheme in `components`, no security requirement on operations.

!!! warning "This changes documentation, not enforcement"
    `security-scheme: NONE` does not make anything public. The [security](05-security-authz.md) chain
    still authenticates every request. If the API really is public you must *also* permit the paths —
    and the two settings living in different places is deliberate: documentation must never be able to
    grant access.

### 4.6 Take full control

```java
@Bean
OpenAPI customOpenApi() {
    return new OpenAPI()
            .info(new Info().title("Customs Declarations API").version("2024-11"))
            .servers(List.of(new Server().url("https://api.example.gov.ae")));
}
```

The platform's `OpenAPI` bean backs off. The error customizer keeps running — so you get full control
of identity and servers *and* keep the error model.

To also drop the error appending, name a bean `platformProblemDetailOpenApiCustomizer`.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local, dev, test | Defaults | Swagger UI is genuinely useful during development |
| Production | Consider `springdoc.swagger-ui.enabled: false` | See the discussion below |
| Production | Keep `/v3/api-docs` **on** | Gateways, portals, and contract tests consume it |
| All | `info.app.version` populated by build-info | Otherwise the document says `dev` |

**Should Swagger UI be on in production?** A real trade-off, and the answer depends on who your
consumers are.

*For:* internal consumers discover and try the API without a separate portal; support engineers have
a live reference during an incident.

*Against:* it is an additional attack surface, it makes API enumeration trivial, and it invites
"try it out" requests against production data.

The platform's default is on, because in an internal estate discoverability usually wins and the
security chain authenticates the UI anyway. If your service handles sensitive data or sits at an
external boundary, turn it off in production and publish the document to a portal instead.

!!! warning "Check what your security chain actually permits"
    The platform's default permit list includes the api-docs and swagger paths so the document is
    reachable. If your API is externally exposed, that means **an unauthenticated caller can read your
    full API surface**. That may be exactly right, or it may be reconnaissance you did not intend.
    Decide deliberately — see [Chapter 5](05-security-authz.md).

### 5.2 What to monitor

The document is not a runtime dependency, so there is nothing to alert on continuously. There is one
check worth automating.

| Check | How | Why |
|---|---|---|
| The document renders | `curl -f localhost:8080/v3/api-docs > /dev/null` in a smoke test | A broken customizer is otherwise only found when a human looks |
| It contains what it should | Assert `ProblemDetail` in `components.schemas` and `bearer-jwt` in `securitySchemes` | Catches a back-off you did not intend |
| The contract has not changed unexpectedly | Diff the document against the previous release in CI | Turns a breaking change into a review conversation |

!!! success "Best practice — diff the spec in CI"
    The single highest-value thing you can do with this capability. Fetch `/v3/api-docs` from a booted
    instance during the build, diff it against the committed copy from the last release, and fail the
    build on a removal or an incompatible type change. That converts "we broke a consumer" from a
    production incident into a pull-request comment. The platform's own
    [smoke matrix](../../examples.md) does the boot-and-fetch half; the diff is yours to add.

### 5.3 Troubleshooting

**`/v3/api-docs` returns 404.**

| Cause | Check |
|---|---|
| Starter missing | `mvn dependency:tree \| grep platform-starter-openapi` |
| springdoc disabled | `curl -s localhost:8080/actuator/env/springdoc.api-docs.enabled` |
| Wrong path | springdoc's path is configurable via `springdoc.api-docs.path` |
| Security is rejecting it | A 404 is unusual here — a 401 is the usual symptom. Check the permit list |

**`/v3/api-docs` returns 500.** A customizer threw. This is the fail-soft property working: your API
is fine, the document is not. The stack trace is in the log — look for `OpenApiCustomizer` in it. Most
common cause: a customizer dereferencing `openApi.getInfo()` after someone replaced the `OpenAPI` bean.

**Error responses are missing from operations.**

| Cause | Check |
|---|---|
| The capability is disabled | `dc.platform.openapi.enabled` |
| A bean named `platformProblemDetailOpenApiCustomizer` exists | `/actuator/beans` — yours replaced the platform's |
| The operation already declares that status | Working as intended. `putIfAbsent` never overwrites |

**The title is the Spring application name and I wanted something else.** Set
`dc.platform.openapi.title`. **The version says `dev`** — build-info is not being generated; see §3.4.

**Operations show no auth requirement.** `security-scheme: NONE` is set, or an `OpenAPI` bean replaced
the platform's without adding a scheme.

**A schema is missing or wrong.** That is springdoc deriving from your types, not the platform. Common
causes: a raw generic type, a `Map<String, Object>` return, an interface return type with no
implementation hint. Annotate with `@Schema`.

### 5.4 Scaling and performance

The document is built **per request**, and building it means walking every handler mapping and
resolving every schema. On a service with hundreds of endpoints that is tens to hundreds of
milliseconds.

That is fine, because nothing in the request path touches it — but two consequences are worth knowing:

- **Do not put `/v3/api-docs` in a health check or a load-balancer probe.** It is the most expensive
  endpoint the service has, and hitting it every few seconds is pure waste. Use
  [`/actuator/health`](03-logging-observability.md).
- **The first fetch after a restart is the slowest.** springdoc caches the assembled document by
  default; a cache-busting query parameter defeats that.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| The document is a map of your attack surface | Every path, parameter, and type. Reconnaissance value is real |
| Swagger UI is an additional surface | Historically a source of XSS advisories. Keep springdoc patched — the [BOM](../../reference/bom.md) manages the version |
| Documented ≠ enforced | `security-scheme` is documentation. Enforcement is [Chapter 5](05-security-authz.md) |
| Schemas can leak internal fields | springdoc derives from your types. A DTO with an internal audit field documents that field. Use `@Schema(hidden = true)` or a proper response type |
| Example values can leak real data | An `@Schema(example = "...")` copied from a production payload is a data leak in a public document |

!!! warning "Check your response types before publishing externally"
    The most common leak in a generated spec is not a path — it is a field. Returning a JPA entity
    directly from a controller documents every column, including the ones you added for internal
    bookkeeping. A dedicated response record fixes the leak *and* the coupling.

---

## 6. Deep Dive

### 6.1 Why the platform declares errors it cannot derive

Section 2.1 called this the one contract-first element. It is worth being precise about why it is
sound rather than convenient.

springdoc derives the document from what it can see: handler methods, their parameters, their return
types. It cannot see `PlatformExceptionHandler`, because an `@RestControllerAdvice` is not reachable
from a handler mapping. So the platform's error contract is structurally invisible to derivation.

The platform therefore asserts it — and the assertion is *true for every service on the platform*,
because the same advice handles errors in all of them. That universality is what makes the assertion
safe. If error handling were per-service, this customizer would be documenting a fiction.

!!! note "Which is also why disabling the errors capability makes the document lie"
    Set `dc.platform.errors.enabled=false` and your service stops producing `ProblemDetail` bodies —
    but the openapi capability keeps documenting them, because the two capabilities are independent by
    design and neither conditions on the other. The document becomes wrong. If you disable errors,
    disable the platform's error appending too.

### 6.2 Why the schema is hand-built rather than derived from `ProblemDetail.class`

The customizer constructs the schema field by field rather than pointing springdoc at Spring's
`ProblemDetail` type. That looks like duplication and is deliberate.

Spring's `ProblemDetail` carries the five RFC members and an open `properties` map. Deriving from it
would document `properties` as an untyped map — which is technically accurate and useless to a
consumer. The platform's extensions (`code`, `correlationId`, `timestamp`) live *in* that map, so
derivation would hide exactly the three fields consumers most need.

Hand-building flattens them into named, typed properties. The cost is that the schema and the
[`ProblemDetailFactory`](02-errors-validation.md) must be kept in step by hand — a real maintenance
seam, and the reason §5.2 recommends diffing the spec in CI.

### 6.3 The `errors[]` gap, and what to do about it

Following from §6.2: the hand-built schema declares eight properties and stops. A real 400 body also
carries `errors[]`, an array of `{field, message, rejectedValue}`.

The consequences, in order of how likely you are to hit them:

1. A generated client has no typed accessor for field-level validation detail. Consumers fall back to
   reading the raw body.
2. A strict schema validator — some gateways do this — may reject a real 400 as having an undeclared
   property.

If your consumers need it, declare the 400 response yourself on the operations that validate, with a
schema that includes `errors[]`. `putIfAbsent` guarantees the platform will not overwrite it.

### 6.4 Springdoc's caching, and the version that is not the version

Springdoc caches the assembled document after the first request. That is why `/v3/api-docs` is slow
once and fast thereafter — and why a customizer bug does not go away when you retry.

Separately, note the three "versions" in play, which are easy to conflate:

| Version | Source | Means |
|---|---|---|
| `info.version` in the document | `openapi.version` → `info.app.version` → `dev` | Your API's version |
| `openapi` field in the document | springdoc | The OpenAPI **specification** version (3.x) |
| `platform.version` meter tag | Jar manifest | Which platform train ([Chapter 3](03-logging-observability.md)) |

A consumer asking "what version are you on?" usually means the first. A tooling problem usually means
the second.

### 6.5 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Returning JPA entities from controllers | It is less code | Documents every column, couples the API to the schema. Use a response record |
| Assuming `security-scheme` enforces something | It reads like it should | It is documentation. Enforcement is [Chapter 5](05-security-authz.md) |
| Health-checking `/v3/api-docs` | It returns 200 and looks like a liveness check | It is the most expensive endpoint you have. Use `/actuator/health` |
| A customizer named `platformProblemDetailOpenApiCustomizer` by accident | Copy-paste from the docs | You silently replaced the platform's. Any other name runs alongside |
| Leaving `version: dev` in production | Nothing fails | build-info is not generated — `/actuator/info` is degraded too |
| Documenting 429 on every operation by hand | Rate limiting was enabled | Document it on the affected operations only, or it is a lie elsewhere |
| Disabling errors but not the error appending | They feel unrelated | The document keeps promising `ProblemDetail` bodies you no longer produce |
| `@Schema(example = ...)` copied from production | Convenient realistic data | It is a data leak in a published document |

---

## 7. Exercises and Hands-on Labs

Starting point: [`examples/example-golden-path`](../../examples.md), which has openapi, errors,
validation, and security wired together.

### Lab 1 — Basic: read your own contract

**Goal.** See what the platform published on your behalf.

**Steps.**

1. Boot the service and fetch `/v3/api-docs`. Pretty-print it.
2. Find `components.schemas.ProblemDetail`. List its eight properties.
3. Find `components.securitySchemes`. What type, scheme, and bearer format?
4. Pick any operation and list its documented responses. Count them.
5. Open `/swagger-ui.html` and use "Try it out" on an endpoint that returns 404. Compare the actual
   response body against the documented `ProblemDetail` schema — field by field.

**Expected outcome.** Seven error responses per operation without a single annotation. The real 404
body matches the schema exactly.

**Hints.**

- `curl -s localhost:8080/v3/api-docs | jq '.paths["/orders/{id}"].get.responses | keys'`
- Getting a 401 from Swagger UI? Authorize with a token first — the security requirement it documents
  is real.

**How to verify.** A `@PlatformTest` on a random port asserting the document contains both
`ProblemDetail` and `bearer-jwt`.

### Lab 2 — Intermediate: improve the contract without fighting the platform

**Goal.** Prove `putIfAbsent` behaves as documented, and add real value on top.

**Steps.**

1. Pick an operation that can return 409. Document it with `@ApiResponse`, a domain description, and
   the error code.
2. Fetch the document. Confirm your description survived and the other six statuses are still there.
3. Add an `OpenApiCustomizer` bean adding a `description` and contact details to `info`.
4. Now rename that bean to `platformProblemDetailOpenApiCustomizer` and fetch again. What happened to
   every operation's error responses? Rename it back.
5. Set `security-scheme: NONE`. Fetch, and confirm the scheme is gone. Then call a protected endpoint
   without a token. What status?

**Expected outcome.** Step 2 shows automation filling gaps without overwriting intent. Step 4 shows
the back-off is a name match — a genuine foot-gun worth having triggered once deliberately. Step 5
shows a 401 despite the document claiming no auth: documentation is not enforcement.

**Hints.**

- Step 4's back-off is `@ConditionalOnMissingBean(name = "platformProblemDetailOpenApiCustomizer")`.
  Name matching, not type matching.
- Step 5's contrast is the whole point. Write down which file would have to change to actually make
  the endpoint public.

**How to verify.** A test asserting your 409 description is present *and* that 400, 404, and 500 are
also present on that same operation.

### Lab 3 — Advanced: catch a breaking change before a consumer does

**Goal.** Build the CI check from §5.2, and use it to find a break you did not know you had made.

**Steps.**

1. Boot the service, save `/v3/api-docs` to `api-docs-baseline.json`, and commit it.
2. Make a change that is obviously breaking: rename a response field, or remove an endpoint.
3. Write a test that boots on a random port, fetches the document, and diffs it against the baseline.
   Fail on removed paths, removed properties, and changed types.
4. Run it. Confirm it fails, and that the message names the field.
5. Now make a change that is *not* breaking — add an optional field. Confirm the check passes.
6. Make the subtle one: change a field from optional to required. Does your check catch it? Should it?
7. Add a `@Schema(hidden = true)` to an internal field on a response type and confirm it disappears.

**Expected outcome.** A working contract guard. Step 6 is the interesting one — required-ness is a
breaking change for consumers and is easy to miss with a naive diff.

**Hints.**

- Compare parsed JSON trees, not strings. Key order is not stable and a text diff will be pure noise.
- Springdoc caches — fetch once per boot, and give each test its own context if you need a fresh one.
- Real tools exist for this (`openapi-diff` and friends). Writing a small one first teaches you which
  changes actually matter.

**How to verify.** The test fails on step 2's change, passes on step 5's, and you have a written
opinion about step 6.

---

## 8. Checklist / Quick Reference

**Add it**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-openapi</artifactId>
</dependency>
```

**Endpoints**

```bash
curl -s localhost:8080/v3/api-docs | jq          # the document
curl -s localhost:8080/v3/api-docs.yaml          # same, as YAML
open http://localhost:8080/swagger-ui.html       # the UI
```

**Properties**

| Key | Default |
|---|---|
| `dc.platform.openapi.enabled` | `true` |
| `dc.platform.openapi.title` | → `spring.application.name` |
| `dc.platform.openapi.version` | → `info.app.version` → `dev` |
| `dc.platform.openapi.security-scheme` | `BEARER_JWT` (or `NONE`) |
| `springdoc.api-docs.enabled` | springdoc's, not the platform's |
| `springdoc.swagger-ui.enabled` | springdoc's, not the platform's |

**Documented automatically on every operation**

400 · 401 · 403 · 404 · 409 · 422 · 500 — all `application/problem+json`, all referencing
`#/components/schemas/ProblemDetail`. Never overwritten if you declared the status yourself.

**Back-off points**

| Declare | Replaces |
|---|---|
| An `OpenAPI` bean | Identity and security scheme — error appending still runs |
| A bean named `platformProblemDetailOpenApiCustomizer` | The error appending |
| An `OpenApiCustomizer` with any other name | Nothing — runs alongside |

**Rules of thumb**

- The document is generated per request, so a bad customizer breaks documentation, never traffic.
- `security-scheme` documents auth; it does not enforce it.
- Never return JPA entities from controllers — you are publishing your schema.
- Never health-check `/v3/api-docs`. It is the most expensive endpoint you have.
- Document error **codes** in descriptions, not just statuses.
- Diff the spec in CI. It is the highest-value thing you can do with this capability.
- `version: dev` in a deployed environment means build-info is missing.
- If you disable the errors capability, disable the error appending too, or the document lies.

---

**Next:** [Chapter 5 — Security and Authorization](05-security-authz.md), which enforces the auth
scheme this chapter only describes.

**Reference:** [modules/openapi.md](../../modules/openapi.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
