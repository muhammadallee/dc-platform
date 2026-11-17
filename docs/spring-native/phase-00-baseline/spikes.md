# Phase 0 framework spikes

Plan §6 step 3. Each claim the realignment relies on, checked on Spring Boot 4.1.2 (JDK 25.0.3, 2026-11-17).

- **S1** was checked against the published artifacts and the docs; it is a feasibility check, and the outbox trial
  (Phase 6) is the real test.
- **S2 to S7** were checked with a throwaway Boot 4.1.2 project, [`spikes/`](spikes/pom.xml), outside the reactor.
  - Command: `mvn -B -ntp test` in `docs/spring-native/phase-00-baseline/spikes/`.
  - Result: exit 0, 12 tests, 0 failures, 0 skipped. Raw log: `evidence-raw/spikes.log`, not committed.

| ID | Claim | Verdict | Evidence |
|---|---|---|---|
| S1 | Spring Modulith supports Boot 4.1.2, has an Oracle event-publication registry, and can forward events to RabbitMQ | **Feasible, with open items** (below) | Artifacts on Maven Central |
| S2 | Spring Security 7 applies a `Customizer<HttpSecurity>` bean to Boot's default chain | **Holds** | `S2CustomizerHttpSecurityTest`, 2/2 |
| S3 | Boot 4 HTTP service clients take per-group properties, and a `RestClientCustomizer` bean reaches the group's client | **Holds** | `S3HttpServiceClientTest`, 1/1 |
| S4 | Spring Framework 7 `@Retryable` works once enabled; it has includes and excludes | **Holds, needs enabling** | `S4ResilientMethodsTest`, 2/2 |
| S5 | SLF4J fluent key-values render in Boot's structured JSON logging; a members customizer can mask values | **Holds** | `S5StructuredLoggingTest`, 1/1 |
| S6 | Method security switched on by auto-configuration; `@PreAuthorize` on an implementing method holds through an interface proxy; guarded methods can be listed at startup | **Holds** | `S6MethodSecurityTest`, 4/4 |
| S7 | A platform advice adds `traceId`, `timestamp`, `retryable` to Spring's own `ErrorResponseException` / `ResponseStatusException` ProblemDetails; the service sets its code as a ProblemDetail property | **Holds** | `S7ProblemDetailTest`, 2/2 |

## S1 — Spring Modulith (detail)

**What was checked:**
- **Version.** Spring Modulith **2.1.2** is the newest GA on Maven Central; 2.2.0-RC1 is a pre-release and is not used.
  - `spring-modulith-starter-core` 2.1.2 depends on `spring-boot-starter` **4.1.2**.
  - `spring-modulith-events-core` depends on `spring-boot-autoconfigure` 4.1.2 and `spring-context` 7.0.9.
- **AMQP module.** `spring-modulith-events-amqp` 2.1.2 exists and depends on `spring-amqp`, `spring-rabbit` and
  `spring-boot-amqp` 4.1.2.
- **Oracle schema.** `spring-modulith-events-jdbc-2.1.2.jar` ships `schemas/v2/schema-oracle.sql` and
  `schema-oracle-archive.sql`, plus v1 legacy variants.
  - Jar SHA-256: `35d2368aad21d8f741838d4a13572b7add2f540367bfa827a8ae6e211722af5`.
  - URL: <https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-events-jdbc/2.1.2/spring-modulith-events-jdbc-2.1.2.jar>.
- **Documented behaviour, from the Context7 docs for 2.1.0:**
  - **Completion modes:** `update`, `delete` and `archive`.
  - **Publication states:** `PUBLISHED`, `PROCESSING`, `COMPLETED`, `FAILED` and `RESUBMITTED`.
  - **Resubmission:** `FailedEventPublications.resubmit(ResubmissionOptions)` covers failed publications.
  - **Staleness:** `spring.modulith.events.staleness.processing` defaults to `Duration.ZERO`.
  - **Republishing on restart:** `spring.modulith.events.republish-outstanding-events-on-restart` defaults to `false`. The
    docs say it is "usually not recommended in multi-instance deployments".
  - **Externalisation:** `spring.modulith.events.externalization.mode` takes `module-listener` (the default) or `outbox`.

**Open items:** each becomes a carry-over row tested in the Phase 6 trial. None of them pre-answers ND5.

| # | Item | Why it matters |
|---|---|---|
| M1 | The Oracle table stores the event in `SERIALIZED_EVENT VARCHAR2(4000)` | A 4000-byte payload cap. The platform outbox stores `payload blob` and `headers clob` (`platform_outbox.sql`), with no such cap, so this is a regression unless a service-owned DDL with a `CLOB` column works with Modulith's JDBC repository. Test both |
| M2 | The shipped DDL uses `CREATE TABLE IF NOT EXISTS`, Oracle 23c syntax (the file says so) | Production is 19c (HD13): services need their own migration. `spring.modulith.events.jdbc.schema-initialization.enabled` defaults to `true`, which clashes with a runtime user without DDL rights. The platform would default it to `false` |
| M3 | The docs do not say whether externalising to RabbitMQ waits for a publisher confirm before the publication completes | v3 guarantee: a row is marked sent only after the broker acknowledges it |
| M4 | Staleness defaults to `ZERO`: a publication left `PROCESSING` by a crash is not retried until configured otherwise | v3 guarantee: a pod killed mid-drain loses nothing |
| M5 | Concurrent resubmission across two instances: how double-claiming is prevented | v3 guarantee: `FOR UPDATE SKIP LOCKED`; two drainers never claim the same row |
| M6 | The registry has an index on `(LISTENER_ID, SERIALIZED_EVENT)` | Index key size and insert cost with 4000-byte values on 19c; measure in the trial |
| M7 | The parked issues HI-0071, HI-0079, HI-0086 | Does Modulith have them, or different ones |

## S2 — `Customizer<HttpSecurity>`

- **What passed:**
  - With only `spring-boot-starter-security` and a `Customizer<HttpSecurity>` bean that permits `/open`, `/open` returns 200
    while `/closed` still returns 401.
  - The customizer is applied **before** Boot's `anyRequest().authenticated()`, so `requestMatchers(…)` in a customizer is
    legal.
- **Not yet checked (Phase 5):**
  - Whether the bean also applies to a `SecurityFilterChain` built by the platform's own auto-configuration from the
    `HttpSecurity` prototype. Expected yes; Phase 5 proves it.
  - How a service-defined chain interacts with the platform's chain.

## S3 — HTTP service clients

- **What passed:**
  - `@ImportHttpServices(group = "orders", types = OrdersApi.class)` with
    `spring.http.serviceclient.orders.base-url` and `spring.http.serviceclient.orders.read-timeout` calls a local server
    successfully.
  - A `RestClientCustomizer` bean's default header arrives at that server. Boot applies customizers to groups through
    `RestClientCustomizerHttpServiceGroupConfigurer`.
- **Property names, from `spring-boot-http-client` 4.1.2 metadata:**
  - `spring.http.serviceclient.<group>.*` per group;
  - `spring.http.clients.*` globally: `connect-timeout`, `read-timeout`, `redirects`, `ssl.bundle`, `factory`.

## S4 — `@Retryable`

- **Not auto-configured.** Boot 4.1.2 has no auto-configuration for Spring's resilience annotations (no `resilien*` class in
  `spring-boot-autoconfigure`). A platform auto-configuration annotated `@EnableResilientMethods` switches them on. With it:
  - `@Retryable(maxRetries = 2, delay = 0, includes = IllegalStateException.class)` makes 3 calls;
  - an excluded exception makes 1.
- **Not yet checked (Phase 4):**
  - how `@Retryable` combines with Resilience4j's annotations on one method;
  - whether the never-retry-POST rule (HD2) can be enforced when a service puts `@Retryable` on a method that POSTs. A
    method-level annotation cannot see the HTTP verb, so the rule must stay in the client (a `RestClient` interceptor).

## S5 — Structured logging

- **What passed:**
  - With `logging.structured.format.console=ecs`,
    `LOG.atInfo().addKeyValue("orderId", "42").log("login with password=hunter2")` produces one JSON line with
    `"orderId":"42"` as a member.
  - A `StructuredLoggingJsonMembersCustomizer`, set by `logging.structured.json.customizer`, using
    `members.applyingValueProcessor(…)`, rewrites the message to `password=****`.
  - The service used SLF4J only.
- **Caveat:** logging is configured once per JVM. The properties must be in place when logging starts: from
  `application.yml`, or from the platform's lowest-precedence `EnvironmentPostProcessor` defaults (the existing pattern).
  The first run failed because an earlier test class had already set up logging in the same JVM; the spike project now runs
  one JVM per test class.
- **Not yet checked (Phase 3):**
  - whether Boot's ECS or Logstash formats carry the platform's field names (`traceId`, `spanId`, `service`) or need
    members customisation;
  - parity with the v3 redaction cases (JWTs, `Authorization`, PII) and the regex boundary cases Codex found in v3 Phase 2.

## S6 — Method security

- **What passed:**
  - **Switch-on:** a configuration class annotated `@EnableMethodSecurity` (standing in for platform auto-configuration)
    enables `@PreAuthorize`; the service adds nothing.
  - **Through an interface proxy:**
    - with `spring.aop.proxy-target-class=false`, the bean is a JDK proxy;
    - a `@PreAuthorize("hasAuthority('orders:read')")` on the **implementing** method is enforced through the interface;
    - a stricter method rule wins over a weaker class rule (`@PreAuthorize("permitAll()")` on the class).

    This is the evidence Phase 5 needs before it can retire the v3 Phase 7 interface-proxy fix.
  - **Startup listing:** walking bean target classes with `AnnotatedElementUtils` finds every guarded method and its
    expression.
- **Not yet checked (Phase 5):**
  - deny by default for methods with no annotation;
  - checking expressions against a declared permission set (`NEW RISK`: typos);
  - the open-path startup check against `@PreAuthorize`.

## S7 — ProblemDetail

- **What passed:**
  - A service throws `ErrorResponseException` (its own subclass sets `code` with `getBody().setProperty`) or
    `ResponseStatusException`.
  - A `@RestControllerAdvice` extending `ResponseEntityExceptionHandler` overrides `createResponseEntity` and adds `traceId`,
    `timestamp`, `retryable`.
  - The JSON carries the service's `code` and `detail` and the platform's fields.
- **Scope:** errors from controllers only. The 401 and 403 from the security filter chain do not pass through the advice;
  the platform's existing security entry points keep producing those, unchanged.
- **Not yet checked (Phase 2):**
  - build-time uniqueness of error codes when the code is a free-form ProblemDetail property;
  - `@ResponseStatus`-annotated exceptions;
  - the catch-all handler that never leaks a message.

## Test-setup errors on the first run (recorded so they are not mistaken for findings)

The first run failed 5 of 12; all five were mistakes in the spike tests, fixed without changing what each claim tests:

| Test | Cause | Fix |
|---|---|---|
| S4 ×2 | Counters read as instance fields of a CGLIB proxy | Static counters |
| S5 | Logging already configured by an earlier class in the same JVM | `reuseForks=false` |
| S7 ×2 | `@WithMockUser` does not reach MockMvc without Boot 4's security-test module; requests got 401 | A `Customizer<HttpSecurity>` opening the two paths (the S2 route) |
