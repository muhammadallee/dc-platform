# Independent review — DC Platform capability hardening, Phase 1 (Core + Error Handling), Round 1

You are the independent reviewer for a production-hardening phase of an internal Spring Boot 4.1 /
Java 25 platform ("chassis"). Your working directory is a checkout of the reviewed commit; read any file
you need. Review ONLY against the phase scope below (requirements + acceptance criteria) and the diff
against the phase start commit. You are not given the implementer's self-assessment on purpose.

Hunt for real defects: correctness, security (header/log injection, information leakage), concurrency
and context bleed, context-propagation leaks across threads, broken back-off/override, ordering bugs,
untested claims, regressions in modules that depend on the changed ones (including out-of-scope modules
such as Kafka, audit, messaging that must still compile and behave), and places where the
implementation does not meet a stated acceptance criterion.

## Severity scale

- **Critical** — data loss/corruption, security bypass, cross-caller data exposure, a defect that takes
  down all replicas.
- **High** — incorrect behaviour under a realistic failure (outage, slowness, restart, concurrency),
  leaked internals in responses/logs, broken back-off/override, misleading health signal.
- **Medium** — degraded operability/DX with a workaround; missing observability on a failure path.
- **Low** — cosmetic, docs, naming.

## Required output format

For EACH finding:

```
### F<n> — <short title>
- Severity: Critical | High | Medium | Low
- Location: <path>:<line>
- Failure scenario: <concrete inputs/state → wrong output/crash>
- Proof or reasoning: <code references; why it happens>
- Proposed fix: <specific>
```

Then a final section `## Acceptance criteria not met` listing any AC from the scope that the diff does
not satisfy or does not test, with the reason. If you find nothing for a category, say so explicitly.
Do not pad with style nitpicks.

## Phase scope (requirements + acceptance criteria)

## 1. Requirements

### R1 Correlation = W3C trace ID only (HD5)
1. The trace ID of the current span is the only correlation identifier. Inbound: a valid
   `traceparent` continues that trace; missing or invalid starts a new trace. Parsing and propagation are
   done by the tracing bridge (Micrometer Tracing + OpenTelemetry W3C propagator), not by platform code.
2. A present-but-invalid `traceparent` (bad format, > 55 chars for version `00`, any control character)
   is never logged verbatim; the platform logs a sanitized, length-capped marker at DEBUG.
3. `X-Correlation-Id` is neither read, generated, echoed, nor propagated anywhere (core filter, REST
   client, tracing baggage). A caller-sent `X-Correlation-Id` is ignored.
4. `CorrelationId` (public, `@API STABLE`) and `RequestContext.correlationId()` are kept; the value is
   the current trace ID (W3C trace-id is 32 lowercase hex, which the existing validation already
   enforces). No public API is removed in this phase.
5. The platform request filter runs **after** Boot's `ServerHttpObservationFilter`
   (`HIGHEST_PRECEDENCE + 1`) and **before** the Spring Security filter chain, so a span exists when it
   reads the trace ID and 401/403 responses carry it.
6. Works at any sampling probability, including `0.0` (unsampled spans still carry a valid trace ID).

### R2 Context propagation (§7.6)
1. Request context (trace ID) and MDC survive `@Async` and the Boot-built `applicationTaskExecutor`
   (Boot's `spring.task.execution.propagate-context` turned on by platform default + a
   `ThreadLocalAccessor` for `RequestContext`).
2. MDC carries `traceId` and `spanId` on every request-scoped line; the platform no longer publishes a
   `correlationId` MDC key. MDC is empty after each request on both the request thread and pool threads.
3. ≥ 200 concurrent requests, each with its own `traceparent`: zero bleed of trace ID between requests in
   response, MDC, async thread, or ProblemDetail.

### R3 Error contract (§7.7)
1. Every error response is RFC 9457 `application/problem+json` with `type`, `title`, `status`,
   `detail`, `instance`, stable `code`, `traceId`, `timestamp`. The `correlationId` property is replaced
   by `traceId` (consumer-visible change).
2. 5xx responses never carry exception messages: unknown exceptions and `PlatformException`s with
   status ≥ 500 return a generic `detail`; full detail is logged with the trace ID.
3. Validation errors list field paths and messages; `rejectedValue` is **omitted by default**
   (`dc.platform.errors.include-rejected-values=false`), and when enabled stays redacted for
   secret-like names.
4. Malformed JSON → 400 ProblemDetail with a stable code and no parser internals (no Jackson message,
   no class names).
5. 401/403 bodies from the security entry points carry `code` and `traceId` too (body shape only — JWT
   semantics are Phase 7).
6. `retryable` (boolean) on every ProblemDetail: `true` for 429/502/503/504, `false` otherwise.
   `retryAfter` is deferred to Phase 6 (it needs the REST client's `Retry-After` handling).
7. Error codes are unique across all modules at build time.

### R4 Configuration and override contracts (§7.1–7.3)
1. Removed properties keep deprecation metadata (`level: error`, reason, replacement):
   `dc.platform.core.correlation.header-name`, `dc.platform.core.correlation.generate-if-missing`,
   `dc.platform.restclient.propagate-correlation`.
2. Config-default snapshot tests for `dc.platform.core.*` and `dc.platform.errors.*`.
3. Override tests: a consumer bean of each platform `@Bean` type in core and errors wins.
4. Kill-switch tier: core `DEFAULT_ON`, errors `DEFAULT_ON`; `enabled=false` behaviour tested.

## 2. Exclusions (and where they are handled)

| Item | Phase |
|---|---|
| Messaging header propagation (`correlationId` header in `DefaultEventPublisher` / `EventHandlerRegistrar`) → `traceparent` | 5 (G5-07). In Phase 1 its value becomes the trace ID automatically. |
| JSON log redaction, metric cardinality guard, health groups, actuator exposure | 2 |
| JPA `CorrelationIdConverter`, audit `correlationId` fields | 3 / not certified (audit). Type kept, so they compile and pass unchanged. |
| JWT validation semantics, actuator authentication | 7 |
| `retryAfter`, REST retry classification, error-body bounding | 6 |
| `type` base URI (`https://errors.dc.com/`, a domain the platform does not own, G1-08) | **OPEN — owner to supply the canonical URI**; unchanged in Phase 1 |

## 3. Correlation surface (every file mentioning correlation, by phase)

Changed in Phase 1: `core/platform-core-api` (`CorrelationId`, `RequestContext` javadoc/MDC key),
`core/platform-core-autoconfigure` (`CoreProperties`, `CorrelationIdFilter`, `CoreContextAutoConfiguration`),
`errors/platform-errors-autoconfigure` (`ProblemDetailFactory`, `PlatformExceptionHandler`),
`security/platform-security-autoconfigure` (`ProblemResponses`), `restclient/*` (`DefaultPlatformRestClientFactory`,
`RestClientProperties`, `RemoteCallException.remoteCorrelationId` deprecated),
`observability/platform-observability-autoconfigure` (baggage default removed),
`logging/platform-logging-autoconfigure` (`logback-platform.xml` comment, `LoggingProperties` doc),
`test/platform-test-api` (`ProblemDetailAssert`), archetype resources (`HttpIntegrationTestSupport`,
`StructuredLoggingTest`, `GreetingClientTest`, `GreetingClient`, `Application`, `HelloService`, README/AGENTS/CLAUDE),
`examples/example-minimal`, `tooling/scripts/golden-path.sh` + `support/GateSupport.java` (correlation echo probe → traceparent check),
docs (`docs/modules/core.md`, `errors.md`, `restclient.md`, `logging.md`, `observability.md`, `reference/properties.md` via generator, book chapters 01/02/03/06, quickstart, testing).

Unchanged in Phase 1 (later phase or out of scope): messaging api/autoconfigure (5), data JPA converter (3),
audit modules and TCK (not certified), openapi `ProblemDetailOpenApiCustomizer` (schema property rename only if tests require),
`specs/**` (historical record, never edited), examples `event-driven`, `golden-path` (follow messaging in 5).

## 4. Applicable §7 contracts

| § | Contract | Phase 1 position |
|---|---|---|
| 7.1 | Real override | tests per `@Bean` in core + errors |
| 7.2 | Config-default snapshot | core + errors |
| 7.3 | Kill-switch tier | both `DEFAULT_ON`; `enabled=false` tested |
| 7.4 | Outage posture | N/A — no backing dependency (recorded) |
| 7.5 | Health semantics | N/A (Phase 2) |
| 7.6 | Context propagation | R2 |
| 7.7 | Error surface | R3 |
| 7.8 | Redaction | ProblemDetail: no messages on 5xx, rejected values omitted; invalid `traceparent` sanitized in logs |
| 7.9 | Observability | trace ID never used as a metric tag (asserted) |
| 7.10 | Graceful shutdown | consumer harness: in-flight slow request across SIGTERM completes |
| 7.11 | Startup under adversity | N/A dependency; startup time recorded |

## 5. Acceptance criteria

| ID | Criterion | Level |
|---|---|---|
| AC1 | Valid `traceparent` → response ProblemDetail `traceId` and log MDC `traceId` equal the inbound trace ID | SLICE + CONSUMER |
| AC2 | Missing `traceparent` → a new 32-hex trace ID, present in ProblemDetail and MDC | SLICE + CONSUMER |
| AC3 | Malformed / oversized / control-character `traceparent` → new trace; raw value absent from all logs; sanitized marker present at DEBUG | UNIT + CONSUMER |
| AC4 | `X-Correlation-Id` request header is ignored (no echo, trace ID unaffected, absent from outbound requests) | SLICE + CONSUMER |
| AC5 | Outbound REST call carries `traceparent` with the same trace ID and no `X-Correlation-Id` | SLICE + CONSUMER |
| AC6 | `traceId` present in ProblemDetail and MDC with `management.tracing.sampling.probability=0.0` | CONSUMER |
| AC7 | `@Async` method sees the caller's trace ID in `RequestContext` and MDC | SLICE + CONSUMER |
| AC8 | 200 parallel requests: zero trace-ID bleed (response, MDC, async, ProblemDetail); 3 consecutive passes | SLICE + CONSUMER |
| AC9 | MDC empty after each request on request and pool threads | UNIT/SLICE |
| AC10 | Malformed JSON → 400, stable code, no parser internals | SLICE + CONSUMER |
| AC11 | Validation error → 400 with field paths, no `rejectedValue` by default | SLICE + CONSUMER |
| AC12 | Unknown exception and 5xx `PlatformException` → 500 generic detail; logs carry the stack trace and trace ID | SLICE + CONSUMER |
| AC13 | 401/403 ProblemDetail carries `code` and `traceId` | SLICE + CONSUMER |
| AC14 | `retryable` correct for 4xx/5xx/429/503 | UNIT |
| AC15 | Deprecated properties present in metadata with `level=error`; setting them has no effect | UNIT |
| AC16 | Config-default snapshot, override and kill-switch tests pass | SLICE |
| AC17 | Downstream compile check (`-amd test-compile`) green incl. Kafka/audit; golden-path gate green | build |
| AC18 | SIGTERM with an in-flight slow request: the request completes with 200, shutdown completes, exit 143 within grace | CONSUMER |


## Diff: phase start f231689 .. reviewed commit 70a04ce (docs/production-readiness excluded)

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index fd03209..beeceac 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -5,7 +5,30 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
 
 ## [Unreleased]
 
+### Breaking (capability hardening, phase 1 — core + errors)
+- **Correlation is the W3C trace ID.** `X-Correlation-Id` is no longer read, generated, echoed, sent by
+  the REST client, or mirrored into tracing baggage; a caller's `X-Correlation-Id` is ignored. Send
+  `traceparent` instead (OpenTelemetry/W3C clients do). `CorrelationId` / `RequestContext.correlationId()`
+  keep their API and now return the current trace ID. The core filter runs after Boot's observation
+  filter (`HIGHEST_PRECEDENCE + 10`) so a span exists, and before Spring Security.
+- MDC / JSON log field `correlationId` → `traceId` (+ `spanId`).
+- ProblemDetail extension `correlationId` → `traceId`; new `retryable` (true for 429/502/503/504).
+- No 5xx problem body carries an exception message (5xx `PlatformException`s included); full detail is
+  logged with the trace ID.
+- Validation errors omit `rejectedValue` by default (`dc.platform.errors.include-rejected-values`, new,
+  default `false`).
+- Removed properties (deprecation metadata, level `error`): `dc.platform.core.correlation.header-name`,
+  `dc.platform.core.correlation.generate-if-missing`, `dc.platform.restclient.propagate-correlation`.
+- `CorrelationIdFilter`'s constructor takes a Micrometer `Tracer`; `RemoteCallException.remoteCorrelationId()`
+  is deprecated (always empty for platform-created exceptions).
+
 ### Added
+- Core: context propagation to `@Async` and Boot-built executors (platform default
+  `spring.task.execution.propagate-context=true` + a `ThreadLocalAccessor` for `RequestContext`).
+- Errors: Spring MVC's own error bodies (404/405/415/unreadable…) get the platform extensions;
+  new codes `DC-CORE-0410` (malformed/unreadable body) and `DC-CORE-0499` (other rejected requests).
+- Security: 401/403 bodies carry `code` (`DC-SEC-0401`/`DC-SEC-0403`), `traceId`, `timestamp`, `retryable`.
+- Build-time error-code uniqueness across every platform module's sources (`ErrorCodeSourceUniquenessTest`).
 - `TestJwtIssuer` (`platform-test-api`, `test.security`, EXPERIMENTAL): a loopback JWKS endpoint that
   mints RS256 tokens (valid, expired, untrusted-key), so tests can exercise real bearer-token
   validation over HTTP — `TestTokens` bypasses the decoder.
@@ -60,6 +83,9 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   it deliberately.
 
 ### Fixed
+- Golden-path gate: the platform install is `clean install`, so a stale local `jacoco.exec` no longer
+  fails the JaCoCo check of a skipped-tests install on a developer tree.
+- Core: an invalid inbound correlation/trace value is never echoed into a log line or exception message.
 - CI: `StartupBudgetTest` (example-golden-path) runs in its own serial step instead of inside the
   parallel `-T1C` reactor, where contention with other modules' test contexts made the wall-clock
   budget fail at 19-25 s (D88). Local `mvn verify` still runs it.
diff --git a/core/platform-core-api/src/main/java/ae/gov/dubaicustoms/platform/core/context/CorrelationId.java b/core/platform-core-api/src/main/java/ae/gov/dubaicustoms/platform/core/context/CorrelationId.java
index 5a6fb60..7d5f069 100644
--- a/core/platform-core-api/src/main/java/ae/gov/dubaicustoms/platform/core/context/CorrelationId.java
+++ b/core/platform-core-api/src/main/java/ae/gov/dubaicustoms/platform/core/context/CorrelationId.java
@@ -8,12 +8,15 @@ import org.apiguardian.api.API;
 /**
  * Correlation identifier propagated across threads, HTTP, and messaging.
  *
- * <p>Format is 32 lowercase hex characters (a UUID without dashes), so it round-trips through
- * HTTP headers and message properties without escaping.
+ * <p>Since the hardening of the core capability, the platform's only correlation identifier is the
+ * W3C trace ID of the current span: the platform populates {@link RequestContext} with it, and it
+ * travels between services in the standard {@code traceparent} header (never in a custom header).
+ * The format, 32 lowercase hex characters, is exactly the W3C trace-id format.
  *
  * <pre>{@code
- * CorrelationId id = CorrelationId.random();
- * httpHeaders.set("X-Correlation-Id", id.value());
+ * RequestContext.correlationId()
+ *         .map(CorrelationId::value)   // == the current trace ID
+ *         .ifPresent(traceId -> problem.setProperty("traceId", traceId));
  * }</pre>
  *
  * <p>Value object; immutable and thread-safe; creation is cheap. The component is never
@@ -36,8 +39,10 @@ public record CorrelationId(String value) {
     public CorrelationId {
         Objects.requireNonNull(value, "value must not be null");
         if (!FORMAT.matcher(value).matches()) {
+            // The rejected value is deliberately not echoed: it often comes straight from a request
+            // header, and exception messages end up in logs (header/log injection).
             throw new IllegalArgumentException(
-                    "correlation id '" + value + "' must be 32 lowercase hex characters (UUID without dashes)");
+                    "correlation id must be 32 lowercase hex characters (got " + value.length() + " characters)");
         }
     }
 
diff --git a/core/platform-core-api/src/main/java/ae/gov/dubaicustoms/platform/core/context/RequestContext.java b/core/platform-core-api/src/main/java/ae/gov/dubaicustoms/platform/core/context/RequestContext.java
index 8c80421..7dc2eb9 100644
--- a/core/platform-core-api/src/main/java/ae/gov/dubaicustoms/platform/core/context/RequestContext.java
+++ b/core/platform-core-api/src/main/java/ae/gov/dubaicustoms/platform/core/context/RequestContext.java
@@ -13,8 +13,11 @@ import org.slf4j.MDC;
 
 /**
  * Read-only static access to the current request context (correlation id plus platform-managed
- * extras). Backed by SLF4J's {@link MDC} so logging patterns like {@code %X{correlationId}} work
- * out of the box; Micrometer context-propagation is layered on later.
+ * extras). The correlation id is the W3C trace ID of the current span (see {@link CorrelationId}).
+ * Backed by SLF4J's {@link MDC}: the id is published under {@code traceId} (the key Spring Boot's
+ * tracing uses), so {@code %X{traceId}} and JSON logs carry it; platform auto-configuration
+ * propagates the context to {@code @Async} and Boot-built executors via Micrometer
+ * context-propagation.
  *
  * <p>Static access is READ-ONLY convenience for application code; population is done exclusively
  * by platform filters and interceptors via {@link #open(CorrelationId, Map)}.
@@ -32,8 +35,8 @@ import org.slf4j.MDC;
 @API(status = API.Status.STABLE, since = "0.1.0")
 public final class RequestContext {
 
-    /** MDC key under which the correlation id is published. */
-    private static final String CORRELATION_ID_KEY = "correlationId";
+    /** MDC key under which the correlation id (the trace ID) is published; Boot tracing's key. */
+    private static final String CORRELATION_ID_KEY = "traceId";
 
     // Stack of open scopes per thread; supports nested open() (e.g. a message handler inside a
     // web request) with innermost-wins reads and exact restoration on close.
diff --git a/core/platform-core-api/src/test/java/ae/gov/dubaicustoms/platform/core/context/RequestContextTest.java b/core/platform-core-api/src/test/java/ae/gov/dubaicustoms/platform/core/context/RequestContextTest.java
index 10d7569..8f69702 100644
--- a/core/platform-core-api/src/test/java/ae/gov/dubaicustoms/platform/core/context/RequestContextTest.java
+++ b/core/platform-core-api/src/test/java/ae/gov/dubaicustoms/platform/core/context/RequestContextTest.java
@@ -29,26 +29,26 @@ class RequestContextTest {
         try (AutoCloseable scope = RequestContext.open(ID, Map.of("tenant", "dxb"))) {
             assertThat(RequestContext.correlationId()).contains(ID);
             assertThat(RequestContext.asMap())
-                    .containsEntry("correlationId", ID.value())
+                    .containsEntry("traceId", ID.value())
                     .containsEntry("tenant", "dxb");
-            assertThat(MDC.get("correlationId")).isEqualTo(ID.value());
+            assertThat(MDC.get("traceId")).isEqualTo(ID.value());
             assertThat(MDC.get("tenant")).isEqualTo("dxb");
         }
         assertThat(RequestContext.correlationId()).isEmpty();
-        assertThat(MDC.get("correlationId")).isNull();
+        assertThat(MDC.get("traceId")).isNull();
         assertThat(MDC.get("tenant")).isNull();
     }
 
     @Test
     void closeRestoresShadowedMdcValues() throws Exception {
-        MDC.put("correlationId", "preexisting");
+        MDC.put("traceId", "preexisting");
         MDC.put("userKey", "kept");
 
         try (AutoCloseable scope = RequestContext.open(ID, Map.of())) {
-            assertThat(MDC.get("correlationId")).isEqualTo(ID.value());
+            assertThat(MDC.get("traceId")).isEqualTo(ID.value());
         }
 
-        assertThat(MDC.get("correlationId")).isEqualTo("preexisting");
+        assertThat(MDC.get("traceId")).isEqualTo("preexisting");
         assertThat(MDC.get("userKey")).isEqualTo("kept");
     }
 
@@ -60,13 +60,13 @@ class RequestContextTest {
             try (AutoCloseable innerScope = RequestContext.open(inner, Map.of("inner", "2"))) {
                 assertThat(RequestContext.correlationId()).contains(inner);
                 assertThat(RequestContext.asMap())
-                        .containsEntry("correlationId", inner.value())
+                        .containsEntry("traceId", inner.value())
                         .containsEntry("outer", "1")
                         .containsEntry("inner", "2");
             }
             assertThat(RequestContext.correlationId()).contains(ID);
             assertThat(RequestContext.asMap())
-                    .containsEntry("correlationId", ID.value())
+                    .containsEntry("traceId", ID.value())
                     .containsEntry("outer", "1")
                     .doesNotContainKey("inner");
         }
diff --git a/core/platform-core-autoconfigure/pom.xml b/core/platform-core-autoconfigure/pom.xml
index bded568..c57879f 100644
--- a/core/platform-core-autoconfigure/pom.xml
+++ b/core/platform-core-autoconfigure/pom.xml
@@ -43,6 +43,20 @@
       <artifactId>jakarta.servlet-api</artifactId>
       <optional>true</optional>
     </dependency>
+    <!-- The correlation id IS the W3C trace id: the filter reads it from the Micrometer Tracer that
+         the observability starter's tracing bridge provides. Optional: without tracing on the
+         classpath no correlation context is established (documented in docs/modules/core.md). -->
+    <dependency>
+      <groupId>io.micrometer</groupId>
+      <artifactId>micrometer-tracing</artifactId>
+      <optional>true</optional>
+    </dependency>
+    <!-- ThreadLocalAccessor SPI: RequestContext rides along with Boot's ContextPropagatingTaskDecorator. -->
+    <dependency>
+      <groupId>io.micrometer</groupId>
+      <artifactId>context-propagation</artifactId>
+      <optional>true</optional>
+    </dependency>
     <!-- Generates config metadata from the properties record at compile time. -->
     <dependency>
       <groupId>org.springframework.boot</groupId>
@@ -55,6 +69,23 @@
       <artifactId>platform-build-tools</artifactId>
       <scope>test</scope>
     </dependency>
+    <!-- Real W3C propagation (OpenTelemetry bridge + Boot's tracing/observation auto-configuration)
+         for the filter tests: no hand-rolled traceparent parsing anywhere, including in tests. -->
+    <dependency>
+      <groupId>io.micrometer</groupId>
+      <artifactId>micrometer-tracing-bridge-otel</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-micrometer-tracing-opentelemetry</artifactId>
+      <scope>test</scope>
+    </dependency>
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-starter-actuator</artifactId>
+      <scope>test</scope>
+    </dependency>
     <!-- MockMvc + servlet runtime for the filter behavior tests. -->
     <dependency>
       <groupId>org.springframework.boot</groupId>
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java
index 259f44a..e6f8b28 100644
--- a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java
@@ -1,6 +1,8 @@
 package ae.gov.dubaicustoms.platform.core.autoconfigure;
 
 import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
+import io.micrometer.tracing.Tracer;
+import org.springframework.beans.factory.ObjectProvider;
 import org.springframework.boot.autoconfigure.AutoConfiguration;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
@@ -9,19 +11,26 @@ import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplicat
 import org.springframework.boot.context.properties.EnableConfigurationProperties;
 import org.springframework.boot.web.servlet.FilterRegistrationBean;
 import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Configuration;
 import org.springframework.core.Ordered;
 
 /*
  * Activates when: servlet web application AND platform-core-api on the classpath
  *                 AND dc.platform.core.enabled != false
+ *                 (filter beans additionally: micrometer-tracing on the classpath)
  * Backs off when: user defines a CorrelationIdFilter bean (the registration then wraps theirs),
  *                 or a FilterRegistrationBean named platformCorrelationFilterRegistration
- * Beans: correlationIdFilter — reads/generates the correlation header, opens RequestContext,
- *                              echoes the header on the response, always closes;
+ * Beans: correlationIdFilter — opens RequestContext with the current span's W3C trace ID (MDC
+ *                              traceId/spanId) for the request and always closes it; never reads,
+ *                              generates or echoes a correlation header;
  *        platformCorrelationFilterRegistration — registers the filter for all URLs at
- *                              Ordered.HIGHEST_PRECEDENCE
- * Order: filter precedence is HIGHEST because it must wrap security and logging — their MDC
- *        reads and log lines must already carry the correlation id.
+ *                              FILTER_ORDER
+ * Order: FILTER_ORDER = HIGHEST_PRECEDENCE + 10: after Boot's ServerHttpObservationFilter
+ *        (HIGHEST_PRECEDENCE + 1), which continues or starts the trace from traceparent, and well
+ *        before the Spring Security chain (-100), so 401/403 bodies and security log lines already
+ *        carry the trace ID.
+ * Without a Tracer bean (tracing bridge absent or disabled) the filter passes requests through
+ * without a correlation context rather than inventing an id.
  */
 @AutoConfiguration
 @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@@ -30,20 +39,26 @@ import org.springframework.core.Ordered;
 @EnableConfigurationProperties(CoreProperties.class)
 public class CoreContextAutoConfiguration {
 
-    @Bean
-    @ConditionalOnMissingBean
-    CorrelationIdFilter correlationIdFilter(CoreProperties properties) {
-        return new CorrelationIdFilter(
-                properties.correlation().headerName(),
-                properties.correlation().generateIfMissing());
-    }
+    /** Servlet filter order of the correlation filter; see the activation comment above. */
+    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
+
+    @Configuration(proxyBeanMethods = false)
+    @ConditionalOnClass(Tracer.class)
+    static class TraceCorrelationConfiguration {
+
+        @Bean
+        @ConditionalOnMissingBean
+        CorrelationIdFilter correlationIdFilter(ObjectProvider<Tracer> tracer) {
+            return new CorrelationIdFilter(tracer.getIfAvailable(() -> Tracer.NOOP));
+        }
 
-    @Bean
-    @ConditionalOnMissingBean(name = "platformCorrelationFilterRegistration")
-    FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration(CorrelationIdFilter filter) {
-        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
-        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
-        registration.addUrlPatterns("/*");
-        return registration;
+        @Bean
+        @ConditionalOnMissingBean(name = "platformCorrelationFilterRegistration")
+        FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration(CorrelationIdFilter filter) {
+            FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
+            registration.setOrder(FILTER_ORDER);
+            registration.addUrlPatterns("/*");
+            return registration;
+        }
     }
 }
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreProperties.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreProperties.java
index 94ff1ec..56c5e94 100644
--- a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreProperties.java
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreProperties.java
@@ -10,9 +10,12 @@ import org.springframework.validation.annotation.Validated;
  * <p>Immutable; validated at startup. Registered by the core auto-configurations via
  * {@code @EnableConfigurationProperties} (never scanned).
  *
+ * <p>The former {@code dc.platform.core.correlation.*} settings are gone: the correlation id is the
+ * W3C trace ID and travels in {@code traceparent}, so there is no header name to choose and nothing to
+ * generate. The keys remain in the configuration metadata as deprecated (level {@code error}).
+ *
  * @param enabled master kill switch for the whole capability
  * @param bannerEnabled whether the startup capability banner is logged
- * @param correlation correlation-id filter settings
  * @since 0.1.0
  */
 @Validated
@@ -21,21 +24,5 @@ public record CoreProperties(
         /** Master switch for the capability. */
         @DefaultValue("true") boolean enabled,
         /** Log the one-line capability banner at startup. */
-        @DefaultValue("true") boolean bannerEnabled,
-        /** Correlation-id filter settings. */
-        @DefaultValue Correlation correlation) {
-
-    /**
-     * Correlation-id propagation settings.
-     *
-     * @param headerName HTTP header carrying the correlation id
-     * @param generateIfMissing generate a fresh id when the incoming request carries none
-     * @since 0.1.0
-     */
-    public record Correlation(
-            /** HTTP header carrying the correlation id. */
-            @DefaultValue("X-Correlation-Id") String headerName,
-            /** Generate a fresh id when the incoming request carries none (or an invalid one). */
-            @DefaultValue("true") boolean generateIfMissing) {
-    }
+        @DefaultValue("true") boolean bannerEnabled) {
 }
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilter.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilter.java
index 40dc2ef..54468a1 100644
--- a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilter.java
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilter.java
@@ -2,23 +2,34 @@ package ae.gov.dubaicustoms.platform.core.autoconfigure;
 
 import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
 import ae.gov.dubaicustoms.platform.core.context.RequestContext;
+import io.micrometer.tracing.Span;
+import io.micrometer.tracing.TraceContext;
+import io.micrometer.tracing.Tracer;
 import jakarta.servlet.FilterChain;
 import jakarta.servlet.ServletException;
 import jakarta.servlet.http.HttpServletRequest;
 import jakarta.servlet.http.HttpServletResponse;
 import java.io.IOException;
 import java.util.Map;
+import java.util.Objects;
+import java.util.regex.Pattern;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.springframework.web.filter.OncePerRequestFilter;
 
 /**
- * Servlet filter that establishes the correlation context for every request: reads the
- * correlation header (generating a fresh id when absent or malformed, if configured), opens the
- * {@link RequestContext} for the duration of the request, and echoes the header on the response.
+ * Servlet filter that establishes the correlation context for every request from the current
+ * W3C trace: it opens the {@link RequestContext} with the active span's trace ID (and publishes
+ * {@code traceId}/{@code spanId} into the MDC) for the duration of the request, and always closes it.
  *
- * <p>Registered by {@code CoreContextAutoConfiguration} at highest filter precedence; define your
- * own {@code CorrelationIdFilter} bean to replace it.
+ * <p>The filter never parses, generates, or echoes correlation headers. Inbound {@code traceparent}
+ * handling is the tracing bridge's job (Boot's {@code ServerHttpObservationFilter}, which runs
+ * before this filter): a valid header continues the caller's trace, a missing or invalid one starts
+ * a new trace. This filter only logs, at DEBUG, a sanitized and length-capped marker when a
+ * {@code traceparent} was present but not valid, so the raw value never reaches a log line.
+ *
+ * <p>Registered by {@code CoreContextAutoConfiguration} after the observation filter and before
+ * the Spring Security filter chain; define your own {@code CorrelationIdFilter} bean to replace it.
  *
  * <p>Thread-safe; the context it opens is confined to the request thread and always closed.
  * Constructor arguments must not be {@code null}.
@@ -29,31 +40,35 @@ public class CorrelationIdFilter extends OncePerRequestFilter {
 
     private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
 
-    private final String headerName;
-    private final boolean generateIfMissing;
+    /** W3C Trace Context, version 00: {@code 00-<32 hex trace-id>-<16 hex parent-id>-<2 hex flags>}. */
+    private static final Pattern TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
+
+    private static final String TRACEPARENT_HEADER = "traceparent";
+
+    private static final int MAX_LOGGED_CHARS = 64;
+
+    private final Tracer tracer;
 
     /**
      * Creates the filter.
      *
-     * @param headerName the HTTP header carrying the correlation id; never {@code null}
-     * @param generateIfMissing whether to mint a fresh id when the request carries none
+     * @param tracer the tracer whose current span carries the request's trace ID; never {@code null}
      */
-    public CorrelationIdFilter(String headerName, boolean generateIfMissing) {
-        this.headerName = headerName;
-        this.generateIfMissing = generateIfMissing;
+    public CorrelationIdFilter(Tracer tracer) {
+        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
     }
 
     @Override
     protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
             throws ServletException, IOException {
-        CorrelationId id = resolve(request.getHeader(headerName));
-        if (id == null) {
+        logRejectedTraceparent(request.getHeader(TRACEPARENT_HEADER));
+        TraceContext trace = currentTraceContext();
+        if (trace == null) {
             filterChain.doFilter(request, response);
             return;
         }
-        // Echo before the chain so the header survives an early response commit.
-        response.setHeader(headerName, id.value());
-        try (AutoCloseable scope = RequestContext.open(id, Map.of())) {
+        Map<String, String> extras = trace.spanId() == null ? Map.of() : Map.of("spanId", trace.spanId());
+        try (AutoCloseable scope = RequestContext.open(new CorrelationId(trace.traceId()), extras)) {
             filterChain.doFilter(request, response);
         } catch (ServletException | IOException | RuntimeException e) {
             throw e;
@@ -63,14 +78,48 @@ public class CorrelationIdFilter extends OncePerRequestFilter {
         }
     }
 
-    private CorrelationId resolve(String headerValue) {
-        if (headerValue != null) {
-            try {
-                return new CorrelationId(headerValue);
-            } catch (IllegalArgumentException e) {
-                log.debug("ignoring malformed {} header: {}", headerName, e.getMessage());
+    // A trace ID that is not a 128-bit W3C id (e.g. a 64-bit Brave id) is not a correlation id; the
+    // request then runs without a RequestContext rather than with a fabricated one.
+    private TraceContext currentTraceContext() {
+        Span span = tracer.currentSpan();
+        if (span == null) {
+            return null;
+        }
+        TraceContext context = span.context();
+        if (context.traceId() == null || context.traceId().isEmpty()) {
+            // No-op tracer (tracing disabled): no trace, so no correlation context.
+            return null;
+        }
+        if (context.traceId().length() != 32) {
+            log.debug("current span has no 128-bit trace id; request runs without a correlation context");
+            return null;
+        }
+        return context;
+    }
+
+    private static void logRejectedTraceparent(String value) {
+        if (value == null || TRACEPARENT.matcher(value).matches() || !log.isDebugEnabled()) {
+            return;
+        }
+        log.debug("rejected inbound traceparent (length={}, sanitized='{}'); a new trace was started",
+                value.length(), sanitize(value));
+    }
+
+    // Keeps only characters a traceparent may legally contain and caps the length, so neither
+    // control characters (log injection) nor an oversized value can reach the log line.
+    static String sanitize(String value) {
+        StringBuilder kept = new StringBuilder(Math.min(value.length(), MAX_LOGGED_CHARS));
+        for (int i = 0; i < value.length() && kept.length() < MAX_LOGGED_CHARS; i++) {
+            char c = value.charAt(i);
+            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == '-') {
+                kept.append(c);
+            } else {
+                kept.append('?');
             }
         }
-        return generateIfMissing ? CorrelationId.random() : null;
+        if (value.length() > MAX_LOGGED_CHARS) {
+            kept.append("...");
+        }
+        return kept.toString();
     }
 }
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformCoreEnvironmentPostProcessor.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformCoreEnvironmentPostProcessor.java
new file mode 100644
index 0000000..9908e77
--- /dev/null
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformCoreEnvironmentPostProcessor.java
@@ -0,0 +1,40 @@
+package ae.gov.dubaicustoms.platform.core.autoconfigure;
+
+import java.util.Map;
+import org.springframework.boot.EnvironmentPostProcessor;
+import org.springframework.boot.SpringApplication;
+import org.springframework.core.Ordered;
+import org.springframework.core.env.ConfigurableEnvironment;
+import org.springframework.core.env.MapPropertySource;
+
+/**
+ * Contributes the core capability's lowest-precedence defaults: turns on Spring Boot's own
+ * context propagation for its task executors ({@code spring.task.execution.propagate-context}), so
+ * the current trace, the {@code RequestContext}, and the MDC follow work onto {@code @Async} methods
+ * and Boot-built executors. Applications override it like any other property.
+ *
+ * <p>Skipped entirely when {@code dc.platform.core.enabled=false}. Stateless and thread-safe.
+ *
+ * @since 0.1.0
+ */
+public class PlatformCoreEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
+
+    /** Property-source name; keep stable — operators grep for it in /actuator/env. */
+    static final String PROPERTY_SOURCE_NAME = "platform-core-defaults";
+
+    @Override
+    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
+        if (!environment.getProperty("dc.platform.core.enabled", Boolean.class, true)) {
+            return;
+        }
+        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME,
+                Map.of("spring.task.execution.propagate-context", "true")));
+    }
+
+    @Override
+    public int getOrder() {
+        // After ConfigDataEnvironmentPostProcessor: application.yml/profile values must be visible
+        // when the kill switch is read.
+        return Ordered.LOWEST_PRECEDENCE;
+    }
+}
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java
new file mode 100644
index 0000000..3988077
--- /dev/null
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java
@@ -0,0 +1,90 @@
+package ae.gov.dubaicustoms.platform.core.autoconfigure.internal;
+
+import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
+import ae.gov.dubaicustoms.platform.core.context.RequestContext;
+import io.micrometer.context.ThreadLocalAccessor;
+import java.util.ArrayDeque;
+import java.util.Deque;
+import java.util.LinkedHashMap;
+import java.util.Map;
+import java.util.Optional;
+
+/**
+ * Micrometer context-propagation accessor for {@link RequestContext}: captures the current context
+ * on the submitting thread and re-opens it on the executing thread, so {@code @Async} methods and
+ * Boot-built executors (wrapped by {@code ContextPropagatingTaskDecorator}) see the caller's trace ID
+ * in {@code RequestContext} and the MDC. Registered through {@code META-INF/services}.
+ *
+ * <p>Every scope this accessor opens is closed by the matching {@code restore}, so pool threads
+ * return to an empty context and an empty MDC after each task.
+ */
+public final class RequestContextThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {
+
+    /** Context-propagation key; stable, other libraries never use it. */
+    public static final String KEY = "dc.platform.request-context";
+
+    private static final String TRACE_ID_KEY = "traceId";
+
+    // One entry per setValue: the scope it opened, or NONE when it opened nothing, so restore()
+    // always pops exactly what the matching setValue pushed.
+    private static final AutoCloseable NONE = () -> {
+    };
+
+    private static final ThreadLocal<Deque<AutoCloseable>> OPENED = ThreadLocal.withInitial(ArrayDeque::new);
+
+    @Override
+    public Object key() {
+        return KEY;
+    }
+
+    @Override
+    public Map<String, String> getValue() {
+        Map<String, String> snapshot = RequestContext.asMap();
+        return snapshot.isEmpty() ? null : snapshot;
+    }
+
+    @Override
+    public void setValue(Map<String, String> value) {
+        Optional<CorrelationId> id = Optional.ofNullable(value.get(TRACE_ID_KEY)).map(CorrelationId::new);
+        if (id.isEmpty()) {
+            OPENED.get().push(NONE);
+            return;
+        }
+        Map<String, String> extras = new LinkedHashMap<>(value);
+        extras.remove(TRACE_ID_KEY);
+        OPENED.get().push(RequestContext.open(id.get(), extras));
+    }
+
+    @Override
+    public void setValue() {
+        // The snapshot had no context: nothing to open, but restore() still pops one entry.
+        OPENED.get().push(NONE);
+    }
+
+    @Override
+    public void restore(Map<String, String> previousValue) {
+        closeLatest();
+    }
+
+    @Override
+    public void restore() {
+        closeLatest();
+    }
+
+    private static void closeLatest() {
+        Deque<AutoCloseable> opened = OPENED.get();
+        AutoCloseable latest = opened.poll();
+        if (opened.isEmpty()) {
+            OPENED.remove();
+        }
+        if (latest == null) {
+            return;
+        }
+        try {
+            latest.close();
+        } catch (Exception e) {
+            // RequestContext scopes never throw; AutoCloseable's signature only.
+            throw new IllegalStateException(e);
+        }
+    }
+}
diff --git a/core/platform-core-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json b/core/platform-core-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json
new file mode 100644
index 0000000..1538ff2
--- /dev/null
+++ b/core/platform-core-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json
@@ -0,0 +1,22 @@
+{
+  "properties": [
+    {
+      "name": "dc.platform.core.correlation.header-name",
+      "type": "java.lang.String",
+      "description": "Removed: the correlation id is the W3C trace ID and travels in the standard traceparent header, so there is no custom header to configure.",
+      "deprecation": {
+        "level": "error",
+        "reason": "Correlation is the W3C trace ID (traceparent); custom correlation headers are no longer read or written."
+      }
+    },
+    {
+      "name": "dc.platform.core.correlation.generate-if-missing",
+      "type": "java.lang.Boolean",
+      "description": "Removed: a request without a valid traceparent always starts a new trace, whose trace ID is the correlation id.",
+      "deprecation": {
+        "level": "error",
+        "reason": "The tracing bridge always starts a new trace when traceparent is missing or invalid."
+      }
+    }
+  ]
+}
diff --git a/core/platform-core-autoconfigure/src/main/resources/META-INF/services/io.micrometer.context.ThreadLocalAccessor b/core/platform-core-autoconfigure/src/main/resources/META-INF/services/io.micrometer.context.ThreadLocalAccessor
new file mode 100644
index 0000000..b6a383e
--- /dev/null
+++ b/core/platform-core-autoconfigure/src/main/resources/META-INF/services/io.micrometer.context.ThreadLocalAccessor
@@ -0,0 +1 @@
+ae.gov.dubaicustoms.platform.core.autoconfigure.internal.RequestContextThreadLocalAccessor
diff --git a/core/platform-core-autoconfigure/src/main/resources/META-INF/spring.factories b/core/platform-core-autoconfigure/src/main/resources/META-INF/spring.factories
new file mode 100644
index 0000000..afa7978
--- /dev/null
+++ b/core/platform-core-autoconfigure/src/main/resources/META-INF/spring.factories
@@ -0,0 +1,2 @@
+org.springframework.boot.EnvironmentPostProcessor=\
+ae.gov.dubaicustoms.platform.core.autoconfigure.PlatformCoreEnvironmentPostProcessor
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java
index 6a3dca1..5379fb7 100644
--- a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java
@@ -3,6 +3,8 @@ package ae.gov.dubaicustoms.platform.core.autoconfigure;
 import static org.assertj.core.api.Assertions.assertThat;
 
 import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
+import io.micrometer.tracing.Tracer;
+import java.util.Map;
 import org.junit.jupiter.api.Test;
 import org.springframework.boot.autoconfigure.AutoConfigurations;
 import org.springframework.boot.test.context.FilteredClassLoader;
@@ -11,7 +13,10 @@ import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
 import org.springframework.boot.web.servlet.FilterRegistrationBean;
 import org.springframework.core.Ordered;
 
-/** The mandatory 5-case ContextRunner matrix for CoreContextAutoConfiguration. */
+/**
+ * The mandatory 5-case ContextRunner matrix for CoreContextAutoConfiguration, plus the ordering
+ * contract, the real-override contract (plan §7.1) and the config-default snapshot (plan §7.2).
+ */
 class CoreContextAutoConfigurationTest {
 
     private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
@@ -28,14 +33,29 @@ class CoreContextAutoConfigurationTest {
     @Test
     void killSwitchDisables() {
         runner.withPropertyValues("dc.platform.core.enabled=false")
-                .run(context -> assertThat(context).doesNotHaveBean(CorrelationIdFilter.class));
+                .run(context -> {
+                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
+                    assertThat(context).doesNotHaveBean("platformCorrelationFilterRegistration");
+                });
     }
 
     @Test
     void backsOffWhenUserFilterPresent() {
-        CorrelationIdFilter mine = new CorrelationIdFilter("X-Mine", false);
+        CorrelationIdFilter mine = new CorrelationIdFilter(Tracer.NOOP);
         runner.withBean("mine", CorrelationIdFilter.class, () -> mine)
-                .run(context -> assertThat(context).getBean(CorrelationIdFilter.class).isSameAs(mine));
+                .run(context -> {
+                    assertThat(context).getBean(CorrelationIdFilter.class).isSameAs(mine);
+                    // The platform registration wraps the consumer's filter instead of a second one.
+                    assertThat(context.getBean("platformCorrelationFilterRegistration", FilterRegistrationBean.class)
+                            .getFilter()).isSameAs(mine);
+                });
+    }
+
+    @Test
+    void backsOffWhenUserRegistrationPresent() {
+        FilterRegistrationBean<CorrelationIdFilter> mine = new FilterRegistrationBean<>(new CorrelationIdFilter(Tracer.NOOP));
+        runner.withBean("platformCorrelationFilterRegistration", FilterRegistrationBean.class, () -> mine)
+                .run(context -> assertThat(context.getBean("platformCorrelationFilterRegistration")).isSameAs(mine));
     }
 
     @Test
@@ -44,6 +64,15 @@ class CoreContextAutoConfigurationTest {
                 .run(context -> assertThat(context).doesNotHaveBean(CoreContextAutoConfiguration.class));
     }
 
+    @Test
+    void noFilterWithoutTracingOnTheClasspath() {
+        runner.withClassLoader(new FilteredClassLoader(Tracer.class))
+                .run(context -> {
+                    assertThat(context).hasNotFailed();
+                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
+                });
+    }
+
     @Test
     void inactiveOutsideWebApplications() {
         new ApplicationContextRunner()
@@ -52,19 +81,37 @@ class CoreContextAutoConfigurationTest {
     }
 
     @Test
-    void filterRegisteredAtHighestPrecedence() {
-        // Ordering case of the matrix: the filter must wrap security and logging.
+    void filterRunsAfterTheObservationFilterAndBeforeSecurity() {
         runner.run(context -> {
             FilterRegistrationBean<?> registration =
                     context.getBean("platformCorrelationFilterRegistration", FilterRegistrationBean.class);
-            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
+            // Boot 4.1 registers ServerHttpObservationFilter at HIGHEST_PRECEDENCE + 1; Spring
+            // Security's chain defaults to -100 (SecurityFilterProperties.DEFAULT_FILTER_ORDER).
+            assertThat(registration.getOrder())
+                    .isEqualTo(CoreContextAutoConfiguration.FILTER_ORDER)
+                    .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1)
+                    .isLessThan(-100);
         });
     }
 
     @Test
-    void headerNameIsConfigurable() {
-        runner.withPropertyValues("dc.platform.core.correlation.header-name=X-Trace")
-                .run(context -> assertThat(context.getBean(CoreProperties.class).correlation().headerName())
-                        .isEqualTo("X-Trace"));
+    void removedCorrelationPropertiesAreIgnored() {
+        runner.withPropertyValues("dc.platform.core.correlation.header-name=X-Trace",
+                        "dc.platform.core.correlation.generate-if-missing=false")
+                .run(context -> {
+                    assertThat(context).hasNotFailed();
+                    assertThat(context).hasSingleBean(CorrelationIdFilter.class);
+                });
+    }
+
+    @Test
+    void configurationDefaultsSnapshot() {
+        // Plan §7.2: a changed default fails here until the snapshot is consciously updated and the
+        // change is recorded in the phase changelog.
+        runner.run(context -> {
+            CoreProperties properties = context.getBean(CoreProperties.class);
+            assertThat(Map.of("enabled", properties.enabled(), "banner-enabled", properties.bannerEnabled()))
+                    .isEqualTo(Map.of("enabled", true, "banner-enabled", true));
+        });
     }
 }
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilterTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilterTest.java
index 294b9f6..ade810e 100644
--- a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilterTest.java
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilterTest.java
@@ -1,110 +1,93 @@
 package ae.gov.dubaicustoms.platform.core.autoconfigure;
 
 import static org.assertj.core.api.Assertions.assertThat;
-import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
-import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
 
-import ae.gov.dubaicustoms.platform.core.context.RequestContext;
+import ch.qos.logback.classic.Level;
+import ch.qos.logback.classic.Logger;
+import ch.qos.logback.classic.spi.ILoggingEvent;
+import ch.qos.logback.core.read.ListAppender;
+import java.util.List;
 import java.util.Map;
-import java.util.concurrent.ExecutorService;
-import java.util.concurrent.Executors;
-import java.util.concurrent.atomic.AtomicReference;
 import org.junit.jupiter.api.AfterEach;
 import org.junit.jupiter.api.BeforeEach;
 import org.junit.jupiter.api.Test;
-import org.slf4j.MDC;
-import org.springframework.test.web.servlet.MockMvc;
-import org.springframework.test.web.servlet.setup.MockMvcBuilders;
-import org.springframework.web.bind.annotation.GetMapping;
-import org.springframework.web.bind.annotation.RestController;
+import org.slf4j.LoggerFactory;
 
-/** Behavior of the correlation filter through a real MockMvc filter chain. */
+/** Unit behaviour of the rejected-traceparent marker: sanitized, capped, never the raw value. */
 class CorrelationIdFilterTest {
 
-    private static final String HEADER = "X-Correlation-Id";
-    private static final String INCOMING = "0123456789abcdef0123456789abcdef";
-
-    private final CaptureController controller = new CaptureController();
-    private MockMvc mockMvc;
-
-    @RestController
-    static class CaptureController {
-
-        final AtomicReference<Map<String, String>> seenContext = new AtomicReference<>();
-        final AtomicReference<String> seenMdc = new AtomicReference<>();
-        final AtomicReference<Boolean> otherThreadSawContext = new AtomicReference<>();
-
-        @GetMapping("/probe")
-        String probe() throws Exception {
-            seenContext.set(RequestContext.asMap());
-            seenMdc.set(MDC.get("correlationId"));
-            ExecutorService executor = Executors.newSingleThreadExecutor();
-            try {
-                otherThreadSawContext.set(executor.submit(() -> RequestContext.correlationId().isPresent()).get());
-            } finally {
-                executor.shutdownNow();
-            }
-            return "ok";
-        }
-    }
+    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
+    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
 
     @BeforeEach
-    void setUp() {
-        mockMvc = MockMvcBuilders.standaloneSetup(controller)
-                .addFilters(new CorrelationIdFilter(HEADER, true))
-                .build();
+    void captureFilterLog() {
+        appender.start();
+        filterLogger.addAppender(appender);
+        filterLogger.setLevel(Level.DEBUG);
     }
 
     @AfterEach
-    void mdcIsAlwaysCleanAfterTheRequest() {
-        assertThat(MDC.get("correlationId")).isNull();
+    void detach() {
+        filterLogger.detachAppender(appender);
+        filterLogger.setLevel(null);
     }
 
     @Test
-    void echoesIncomingCorrelationIdAndPopulatesContextDuringRequest() throws Exception {
-        mockMvc.perform(get("/probe").header(HEADER, INCOMING))
-                .andExpect(status().isOk())
-                .andExpect(result -> assertThat(result.getResponse().getHeader(HEADER)).isEqualTo(INCOMING));
-
-        assertThat(controller.seenContext.get()).containsEntry("correlationId", INCOMING);
-        assertThat(controller.seenMdc.get()).isEqualTo(INCOMING);
+    void sanitizeReplacesEveryCharacterOutsideTheTraceparentAlphabet() {
+        assertThat(CorrelationIdFilter.sanitize("00-ab\r\nInjected: yes"))
+                .hasSize("00-ab\r\nInjected: yes".length())
+                .startsWith("00-ab??")
+                .matches("[0-9a-f?-]+");
+        assertThat(CorrelationIdFilter.sanitize("<script>")).isEqualTo("??c?????");
     }
 
     @Test
-    void generatesFreshIdWhenHeaderMissing() throws Exception {
-        mockMvc.perform(get("/probe"))
-                .andExpect(status().isOk())
-                .andExpect(result -> assertThat(result.getResponse().getHeader(HEADER)).matches("[0-9a-f]{32}"));
+    void sanitizeCapsTheLength() {
+        String oversized = "a".repeat(5_000);
+        assertThat(CorrelationIdFilter.sanitize(oversized)).hasSize(64 + 3).endsWith("...");
     }
 
     @Test
-    void regeneratesWhenHeaderMalformed() throws Exception {
-        mockMvc.perform(get("/probe").header(HEADER, "not-a-correlation-id"))
-                .andExpect(status().isOk())
-                .andExpect(result -> assertThat(result.getResponse().getHeader(HEADER))
-                        .matches("[0-9a-f]{32}")
-                        .isNotEqualTo("not-a-correlation-id"));
+    void sanitizeKeepsAValidTraceparentReadable() {
+        String valid = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
+        assertThat(CorrelationIdFilter.sanitize(valid)).isEqualTo(valid);
     }
 
     @Test
-    void contextDoesNotLeakToOtherThreads() throws Exception {
-        mockMvc.perform(get("/probe").header(HEADER, INCOMING)).andExpect(status().isOk());
-
-        assertThat(controller.otherThreadSawContext.get())
-                .as("the request context must be confined to the request thread")
-                .isFalse();
+    void rejectedMarkerNeverContainsTheRawValue() throws Exception {
+        String hostile = "00-\r\nX-Injected: forged-log-line\u0007" + "f".repeat(300);
+        CorrelationIdFilter filter = new CorrelationIdFilter(io.micrometer.tracing.Tracer.NOOP);
+        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/x");
+        request.addHeader("traceparent", hostile);
+        filter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(), (req, res) -> {
+        });
+
+        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
+        assertThat(lines).singleElement().satisfies(line -> {
+            assertThat(line).startsWith("rejected inbound traceparent (length=" + hostile.length() + ",");
+            assertThat(line).doesNotContain("\r", "\n", "\u0007", "X-Injected", "forged-log-line");
+            assertThat(line.length()).isLessThan(200);
+        });
     }
 
     @Test
-    void passesThroughWithoutContextWhenGenerationDisabled() throws Exception {
-        MockMvc noGeneration = MockMvcBuilders.standaloneSetup(controller)
-                .addFilters(new CorrelationIdFilter(HEADER, false))
-                .build();
-
-        noGeneration.perform(get("/probe"))
-                .andExpect(status().isOk())
-                .andExpect(result -> assertThat(result.getResponse().getHeader(HEADER)).isNull());
+    void validTraceparentProducesNoMarker() throws Exception {
+        CorrelationIdFilter filter = new CorrelationIdFilter(io.micrometer.tracing.Tracer.NOOP);
+        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/x");
+        request.addHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
+        filter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(), (req, res) -> {
+        });
+        assertThat(appender.list).isEmpty();
+    }
 
-        assertThat(controller.seenContext.get()).isEmpty();
+    @Test
+    void noSpanMeansNoContextAndThePassThroughStillRuns() throws Exception {
+        CorrelationIdFilter filter = new CorrelationIdFilter(io.micrometer.tracing.Tracer.NOOP);
+        Map<String, Boolean> ran = new java.util.HashMap<>();
+        filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", "/x"),
+                new org.springframework.mock.web.MockHttpServletResponse(),
+                (req, res) -> ran.put("chain", ae.gov.dubaicustoms.platform.core.context.RequestContext
+                        .correlationId().isEmpty()));
+        assertThat(ran).containsEntry("chain", true);
     }
 }
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformCoreEnvironmentPostProcessorTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformCoreEnvironmentPostProcessorTest.java
new file mode 100644
index 0000000..a8dc9b5
--- /dev/null
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformCoreEnvironmentPostProcessorTest.java
@@ -0,0 +1,41 @@
+package ae.gov.dubaicustoms.platform.core.autoconfigure;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import java.util.Map;
+import org.junit.jupiter.api.Test;
+import org.springframework.boot.SpringApplication;
+import org.springframework.core.env.MapPropertySource;
+import org.springframework.core.env.StandardEnvironment;
+
+class PlatformCoreEnvironmentPostProcessorTest {
+
+    private final PlatformCoreEnvironmentPostProcessor processor = new PlatformCoreEnvironmentPostProcessor();
+
+    @Test
+    void turnsOnBootsTaskContextPropagationByDefault() {
+        StandardEnvironment environment = new StandardEnvironment();
+        processor.postProcessEnvironment(environment, new SpringApplication());
+        assertThat(environment.getProperty("spring.task.execution.propagate-context")).isEqualTo("true");
+        assertThat(environment.getPropertySources().contains(PlatformCoreEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
+                .isTrue();
+    }
+
+    @Test
+    void applicationValuesWin() {
+        StandardEnvironment environment = new StandardEnvironment();
+        environment.getPropertySources().addFirst(new MapPropertySource("app",
+                Map.of("spring.task.execution.propagate-context", "false")));
+        processor.postProcessEnvironment(environment, new SpringApplication());
+        assertThat(environment.getProperty("spring.task.execution.propagate-context")).isEqualTo("false");
+    }
+
+    @Test
+    void killSwitchContributesNothing() {
+        StandardEnvironment environment = new StandardEnvironment();
+        environment.getPropertySources().addFirst(new MapPropertySource("app", Map.of("dc.platform.core.enabled", "false")));
+        processor.postProcessEnvironment(environment, new SpringApplication());
+        assertThat(environment.getPropertySources().contains(PlatformCoreEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
+                .isFalse();
+    }
+}
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/TraceCorrelationIntegrationTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/TraceCorrelationIntegrationTest.java
new file mode 100644
index 0000000..4be6ef5
--- /dev/null
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/TraceCorrelationIntegrationTest.java
@@ -0,0 +1,318 @@
+package ae.gov.dubaicustoms.platform.core.autoconfigure;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
+import ae.gov.dubaicustoms.platform.core.context.RequestContext;
+import io.micrometer.observation.ObservationRegistry;
+import jakarta.servlet.Filter;
+import java.net.URI;
+import java.net.http.HttpClient;
+import java.net.http.HttpRequest;
+import java.net.http.HttpResponse;
+import java.util.HashMap;
+import java.util.HexFormat;
+import java.util.List;
+import java.util.Map;
+import java.util.Objects;
+import java.util.Optional;
+import java.util.concurrent.CompletableFuture;
+import java.util.concurrent.ConcurrentLinkedQueue;
+import java.util.concurrent.ExecutorService;
+import java.util.concurrent.Executors;
+import java.util.concurrent.Future;
+import java.util.concurrent.ThreadLocalRandom;
+import org.junit.jupiter.api.AfterAll;
+import org.junit.jupiter.api.BeforeAll;
+import org.junit.jupiter.api.Nested;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.api.TestInstance;
+import org.slf4j.MDC;
+import org.springframework.boot.SpringApplication;
+import org.springframework.boot.SpringBootConfiguration;
+import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
+import org.springframework.boot.web.server.context.WebServerApplicationContext;
+import org.springframework.boot.web.servlet.FilterRegistrationBean;
+import org.springframework.context.ConfigurableApplicationContext;
+import org.springframework.context.annotation.Bean;
+import org.springframework.core.Ordered;
+import org.springframework.core.task.AsyncTaskExecutor;
+import org.springframework.scheduling.annotation.Async;
+import org.springframework.scheduling.annotation.EnableAsync;
+import org.springframework.stereotype.Service;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.RequestHeader;
+import org.springframework.web.bind.annotation.RestController;
+import org.springframework.web.client.RestClient;
+import tools.jackson.databind.json.JsonMapper;
+
+/**
+ * End-to-end correlation contract (phase-01 scope R1/R2) through a real embedded server, Boot's own
+ * observation filter and the OpenTelemetry W3C propagator: nothing here parses {@code traceparent}
+ * by hand except the test's assertions.
+ */
+class TraceCorrelationIntegrationTest {
+
+    private static final JsonMapper JSON = JsonMapper.builder().build();
+    private static final HttpClient HTTP = HttpClient.newHttpClient();
+
+    /** Records the MDC at the very edge of the servlet chain (before tracing, after everything). */
+    static final ConcurrentLinkedQueue<Map<String, String>> EDGE_MDC = new ConcurrentLinkedQueue<>();
+
+    @SpringBootConfiguration
+    @EnableAutoConfiguration
+    @EnableAsync
+    static class App {
+
+        @Bean
+        FilterRegistrationBean<Filter> edgeMdcRecorder() {
+            Filter filter = (request, response, chain) -> {
+                EDGE_MDC.add(Optional.ofNullable(MDC.getCopyOfContextMap()).orElse(Map.of()));
+                try {
+                    chain.doFilter(request, response);
+                } finally {
+                    EDGE_MDC.add(Optional.ofNullable(MDC.getCopyOfContextMap()).orElse(Map.of()));
+                }
+            };
+            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
+            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
+            return registration;
+        }
+
+        // Instrumented like any Boot-managed client: the observation registry adds traceparent.
+        @Bean
+        RestClient selfClient(ObservationRegistry observationRegistry) {
+            return RestClient.builder().observationRegistry(observationRegistry).build();
+        }
+    }
+
+    @Service
+    static class AsyncProbe {
+
+        @Async
+        public CompletableFuture<Map<String, String>> capture() {
+            return CompletableFuture.completedFuture(snapshot());
+        }
+    }
+
+    @RestController
+    static class ProbeController {
+
+        private final AsyncProbe asyncProbe;
+        private final RestClient selfClient;
+        private final WebServerApplicationContext context;
+
+        ProbeController(AsyncProbe asyncProbe, RestClient selfClient, WebServerApplicationContext context) {
+            this.asyncProbe = asyncProbe;
+            this.selfClient = selfClient;
+            this.context = context;
+        }
+
+        @GetMapping("/probe")
+        Map<String, Object> probe() throws Exception {
+            Map<String, Object> body = new HashMap<>(snapshot());
+            body.put("async", asyncProbe.capture().get());
+            return body;
+        }
+
+        @GetMapping("/outbound")
+        Map<String, Object> outbound() {
+            Map<String, Object> body = new HashMap<>(snapshot());
+            body.put("downstreamHeaders", selfClient.get()
+                    .uri("http://127.0.0.1:" + context.getWebServer().getPort() + "/headers")
+                    .retrieve().body(Map.class));
+            return body;
+        }
+
+        @GetMapping("/headers")
+        Map<String, String> headers(@RequestHeader Map<String, String> headers) {
+            Map<String, String> lowerCased = new HashMap<>();
+            headers.forEach((name, value) -> lowerCased.put(name.toLowerCase(java.util.Locale.ROOT), value));
+            return lowerCased;
+        }
+    }
+
+    static Map<String, String> snapshot() {
+        Map<String, String> values = new HashMap<>();
+        RequestContext.correlationId().map(CorrelationId::value).ifPresent(id -> values.put("context", id));
+        Optional.ofNullable(MDC.get("traceId")).ifPresent(id -> values.put("mdcTraceId", id));
+        Optional.ofNullable(MDC.get("spanId")).ifPresent(id -> values.put("mdcSpanId", id));
+        Optional.ofNullable(MDC.get("correlationId")).ifPresent(id -> values.put("mdcCorrelationId", id));
+        values.put("thread", Thread.currentThread().getName());
+        return values;
+    }
+
+    static String randomTraceId() {
+        byte[] bytes = new byte[16];
+        ThreadLocalRandom.current().nextBytes(bytes);
+        return HexFormat.of().formatHex(bytes);
+    }
+
+    static String traceparent(String traceId) {
+        return "00-" + traceId + "-" + HexFormat.of().formatHex(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}) + "-01";
+    }
+
+    abstract static class RunningApp {
+
+        ConfigurableApplicationContext context;
+        int port;
+
+        abstract String samplingProbability();
+
+        @BeforeAll
+        void start() {
+            context = new SpringApplication(App.class, AsyncProbe.class, ProbeController.class)
+                    .run("--server.port=0", "--spring.main.banner-mode=off",
+                            "--management.tracing.sampling.probability=" + samplingProbability(),
+                            "--spring.task.execution.pool.core-size=1", "--spring.task.execution.pool.max-size=1",
+                            "--logging.level.root=WARN");
+            port = ((WebServerApplicationContext) context).getWebServer().getPort();
+        }
+
+        @AfterAll
+        void stop() {
+            if (context != null) {
+                context.close();
+            }
+        }
+
+        @SuppressWarnings("unchecked")
+        Map<String, Object> get(String path, Map<String, String> headers) throws Exception {
+            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
+            headers.forEach(request::header);
+            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
+            assertThat(response.statusCode()).as("status of %s: %s", path, response.body()).isEqualTo(200);
+            assertThat(response.headers().firstValue("X-Correlation-Id")).as("no correlation header echoed").isEmpty();
+            Map<String, Object> body = JSON.readValue(response.body(), Map.class);
+            body.put("_responseHeaders", response.headers().map());
+            return body;
+        }
+    }
+
+    @Nested
+    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
+    class AlwaysSampled extends RunningApp {
+
+        @Override
+        String samplingProbability() {
+            return "1.0";
+        }
+
+        @Test
+        void validTraceparentIsContinuedAndIsTheCorrelationId() throws Exception {
+            String traceId = randomTraceId();
+            Map<String, Object> body = get("/probe", Map.of("traceparent", traceparent(traceId)));
+            assertThat(body).containsEntry("context", traceId).containsEntry("mdcTraceId", traceId);
+            assertThat(body.get("mdcSpanId")).asString().matches("[0-9a-f]{16}");
+            assertThat(body).doesNotContainKey("mdcCorrelationId");
+        }
+
+        @Test
+        void missingTraceparentStartsANewTrace() throws Exception {
+            Map<String, Object> body = get("/probe", Map.of());
+            assertThat(body.get("context")).asString().matches("[0-9a-f]{32}");
+            assertThat(body.get("mdcTraceId")).isEqualTo(body.get("context"));
+        }
+
+        @Test
+        void invalidTraceparentsStartANewTrace() throws Exception {
+            for (String invalid : List.of("garbage", "00-" + "z".repeat(32) + "-0102030405060708-01",
+                    "00-" + "0".repeat(32) + "-0102030405060708-01", "00-" + randomTraceId() + "-" + "a".repeat(300))) {
+                Map<String, Object> body = get("/probe", Map.of("traceparent", invalid));
+                assertThat(body.get("context")).as("for %s", invalid).asString().matches("[0-9a-f]{32}")
+                        .isNotEqualTo("0".repeat(32));
+                assertThat(invalid).doesNotContain(String.valueOf(body.get("context")));
+            }
+        }
+
+        @Test
+        void strayCorrelationHeaderIsIgnored() throws Exception {
+            String traceId = randomTraceId();
+            String stray = randomTraceId();
+            Map<String, Object> body = get("/outbound",
+                    Map.of("traceparent", traceparent(traceId), "X-Correlation-Id", stray));
+            assertThat(body).containsEntry("context", traceId);
+            @SuppressWarnings("unchecked")
+            Map<String, String> downstream = (Map<String, String>) body.get("downstreamHeaders");
+            assertThat(downstream).doesNotContainKey("x-correlation-id").doesNotContainValue(stray);
+        }
+
+        @Test
+        void outboundCallCarriesTheSameTrace() throws Exception {
+            String traceId = randomTraceId();
+            Map<String, Object> body = get("/outbound", Map.of("traceparent", traceparent(traceId)));
+            @SuppressWarnings("unchecked")
+            Map<String, String> downstream = (Map<String, String>) body.get("downstreamHeaders");
+            assertThat(downstream.get("traceparent")).startsWith("00-" + traceId + "-");
+            assertThat(downstream).doesNotContainKey("x-correlation-id");
+        }
+
+        @Test
+        void asyncWorkSeesTheCallersTraceAndPoolThreadsAreCleanAfterwards() throws Exception {
+            String traceId = randomTraceId();
+            Map<String, Object> body = get("/probe", Map.of("traceparent", traceparent(traceId)));
+            @SuppressWarnings("unchecked")
+            Map<String, String> async = (Map<String, String>) body.get("async");
+            assertThat(async.get("thread")).isNotEqualTo(body.get("thread"));
+            assertThat(async).containsEntry("context", traceId).containsEntry("mdcTraceId", traceId);
+
+            // Same single pool thread, submitted from a thread with no context: nothing may linger.
+            AsyncTaskExecutor executor = context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class);
+            Map<String, String> leftover = executor.submit(TraceCorrelationIntegrationTest::snapshot).get();
+            assertThat(leftover).containsOnlyKeys("thread");
+        }
+
+        @Test
+        void mdcIsEmptyAtTheEdgeOfEveryRequest() throws Exception {
+            EDGE_MDC.clear();
+            for (int i = 0; i < 20; i++) {
+                get("/probe", Map.of("traceparent", traceparent(randomTraceId())));
+            }
+            assertThat(EDGE_MDC).hasSize(40).allSatisfy(mdc -> assertThat(mdc).isEmpty());
+        }
+
+        @Test
+        void twoHundredConcurrentRequestsNeverBleedContext() throws Exception {
+            // Plan §3.11: concurrency tests must pass 3 consecutive runs, so the round repeats in-test.
+            for (int round = 0; round < 3; round++) {
+                try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
+                    List<String> sent = java.util.stream.IntStream.range(0, 200).mapToObj(i -> randomTraceId()).toList();
+                    List<Future<Map<String, Object>>> replies = sent.stream()
+                            .map(id -> callers.submit(() -> get("/probe", Map.of("traceparent", traceparent(id)))))
+                            .toList();
+                    for (int i = 0; i < sent.size(); i++) {
+                        Map<String, Object> body = replies.get(i).get();
+                        String expected = sent.get(i);
+                        assertThat(body).as("round %d request %d", round, i)
+                                .containsEntry("context", expected).containsEntry("mdcTraceId", expected);
+                        @SuppressWarnings("unchecked")
+                        Map<String, String> async = (Map<String, String>) body.get("async");
+                        assertThat(async).containsEntry("context", expected);
+                    }
+                }
+            }
+        }
+    }
+
+    @Nested
+    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
+    class NeverSampled extends RunningApp {
+
+        @Override
+        String samplingProbability() {
+            return "0.0";
+        }
+
+        @Test
+        void unsampledRequestsStillCarryTheTraceIdInContextAndMdc() throws Exception {
+            String traceId = randomTraceId();
+            Map<String, Object> body = get("/probe",
+                    Map.of("traceparent", "00-" + traceId + "-0102030405060708-00"));
+            assertThat(body).containsEntry("context", traceId).containsEntry("mdcTraceId", traceId);
+
+            Map<String, Object> fresh = get("/probe", Map.of());
+            assertThat(fresh.get("context")).asString().matches("[0-9a-f]{32}");
+            assertThat(Objects.equals(fresh.get("mdcTraceId"), fresh.get("context"))).isTrue();
+        }
+    }
+}
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessorTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessorTest.java
new file mode 100644
index 0000000..ccfdee0
--- /dev/null
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessorTest.java
@@ -0,0 +1,91 @@
+package ae.gov.dubaicustoms.platform.core.autoconfigure.internal;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
+import ae.gov.dubaicustoms.platform.core.context.RequestContext;
+import io.micrometer.context.ContextRegistry;
+import io.micrometer.context.ContextSnapshot;
+import io.micrometer.context.ContextSnapshotFactory;
+import java.util.Map;
+import java.util.concurrent.ExecutorService;
+import java.util.concurrent.Executors;
+import org.junit.jupiter.api.AfterEach;
+import org.junit.jupiter.api.Test;
+import org.slf4j.MDC;
+
+class RequestContextThreadLocalAccessorTest {
+
+    private static final CorrelationId ID = new CorrelationId("0af7651916cd43dd8448eb211c80319c");
+
+    private final RequestContextThreadLocalAccessor accessor = new RequestContextThreadLocalAccessor();
+
+    @AfterEach
+    void clean() {
+        MDC.clear();
+    }
+
+    @Test
+    void isRegisteredThroughTheServiceLoader() {
+        assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
+                .anyMatch(candidate -> RequestContextThreadLocalAccessor.KEY.equals(candidate.key()));
+    }
+
+    @Test
+    void capturesNothingWithoutAContext() {
+        assertThat(accessor.getValue()).isNull();
+    }
+
+    @Test
+    void setValueOpensTheCapturedContextAndRestoreClosesIt() throws Exception {
+        Map<String, String> captured;
+        try (AutoCloseable scope = RequestContext.open(ID, Map.of("spanId", "b7ad6b7169203331"))) {
+            captured = accessor.getValue();
+        }
+        assertThat(captured).containsEntry("traceId", ID.value()).containsEntry("spanId", "b7ad6b7169203331");
+
+        accessor.setValue(captured);
+        assertThat(RequestContext.correlationId()).contains(ID);
+        assertThat(MDC.get("traceId")).isEqualTo(ID.value());
+        assertThat(MDC.get("spanId")).isEqualTo("b7ad6b7169203331");
+
+        accessor.restore(null);
+        assertThat(RequestContext.correlationId()).isEmpty();
+        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
+    }
+
+    @Test
+    void emptyOrIdLessSnapshotsOpenNothingAndStillUnwindOneLevel() throws Exception {
+        try (AutoCloseable outer = RequestContext.open(ID, Map.of())) {
+            accessor.setValue();
+            accessor.setValue(Map.of("tenant", "dxb"));
+            assertThat(RequestContext.correlationId()).contains(ID);
+            accessor.restore();
+            accessor.restore(Map.of());
+            // The outer scope was opened by someone else and must survive both restores.
+            assertThat(RequestContext.correlationId()).contains(ID);
+        }
+        accessor.restore();
+        assertThat(RequestContext.correlationId()).isEmpty();
+    }
+
+    @Test
+    void propagatesThroughAContextSnapshotToAnotherThread() throws Exception {
+        ContextSnapshotFactory factory = ContextSnapshotFactory.builder().build();
+        ExecutorService pool = Executors.newSingleThreadExecutor();
+        try {
+            Runnable wrapped;
+            try (AutoCloseable scope = RequestContext.open(ID, Map.of())) {
+                ContextSnapshot snapshot = factory.captureAll();
+                wrapped = snapshot.wrap((Runnable) () -> {
+                    assertThat(RequestContext.correlationId()).contains(ID);
+                });
+            }
+            pool.submit(wrapped).get();
+            assertThat(pool.submit(() -> RequestContext.correlationId().isEmpty() && MDC.get("traceId") == null).get())
+                    .as("pool thread is clean after the wrapped task").isTrue();
+        } finally {
+            pool.shutdownNow();
+        }
+    }
+}
diff --git a/docs/decisions/decision-log.md b/docs/decisions/decision-log.md
index d246732..d358dfe 100644
--- a/docs/decisions/decision-log.md
+++ b/docs/decisions/decision-log.md
@@ -605,3 +605,18 @@ One line of context per decision; details live in the commit bodies referenced.
   boots in the same time as `main` when measured alone. CI now skips it in the parallel `verify`
   (`-Dplatform.startup-budget.skip=true`) and runs it serially in its own step. Raising the baseline
   was rejected: it would hide the regressions the guard exists to catch.
+- **D89 — correlation is the W3C trace ID (hardening HD5).** The platform-minted `X-Correlation-Id`
+  is removed everywhere (core filter, REST client, tracing baggage, MDC key, ProblemDetail property).
+  `CorrelationId`/`RequestContext` keep their API and carry the trace ID; inbound `traceparent`
+  handling is left to Boot's observation filter and the OpenTelemetry W3C propagator. The platform
+  filter moves to `HIGHEST_PRECEDENCE + 10` (after the observation filter, before security).
+  Without a tracer the platform establishes no correlation context rather than inventing an id.
+- **D90 — no exception message in any 5xx body.** Including `PlatformException`s with status >= 500 and
+  Spring MVC's own 5xx bodies; 4xx business messages stay (application-authored). Simpler and safer
+  than classifying messages as safe.
+- **D91 — `rejectedValue` off by default.** Field-name redaction cannot recognise personal data
+  (national ID, email, phone); omitting the value by default is the allow-list default, opt-in via
+  `dc.platform.errors.include-rejected-values`.
+- **D92 — error-code uniqueness read from sources.** The classpath registry only sees the errors
+  module's dependencies; a source scan over every platform module's `src/main/java` covers modules no
+  single test classpath contains. Examples and the archetype are excluded (separate services).
diff --git a/docs/modules/core.md b/docs/modules/core.md
index 785a677..c8069c9 100644
--- a/docs/modules/core.md
+++ b/docs/modules/core.md
@@ -1,15 +1,27 @@
 # Core
 
-The smallest useful chassis: correlation-id propagation, the platform exception model, and the
-startup capability banner.
+The smallest useful chassis: request correlation by W3C trace ID, context propagation, the platform
+exception model, and the startup capability banner.
 
 ## What you get
 
-- **Correlation ids** — every servlet request gets a `CorrelationId` (32 lowercase hex): read from
-  the `X-Correlation-Id` header or freshly generated, published to the SLF4J `MDC`
-  (`%X{correlationId}`), readable via `RequestContext`, and echoed on the response.
+- **Correlation = the W3C trace ID** — the only correlation identifier is the trace ID of the
+  current span. A request carrying a valid `traceparent` continues that trace; a missing or invalid
+  one starts a new trace (Boot's observation filter and the OpenTelemetry W3C propagator do this — the
+  platform never parses the header). The platform filter then opens `RequestContext` with the trace
+  ID, so `RequestContext.correlationId()`, the SLF4J `MDC` (`traceId`, `spanId`) and every error body
+  (`traceId`) carry it. No custom correlation header is read, generated, echoed or sent: a caller's
+  `X-Correlation-Id` is ignored. An invalid `traceparent` is logged only as a sanitized,
+  length-capped DEBUG marker — never verbatim.
+- **Works when unsampled** — unsampled spans still carry a valid trace ID, so correlation does not
+  depend on `management.tracing.sampling.probability`.
+- **Context propagation** — the platform turns on Boot's own
+  `spring.task.execution.propagate-context`, and registers a Micrometer `ThreadLocalAccessor` for
+  `RequestContext`: `@Async` methods and Boot-built executors see the caller's trace in
+  `RequestContext` and the MDC, and pool threads are clean after each task. Executors you build
+  yourself need `ContextPropagatingTaskDecorator` (or `ContextSnapshot.wrap`) to get the same.
 - **Exception model** — subclass `PlatformException` with a stable `ErrorCode`
-  (`DC-<CAP>-<NNNN>`); phase 4 maps them to RFC-9457 problem responses.
+  (`DC-<CAP>-<NNNN>`); the errors capability maps them to RFC-9457 problem responses.
 - **Capability banner** — one INFO line at startup listing every active platform capability:
   `platform: core[ACTIVE], messaging[ACTIVE] (kafka)`.
 - **API markers** — `@PlatformApi` (SemVer-guaranteed) and `@PlatformInternal` (no guarantees).
@@ -23,26 +35,32 @@ startup capability banner.
 </dependency>
 ```
 
+Correlation needs a tracer: `platform-starter-observability` brings the Micrometer Tracing
+OpenTelemetry bridge (the archetype includes it). Without tracing on the classpath, requests run with
+no correlation context and error bodies omit `traceId` — the platform never invents an id.
+
 ## Zero-config behavior
 
-On a servlet web app: the correlation filter runs at highest filter precedence (it wraps security
-and logging), generating ids when the header is absent or malformed. In any app: the banner logs
-once the context is ready. Non-web apps get no filter; everything else is unchanged.
+On a servlet web app: the correlation filter runs at order `HIGHEST_PRECEDENCE + 10` — after Boot's
+`ServerHttpObservationFilter` (`HIGHEST_PRECEDENCE + 1`, which continues or starts the trace) and
+before Spring Security (`-100`), so 401/403 bodies and security log lines already carry the trace ID.
+In any app: Boot's task executors propagate context, and the banner logs once the context is ready.
+Non-web apps get no filter; everything else is unchanged.
 
 ## Properties
 
 | Key | Default | Meaning |
 |---|---|---|
-| `dc.platform.core.enabled` | `true` | Kill switch for the whole capability. |
+| `dc.platform.core.enabled` | `true` | Kill switch for the whole capability (tier `DEFAULT_ON`). |
 | `dc.platform.core.banner-enabled` | `true` | Log the startup capability banner. |
-| `dc.platform.core.correlation.header-name` | `X-Correlation-Id` | HTTP header carrying the id. |
-| `dc.platform.core.correlation.generate-if-missing` | `true` | Mint a fresh id when the request has none. |
+| `spring.task.execution.propagate-context` | `true` (platform default; Boot's is `false`) | Boot's own context propagation for its executors. |
 
-(Hand-written until the generated reference lands in phase 14.)
+Removed (deprecated, level `error` — setting them has no effect): `dc.platform.core.correlation.header-name`,
+`dc.platform.core.correlation.generate-if-missing`. There is no header to name and nothing to generate:
+a request without a valid `traceparent` always starts a new trace.
 
 ## Customize
 
-- Change the header name or generation policy via the properties above.
 - Contribute a capability line to the banner from your own auto-configuration:
 
 ```java
@@ -52,25 +70,40 @@ CapabilityDescriptor myCapabilityDescriptor() {
 }
 ```
 
+- Read the correlation id anywhere on a request (or `@Async`) thread:
+
+```java
+RequestContext.correlationId()
+        .map(CorrelationId::value)          // the W3C trace ID
+        .ifPresent(traceId -> receipt.setSupportReference(traceId));
+```
+
 ## Replace / Disable
 
 - Define your own `CorrelationIdFilter` bean to replace the filter (the platform registration
   wraps yours); define a `FilterRegistrationBean` named `platformCorrelationFilterRegistration`
-  to take over registration entirely.
+  to take over registration entirely. Keep it after the observation filter, or there is no span yet.
 - Define an `ApplicationRunner` named `platformBannerRunner` to replace the banner.
-- `dc.platform.core.enabled=false` switches the capability off wholesale.
+- `dc.platform.core.enabled=false` switches the capability off wholesale (no correlation context, no
+  platform executor default); tracing itself keeps working.
 
 ## Error codes
 
-None yet — core defines the model (`PlatformException`, `ErrorCode`); capabilities start
-registering codes in phase 4.
+None — core defines the model (`PlatformException`, `ErrorCode`); see the errors capability for the
+codes the platform maps.
 
 ## Testing
 
 `RequestContext.open(id, extras)` is `@PlatformInternal` but usable from tests to establish a
-context around code under test (try-with-resources). The filter is a plain
-`OncePerRequestFilter`: `MockMvcBuilders.standaloneSetup(controller).addFilters(new
-CorrelationIdFilter("X-Correlation-Id", true))` exercises it without a full context.
+context around code under test (try-with-resources). For a real request, send a `traceparent`
+(`00-<32 hex trace id>-<16 hex span id>-01`) and assert the trace ID in the response's ProblemDetail or
+the JSON log's `traceId`; the archetype's `HttpIntegrationTestSupport` does exactly that.
+
+## Upgrading from the `X-Correlation-Id` model
+
+Callers that sent `X-Correlation-Id` should send `traceparent` instead (any OpenTelemetry/W3C client
+does this automatically). Log queries on `correlationId` become queries on `traceId`; ProblemDetail
+consumers read `traceId`. `CorrelationId` and `RequestContext.correlationId()` keep their API.
 
 ## Local dev notes
 
diff --git a/docs/modules/errors.md b/docs/modules/errors.md
index cc5f800..1f4871e 100644
--- a/docs/modules/errors.md
+++ b/docs/modules/errors.md
@@ -1,8 +1,8 @@
 # Errors
 
 RFC-9457 problem responses with stable, machine-readable error codes: throw a
-`BusinessException`, get a correct `application/problem+json` body with `code`, `correlationId`,
-and `timestamp` — correlated with the JSON log line.
+`BusinessException`, get a correct `application/problem+json` body with `code`, `traceId`,
+`timestamp` and `retryable` — correlated with the JSON log line by the W3C trace ID.
 
 ## What you get
 
@@ -10,16 +10,25 @@ and `timestamp` — correlated with the JSON log line.
   (404), `ConflictException` (409); each carries an `ErrorCode` (`DC-<CAP>-<NNNN>`) that becomes
   the problem `type`/`title` and the `code` extension.
 - **Platform exception mapping** — any other `PlatformException` maps to 500 with its own code;
-  anything unmapped maps to 500 with `DC-CORE-0500` and a generic detail (the message is never
-  leaked — the full exception goes to the log, correlated via MDC).
+  anything unmapped maps to 500 with `DC-CORE-0500`. **No 5xx body ever carries an exception
+  message** — every 5xx gets the generic detail `An unexpected error occurred.`; the full exception
+  goes to the log with the trace ID. 4xx business exceptions keep their application-authored message.
+- **Framework errors get the same contract** — Spring MVC's own rejections (404, 405, 406, 415, …) keep
+  their statuses and gain `code` (`DC-CORE-0499`), `traceId`, `timestamp`, `retryable`; an unreadable
+  or malformed body is `DC-CORE-0410` with a fixed detail (the JSON parser's message, which names
+  classes and offsets, never reaches the caller).
+- **`retryable`** — `true` for 429, 502, 503, 504, otherwise `false`: a hint for clients deciding
+  whether to retry the identical request after backing off.
 - **Validation mapping** — `@Valid` body failures and method-validation
   `ConstraintViolationException`s map to 400 with an `errors[]` extension
-  (`field`, `message`, `rejectedValue`); rejected values for password/secret/token-shaped fields
-  are `REDACTED`.
+  (`field`, `message`). `rejectedValue` is **omitted by default** — rejected values are raw user
+  input and may be personal data; turn it on with `dc.platform.errors.include-rejected-values=true`,
+  and even then password/secret/token-shaped fields are `REDACTED`.
 - **Customization SPI-lite** — `ProblemDetailCustomizer` beans applied in `@Order` to every
   outgoing problem body.
-- **Error-code registry** — a build-time gate asserts code format and platform-wide uniqueness
-  and exports `target/error-codes.csv` (feeds the phase-14 reference).
+- **Error-code registry** — build-time gates assert code format and platform-wide uniqueness:
+  one scans the classpath and exports `target/error-codes.csv` (feeds the reference), the other reads
+  the production sources of every platform module, so a duplicate anywhere fails the build.
 
 ## Starter coordinates
 
@@ -52,8 +61,9 @@ throw new NotFoundException(new ErrorCode("DC-ORDER-0404"), "order 42 not found"
   "detail": "order 42 not found",
   "instance": "/orders/42",
   "code": "DC-ORDER-0404",
-  "correlationId": "0123456789abcdef0123456789abcdef",
-  "timestamp": "2026-01-01T00:00:00Z"
+  "traceId": "0123456789abcdef0123456789abcdef",
+  "timestamp": "2026-01-01T00:00:00Z",
+  "retryable": false
 }
 ```
 
@@ -65,6 +75,10 @@ throw new NotFoundException(new ErrorCode("DC-ORDER-0404"), "order 42 not found"
 | `dc.platform.errors.include-stacktrace` | `false` | Add a `stacktrace` extension (debug only). |
 | `dc.platform.errors.type-base-uri` | `https://errors.dc.com/` | Base URI forming the problem `type`. |
 | `dc.platform.errors.map-validation` | `true` | Map validation failures to 400 + `errors[]`. |
+| `dc.platform.errors.include-rejected-values` | `false` | Echo invalid values as `errors[].rejectedValue` (raw user input; credentials stay redacted). |
+
+The `type` base URI (`https://errors.dc.com/`) is a placeholder on a domain the platform does not own;
+set `type-base-uri` to your organisation's problem-type namespace (open issue G1-08).
 
 (Hand-written until the generated reference lands in phase 14.)
 
@@ -93,7 +107,11 @@ Customizers run last, in order, on every problem body the platform emits.
 | Code | Meaning |
 |---|---|
 | `DC-CORE-0400` | Request validation failed (`errors[]` lists the violations). |
+| `DC-CORE-0410` | Request body unreadable or malformed (e.g. invalid JSON). |
+| `DC-CORE-0499` | Any other request the servlet stack rejected (404, 405, 406, 415, …); status preserved. |
 | `DC-CORE-0500` | Unhandled exception; generic body, full details in the log. |
+| `DC-SEC-0401` | Missing or unacceptable credentials (security entry point). |
+| `DC-SEC-0403` | Authenticated, but lacking the required authority (security denied handler). |
 
 Applications register their own codes by declaring `static final ErrorCode` constants — the
 registry gate picks them up automatically.
@@ -103,7 +121,7 @@ registry gate picks them up automatically.
 The advices are plain beans:
 `MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler)` exercises mappings
 without a full context. Open a `RequestContext` (try-with-resources) around the call to see the
-`correlationId` extension. Inject a fixed `Clock` bean to make `timestamp` deterministic.
+`traceId` extension. Inject a fixed `Clock` bean to make `timestamp` deterministic.
 
 ## Local dev notes
 
diff --git a/docs/modules/logging.md b/docs/modules/logging.md
index 06cde60..2de066b 100644
--- a/docs/modules/logging.md
+++ b/docs/modules/logging.md
@@ -6,11 +6,11 @@ application context exists — and readable console output when a human is watch
 ## What you get
 
 - **JSON by default** — one logstash-encoded line per event with ECS-ish fields:
-  `@timestamp`, `level`, `logger`, `message`, `service`, `correlationId` (and every other MDC
+  `@timestamp`, `level`, `logger`, `message`, `service`, `traceId`, `spanId` (and every other MDC
   entry), `stack_trace`.
 - **Console for humans** — activate the `local` profile and output falls back to Boot's
   human-readable console format (explicit `dc.platform.logging.format` always wins).
-- **Correlation built in** — the core filter publishes `correlationId` to the MDC; the encoder
+- **Correlation built in** — the core filter publishes the W3C `traceId` (and `spanId`) to the MDC; the encoder
   lifts MDC entries into JSON fields, so log lines correlate with problem responses for free.
 - **`Kv.of(key, value)`** — structured argument helper that stays readable in console format.
 - **`LogSanitizer` SPI-lite** — contribute beans to scrub sensitive values; collected by the
@@ -35,7 +35,7 @@ through a lowest-precedence property source named `platform-logging-defaults` (v
 ```json
 {"@timestamp":"2026-01-01T00:00:00.000Z","message":"order accepted orderId=42",
  "logger":"ae.gov.dc.orders.OrderService","level":"INFO","service":"orders",
- "correlationId":"0123456789abcdef0123456789abcdef"}
+ "traceId":"0123456789abcdef0123456789abcdef","spanId":"b7ad6b7169203331"}
 ```
 
 ## Properties
diff --git a/docs/modules/observability.md b/docs/modules/observability.md
index 65c0055..14aacdd 100644
--- a/docs/modules/observability.md
+++ b/docs/modules/observability.md
@@ -16,8 +16,9 @@ platform capabilities a running service actually has.
   capabilities are ACTIVE in this running service, with provider detail.
 - **Prometheus locally, OTLP when you have a collector** — the starter ships both registries;
   OTLP export is OFF by default because laptops have no collector.
-- **Correlation via baggage, never metric tags** — the correlation id is high-cardinality (one
-  value per request); it propagates downstream through tracing baggage (`X-Correlation-Id`) and
+- **Correlation via the trace, never metric tags** — the correlation id is the W3C trace ID,
+  high-cardinality (one value per request); it propagates downstream in `traceparent` (no custom
+  baggage header any more) and
   is never added as a metric tag or observation key-value.
 
 ## Starter coordinates
@@ -41,7 +42,7 @@ property source named `platform-observability-defaults` (visible in `/actuator/e
 | `management.endpoint.health.group.liveness.include` | `livenessState` |
 | `management.endpoint.health.group.readiness.include` | `readinessState,db,rabbit,redis` |
 | `management.endpoint.health.validate-group-membership` | `false` (the readiness list names optional members) |
-| `management.tracing.baggage.remote-fields` | `X-Correlation-Id` |
+| `management.tracing.baggage.remote-fields` | not set (was `X-Correlation-Id`; removed in the hardening phase 1) |
 | `management.otlp.metrics.export.enabled` | `false` (only while `otlp.enabled=false`) |
 | `management.tracing.export.otlp.enabled` | `false` (only while `otlp.enabled=false`) |
 
diff --git a/docs/modules/restclient.md b/docs/modules/restclient.md
index 7753f2a..6fc3b9c 100644
--- a/docs/modules/restclient.md
+++ b/docs/modules/restclient.md
@@ -1,20 +1,21 @@
 # REST Client
 
 Platform-conventional outbound REST calls: inject `PlatformRestClientFactory` instead of
-`RestClient.Builder` to get correlation propagation, sane timeouts, and error mapping on every
+`RestClient.Builder` to get W3C trace propagation, sane timeouts, and error mapping on every
 outbound call for free.
 
 ## What you get
 
 - **`PlatformRestClientFactory`** — `builder(String clientName)` returns a pre-configured
   `RestClient.Builder` on the JDK `HttpClient` request factory.
-- **Correlation propagation** — the current `RequestContext` correlation id (if any) is sent as
-  an `X-Correlation-Id` header on every outbound call (matches the core capability's default
-  header name).
+- **Trace propagation** — every client is instrumented with the observation registry, so the
+  current trace travels as the standard `traceparent` header (the platform's only correlation id).
+  No custom correlation header is sent or read (the former `X-Correlation-Id` behaviour is gone).
 - **Per-client timeouts** — a `connect-timeout`/`read-timeout` default, overridable per client
   name.
 - **Error mapping** — any non-2xx response throws `RemoteCallException` (status, 1KB-truncated
-  body, remote correlation id echoed by the callee).
+  body). `remoteCorrelationId()` is deprecated and always empty for platform-created exceptions:
+  caller and callee share one trace, so the caller's own `traceId` joins both sides' logs.
 - **`PlatformRestClientCustomizer` extension point** — ordered beans that further customize a
   named client's builder.
 - **Observations** — every call records an `http.client.requests` observation (metrics/tracing via
@@ -65,7 +66,7 @@ class OrdersClient {
 | Key | Default | Meaning |
 |---|---|---|
 | `dc.platform.restclient.enabled` | `true` | Kill switch for the whole capability. |
-| `dc.platform.restclient.propagate-correlation` | `true` | Send the current correlation id as an outbound header. |
+| ~~`dc.platform.restclient.propagate-correlation`~~ | — | Removed (deprecated, level `error`): `traceparent` is always propagated. |
 | `dc.platform.restclient.defaults.connect-timeout` | `2s` | Default connect timeout. |
 | `dc.platform.restclient.defaults.read-timeout` | `10s` | Default read timeout. |
 | `dc.platform.restclient.clients.<name>.connect-timeout` | (defaults) | Per-client connect timeout override. |
diff --git a/docs/quickstart.md b/docs/quickstart.md
index 9f7b2ed..76c4307 100644
--- a/docs/quickstart.md
+++ b/docs/quickstart.md
@@ -1,7 +1,7 @@
 # Quickstart — a new platform service in under 10 minutes
 
 The DC Platform ships a Maven archetype that generates a ready-to-run service wired with the
-golden-path chassis (correlation IDs, JSON logging, RFC-9457 errors, validation, metrics/tracing,
+golden-path chassis (W3C trace correlation, JSON logging, RFC-9457 errors, validation, metrics/tracing,
 OpenAPI, and an authenticated-by-default security filter chain). You add business logic; the platform
 does the cross-cutting work.
 
@@ -49,7 +49,7 @@ orders-service/
   src/test/java/.../HelloControllerTest.java     @PlatformWebTest + TestTokens
   src/test/java/.../ApplicationSmokeTest.java    @PlatformTest (boots the full context)
   src/test/java/.../SecurityIntegrationTest.java real HTTP: 401 for missing/untrusted/expired tokens, 200 via a loopback JWKS
-  src/test/java/.../StructuredLoggingTest.java   parses the emitted JSON log events: fields, correlation, no tokens
+  src/test/java/.../StructuredLoggingTest.java   parses the emitted JSON log events: fields, traceId, no tokens
   src/test/java/.../data/NoteRepositoryTest.java Flyway-created schema, CRUD across transactions, rollback, constraint
   src/test/java/.../client/GreetingClientTest.java real calls to a loopback stub: JSON, errors, timeouts, relay
   src/test/java/.../PlatformConformanceTest.java runs PlatformUsageRules over your code
diff --git a/docs/testing.md b/docs/testing.md
index 2cd47ec..ad60bae 100644
--- a/docs/testing.md
+++ b/docs/testing.md
@@ -71,7 +71,7 @@ class OrdersEventsTest {
   ```
 
 - **`assertThatProblem(...)`** — AssertJ assertions over RFC-9457 `ProblemDetail` bodies, including
-  the platform `code`/`correlationId` extensions:
+  the platform `code`/`traceId` extensions:
 
   ```java
   assertThatProblem(response).hasStatus(404).hasCode("DC-ORD-0001");
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorsProperties.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorsProperties.java
index abe3a87..0a711b0 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorsProperties.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorsProperties.java
@@ -15,6 +15,9 @@ import org.springframework.validation.annotation.Validated;
  *        never enable in production)
  * @param typeBaseUri base URI prepended to the error code to form the problem {@code type}
  * @param mapValidation map Bean Validation failures to 400 problem responses with {@code errors[]}
+ * @param includeRejectedValues echo each invalid value as {@code errors[].rejectedValue} (off by
+ *        default: rejected values are raw user input and may be personal data; credential-shaped
+ *        fields stay redacted even when on)
  * @since 0.1.0
  */
 @Validated
@@ -27,5 +30,7 @@ public record ErrorsProperties(
         /** Base URI prepended to the error code to form the problem type. */
         @DefaultValue("https://errors.dc.com/") String typeBaseUri,
         /** Map Bean Validation failures to 400 problem responses with an errors[] extension. */
-        @DefaultValue("true") boolean mapValidation) {
+        @DefaultValue("true") boolean mapValidation,
+        /** Echo invalid values as errors[].rejectedValue (raw user input; off by default). */
+        @DefaultValue("false") boolean includeRejectedValues) {
 }
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandler.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandler.java
index d4dc23f..acbb95d 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandler.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandler.java
@@ -62,7 +62,7 @@ public class PlatformConstraintViolationHandler {
             Map<String, Object> entry = new LinkedHashMap<>();
             entry.put("field", field);
             entry.put("message", violation.getMessage());
-            entry.put("rejectedValue", factory.redact(field, violation.getInvalidValue()));
+            factory.putRejectedValue(entry, field, violation.getInvalidValue());
             errors.add(entry);
         }
         ProblemDetail problem = factory.create(400, PlatformExceptionHandler.VALIDATION_FAILED,
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java
index 7c52391..2a3ca7a 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java
@@ -27,10 +27,13 @@ import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExcep
 /**
  * Platform {@code @RestControllerAdvice}: maps {@link PlatformException}s, request-body
  * validation failures, and unexpected exceptions to RFC-9457 {@link ProblemDetail} responses
- * with {@code code}, {@code correlationId}, and {@code timestamp} extensions.
+ * with {@code code}, {@code traceId}, {@code timestamp} and {@code retryable} extensions.
  *
  * <p>Extends {@link ResponseEntityExceptionHandler} so the servlet stack's own exceptions
- * (404/405/406/415, …) keep their proper statuses instead of falling into the 500 catch-all.
+ * (404/405/406/415, …) keep their proper statuses instead of falling into the 500 catch-all; their
+ * bodies get the same platform extensions ({@code DC-CORE-0410} for an unreadable request body,
+ * {@code DC-CORE-0499} for any other rejected request). No 5xx body ever carries an exception
+ * message: the full exception is logged with the trace ID instead.
  * Ordered at {@link Ordered#LOWEST_PRECEDENCE} so application advice beans win first.
  *
  * <p>Registered by {@code PlatformErrorHandlingAutoConfiguration}; define your own bean named
@@ -48,6 +51,15 @@ public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {
     /** Code for request-body validation failures mapped to 400 with {@code errors[]}. */
     static final ErrorCode VALIDATION_FAILED = new ErrorCode("DC-CORE-0400");
 
+    /** Code for a request body that could not be read or parsed (e.g. malformed JSON). */
+    static final ErrorCode MALFORMED_REQUEST = new ErrorCode("DC-CORE-0410");
+
+    /** Code for every other request the servlet stack rejected (404, 405, 406, 415, …). */
+    static final ErrorCode REQUEST_REJECTED = new ErrorCode("DC-CORE-0499");
+
+    /** Detail for an unreadable body: the parser's own message names classes and offsets. */
+    private static final String MALFORMED_REQUEST_DETAIL = "The request body is malformed or unreadable.";
+
     private static final Logger log = LoggerFactory.getLogger(PlatformExceptionHandler.class);
 
     private final ErrorsProperties properties;
@@ -66,8 +78,9 @@ public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {
 
     /**
      * Maps platform exceptions: business exceptions take their {@code statusHint}, all other
-     * platform exceptions are infrastructure failures and map to 500. The exception message is
-     * platform/application-authored and treated as safe for the response body.
+     * platform exceptions are infrastructure failures and map to 500. For 4xx the exception message
+     * is application-authored and becomes the {@code detail}; for 5xx the body carries only the
+     * generic detail (infrastructure messages name hosts, clients and statuses).
      *
      * @param exception the platform exception; never {@code null}
      * @param request the current request; never {@code null}
@@ -116,14 +129,13 @@ public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {
             Map<String, Object> entry = new LinkedHashMap<>();
             entry.put("field", fieldError.getField());
             entry.put("message", fieldError.getDefaultMessage());
-            entry.put("rejectedValue", factory.redact(fieldError.getField(), fieldError.getRejectedValue()));
+            factory.putRejectedValue(entry, fieldError.getField(), fieldError.getRejectedValue());
             errors.add(entry);
         });
         exception.getBindingResult().getGlobalErrors().forEach(globalError -> {
             Map<String, Object> entry = new LinkedHashMap<>();
             entry.put("field", globalError.getObjectName());
             entry.put("message", globalError.getDefaultMessage());
-            entry.put("rejectedValue", null);
             errors.add(entry);
         });
         ProblemDetail problem = factory.create(
@@ -132,6 +144,40 @@ public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {
         return ResponseEntity.badRequest().body(problem);
     }
 
+    /**
+     * Every body Spring MVC builds for its own exceptions passes here: it gets the platform
+     * extensions, and an unreadable body never exposes the parser's message.
+     */
+    @Override
+    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
+            WebRequest request) {
+        if (body instanceof ProblemDetail problem
+                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
+            ErrorCode code = REQUEST_REJECTED;
+            if (statusCode.value() >= 500) {
+                code = UNEXPECTED_ERROR;
+            } else if (problem.getDetail() != null && problem.getDetail().startsWith("Failed to read request")) {
+                code = MALFORMED_REQUEST;
+                problem.setDetail(MALFORMED_REQUEST_DETAIL);
+            }
+            if (problem.getInstance() == null && instancePathOf(request) != null) {
+                problem.setInstance(java.net.URI.create(instancePathOf(request)));
+            }
+            factory.decorate(problem, code, null);
+        }
+        return super.createResponseEntity(body, headers, statusCode, request);
+    }
+
+    @Override
+    protected ResponseEntity<Object> handleHttpMessageNotReadable(
+            org.springframework.http.converter.HttpMessageNotReadableException exception, HttpHeaders headers,
+            HttpStatusCode status, WebRequest request) {
+        log.debug("unreadable request body [{}]", MALFORMED_REQUEST.value(), exception);
+        ProblemDetail problem = factory.create(400, MALFORMED_REQUEST, MALFORMED_REQUEST_DETAIL,
+                instancePathOf(request), exception);
+        return ResponseEntity.badRequest().headers(headers).body(problem);
+    }
+
     private static String instancePathOf(WebRequest request) {
         return request instanceof ServletWebRequest servletRequest
                 ? servletRequest.getRequest().getRequestURI()
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactory.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactory.java
index c835747..bc6108d 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactory.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactory.java
@@ -12,12 +12,20 @@ import java.time.Clock;
 import java.time.Instant;
 import java.util.List;
 import java.util.Locale;
+import java.util.Map;
+import java.util.Set;
 import org.springframework.http.ProblemDetail;
 
 // Shared ProblemDetail assembly for the platform advice beans: one place sets type/title/detail/
-// instance and the code/correlationId/timestamp extensions, and applies customizers in order.
+// instance and the code/traceId/timestamp/retryable extensions, and applies customizers in order.
 public final class ProblemDetailFactory {
 
+    /** Generic body detail for every 5xx: server-side messages routinely carry internals. */
+    public static final String GENERIC_SERVER_ERROR_DETAIL = "An unexpected error occurred.";
+
+    // Statuses a client may retry the identical request on (after backing off).
+    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);
+
     private final ErrorsProperties properties;
     private final List<ProblemDetailCustomizer> customizers;
     private final Clock clock;
@@ -30,22 +38,35 @@ public final class ProblemDetailFactory {
 
     /**
      * Builds the fully populated problem body and applies the customizers in order.
-     * Timestamp/correlation extensions are ISO-8601 strings, not temporal objects, so the JSON
+     * Timestamp/trace extensions are ISO-8601 / hex strings, not temporal objects, so the JSON
      * shape does not depend on which Jackson generation/modules the application runs.
      */
     public ProblemDetail create(int status, ErrorCode code, String detail, String instancePath, Throwable source) {
         ProblemDetail problem = ProblemDetail.forStatus(status);
-        problem.setType(URI.create(properties.typeBaseUri() + code.value()));
-        problem.setTitle(code.value());
-        problem.setDetail(detail);
+        problem.setDetail(status >= 500 ? GENERIC_SERVER_ERROR_DETAIL : detail);
         if (instancePath != null) {
             problem.setInstance(URI.create(instancePath));
         }
+        return decorate(problem, code, source);
+    }
+
+    /**
+     * Adds the platform contract to a problem body built elsewhere (e.g. by Spring MVC for a 404,
+     * 405 or unreadable body): type/title from the code, plus {@code code}, {@code traceId},
+     * {@code timestamp} and {@code retryable}. A 5xx body's detail is replaced by the generic text.
+     */
+    public ProblemDetail decorate(ProblemDetail problem, ErrorCode code, Throwable source) {
+        problem.setType(URI.create(properties.typeBaseUri() + code.value()));
+        problem.setTitle(code.value());
+        if (problem.getStatus() >= 500) {
+            problem.setDetail(GENERIC_SERVER_ERROR_DETAIL);
+        }
         problem.setProperty("code", code.value());
         RequestContext.correlationId().map(CorrelationId::value)
-                .ifPresent(id -> problem.setProperty("correlationId", id));
+                .ifPresent(id -> problem.setProperty("traceId", id));
         problem.setProperty("timestamp", Instant.now(clock).toString());
-        if (properties.includeStacktrace()) {
+        problem.setProperty("retryable", RETRYABLE_STATUSES.contains(problem.getStatus()));
+        if (properties.includeStacktrace() && source != null) {
             problem.setProperty("stacktrace", stackTraceOf(source));
         }
         customizers.forEach(customizer -> customizer.customize(problem, source));
@@ -53,9 +74,19 @@ public final class ProblemDetailFactory {
     }
 
     /**
-     * Redacts rejected values for credential-shaped fields: rejected values echo raw user input,
-     * and for password/secret/token fields that input must never round-trip through an error
-     * response (or the logs that capture it).
+     * Adds {@code rejectedValue} to a validation-error entry only when
+     * {@code dc.platform.errors.include-rejected-values=true}: rejected values echo raw user input,
+     * which may be personal data. Even then, credential-shaped fields are redacted.
+     */
+    public void putRejectedValue(Map<String, Object> entry, String fieldName, Object rejectedValue) {
+        if (properties.includeRejectedValues()) {
+            entry.put("rejectedValue", redact(fieldName, rejectedValue));
+        }
+    }
+
+    /**
+     * Redacts rejected values for credential-shaped fields: for password/secret/token fields the
+     * input must never round-trip through an error response (or the logs that capture it).
      */
     public Object redact(String fieldName, Object rejectedValue) {
         String normalized = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java
new file mode 100644
index 0000000..1a97ac9
--- /dev/null
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java
@@ -0,0 +1,100 @@
+package ae.gov.dubaicustoms.platform.errors.autoconfigure;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import java.io.IOException;
+import java.io.UncheckedIOException;
+import java.nio.file.Files;
+import java.nio.file.Path;
+import java.util.ArrayList;
+import java.util.List;
+import java.util.Map;
+import java.util.regex.Matcher;
+import java.util.regex.Pattern;
+import java.util.stream.Collectors;
+import java.util.stream.Stream;
+import org.junit.jupiter.api.Test;
+
+/**
+ * Build-time uniqueness of error codes across EVERY platform module (phase-01 scope R3.7).
+ * {@link ErrorCodeRegistryTest} only sees the classes on this module's test classpath, which misses
+ * codes declared in modules this one does not depend on (REST client, messaging, locking, storage,
+ * security…). This gate reads the production sources of the whole repository instead, so a duplicate
+ * anywhere fails the build. Consumer examples and the archetype are excluded: they model separate
+ * services with their own namespaces.
+ */
+class ErrorCodeSourceUniquenessTest {
+
+    private static final Pattern DECLARATION = Pattern.compile("new ErrorCode\\(\"([^\"]+)\"\\)");
+    private static final Pattern FORMAT = Pattern.compile("^DC-[A-Z]{2,8}-\\d{4}$");
+
+    private record Declaration(String code, Path file) {
+    }
+
+    @Test
+    void everyPlatformErrorCodeIsWellFormedAndDeclaredOnce() throws IOException {
+        List<Declaration> declarations = scan(repositoryRoot());
+
+        // A near-empty result means the gate stopped looking, not that the platform has no codes.
+        assertThat(declarations).extracting(Declaration::code)
+                .contains("DC-CORE-0500", "DC-CORE-0400", "DC-CORE-0410", "DC-CORE-0499",
+                        "DC-SEC-0401", "DC-SEC-0403", "DC-RCLIENT-0500", "DC-MSG-0001");
+        assertThat(declarations).allSatisfy(d -> assertThat(d.code()).as("format in %s", d.file()).matches(FORMAT));
+
+        Map<String, List<Path>> byCode = declarations.stream().collect(
+                Collectors.groupingBy(Declaration::code, Collectors.mapping(Declaration::file, Collectors.toList())));
+        Map<String, List<Path>> duplicates = byCode.entrySet().stream()
+                .filter(entry -> entry.getValue().size() > 1)
+                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
+        assertThat(duplicates).as("error codes are contracts and must be unique platform-wide").isEmpty();
+    }
+
+    private static List<Declaration> scan(Path root) throws IOException {
+        List<Declaration> found = new ArrayList<>();
+        try (Stream<Path> files = Files.walk(root)) {
+            files.filter(file -> file.toString().endsWith(".java"))
+                    .filter(ErrorCodeSourceUniquenessTest::isPlatformProductionSource)
+                    .forEach(file -> found.addAll(declarationsIn(file)));
+        }
+        return found;
+    }
+
+    private static boolean isPlatformProductionSource(Path file) {
+        String path = file.toString().replace('\\', '/');
+        return path.contains("/src/main/java/")
+                && !path.contains("/target/")
+                && !path.contains("/examples/")
+                && !path.contains("/tooling/")
+                && !path.contains("/scratch/")
+                && !path.contains("/tmpviol/");
+    }
+
+    private static List<Declaration> declarationsIn(Path file) {
+        List<Declaration> found = new ArrayList<>();
+        try {
+            for (String line : Files.readAllLines(file)) {
+                String trimmed = line.trim();
+                // Javadoc and comments show example codes; only real declarations count.
+                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
+                    continue;
+                }
+                Matcher matcher = DECLARATION.matcher(line);
+                while (matcher.find()) {
+                    found.add(new Declaration(matcher.group(1), file));
+                }
+            }
+        } catch (IOException e) {
+            throw new UncheckedIOException(e);
+        }
+        return found;
+    }
+
+    private static Path repositoryRoot() {
+        Path dir = Path.of("").toAbsolutePath();
+        while (dir != null && !Files.exists(dir.resolve("build/platform-parent/pom.xml"))) {
+            dir = dir.getParent();
+        }
+        assertThat(dir).as("repository root above %s", Path.of("").toAbsolutePath()).isNotNull();
+        return dir;
+    }
+}
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandlerTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandlerTest.java
index 0cb823b..4f7fc7d 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandlerTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandlerTest.java
@@ -26,7 +26,12 @@ class PlatformConstraintViolationHandlerTest {
     }
 
     private ProblemDetail handle(Object invalidBean) {
-        ErrorsProperties properties = new ErrorsProperties(true, false, "https://errors.dc.com/", true);
+        return handle(invalidBean, true);
+    }
+
+    private ProblemDetail handle(Object invalidBean, boolean includeRejectedValues) {
+        ErrorsProperties properties = new ErrorsProperties(true, false, "https://errors.dc.com/", true,
+                includeRejectedValues);
         PlatformConstraintViolationHandler handler = new PlatformConstraintViolationHandler(
                 new ProblemDetailFactory(properties, List.of(), FIXED_CLOCK));
         try (var factory = Validation.buildDefaultValidatorFactory()) {
@@ -64,4 +69,16 @@ class PlatformConstraintViolationHandlerTest {
             assertThat(entry).containsEntry("rejectedValue", "REDACTED");
         });
     }
+
+    @Test
+    void rejectedValuesAreOmittedByDefault() {
+        ProblemDetail problem = handle(new Credentials("", "0123456789ab"), false);
+
+        @SuppressWarnings("unchecked")
+        List<Map<String, Object>> errors = (List<Map<String, Object>>) problem.getProperties().get("errors");
+        assertThat(errors).singleElement().satisfies(entry -> {
+            assertThat(entry).containsEntry("field", "user");
+            assertThat(entry).doesNotContainKey("rejectedValue");
+        });
+    }
 }
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorHandlingAutoConfigurationTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorHandlingAutoConfigurationTest.java
index 404fda3..e14a0d9 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorHandlingAutoConfigurationTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorHandlingAutoConfigurationTest.java
@@ -68,6 +68,30 @@ class PlatformErrorHandlingAutoConfigurationTest {
                 });
     }
 
+    @Test
+    void consumerSubclassReplacesThePlatformAdvice() {
+        // Plan §7.1: a real override, not annotation presence — the consumer's handler is the one
+        // in the context and the platform's is not created alongside it.
+        ErrorsProperties properties = new ErrorsProperties(true, false, "https://problems.example/", true, false);
+        PlatformExceptionHandler mine = new PlatformExceptionHandler(properties,
+                new ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory(properties,
+                        java.util.List.of(), java.time.Clock.systemUTC())) {
+        };
+        runner.withBean("platformExceptionHandler", PlatformExceptionHandler.class, () -> mine)
+                .run(context -> {
+                    assertThat(context).getBeans(PlatformExceptionHandler.class).hasSize(1);
+                    assertThat(context.getBean(PlatformExceptionHandler.class)).isSameAs(mine);
+                });
+    }
+
+    @Test
+    void configurationDefaultsSnapshot() {
+        // Plan §7.2: a changed default fails here until the snapshot is consciously updated and the
+        // change is recorded in the phase changelog. include-rejected-values=false is new in phase 1.
+        runner.run(context -> assertThat(context.getBean(ErrorsProperties.class))
+                .isEqualTo(new ErrorsProperties(true, false, "https://errors.dc.com/", true, false)));
+    }
+
     @Test
     void propertiesBindFromKebabKeys() {
         runner.withPropertyValues(
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
index 53d1054..ddc9d2b 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
@@ -47,7 +47,11 @@ class PlatformExceptionHandlerTest {
     }
 
     private static ErrorsProperties defaults() {
-        return new ErrorsProperties(true, false, "https://errors.dc.com/", true);
+        return new ErrorsProperties(true, false, "https://errors.dc.com/", true, false);
+    }
+
+    private static ErrorsProperties withRejectedValues() {
+        return new ErrorsProperties(true, false, "https://errors.dc.com/", true, true);
     }
 
     @Test
@@ -67,8 +71,9 @@ class PlatformExceptionHandlerTest {
                       "detail": "order 42 not found",
                       "instance": "/missing",
                       "code": "DC-TEST-0404",
-                      "correlationId": "%s",
-                      "timestamp": "2026-01-01T00:00:00Z"
+                      "traceId": "%s",
+                      "timestamp": "2026-01-01T00:00:00Z",
+                      "retryable": false
                     }""".formatted(CORRELATION),
                     result.getResponse().getContentAsString(), JSONCompareMode.STRICT);
             assertThat(result.getResponse().getContentType())
@@ -84,11 +89,39 @@ class PlatformExceptionHandlerTest {
     }
 
     @Test
-    void infrastructurePlatformExceptionMapsTo500WithItsCode() throws Exception {
-        mockMvc(defaults(), List.of()).perform(get("/infra"))
+    void infrastructurePlatformExceptionMapsTo500WithItsCodeButNotItsMessage() throws Exception {
+        MvcResult result = mockMvc(defaults(), List.of()).perform(get("/infra"))
                 .andExpect(status().isInternalServerError())
                 .andExpect(jsonPath("$.code").value("DC-TEST-0500"))
-                .andExpect(jsonPath("$.detail").value("transport down"));
+                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
+                .andExpect(jsonPath("$.retryable").value(false))
+                .andReturn();
+        assertThat(result.getResponse().getContentAsString()).doesNotContain("transport down");
+    }
+
+    @Test
+    void malformedJsonMapsTo400WithoutParserInternals() throws Exception {
+        MvcResult result = mockMvc(defaults(), List.of())
+                .perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"name\": [oops"))
+                .andExpect(status().isBadRequest())
+                .andExpect(jsonPath("$.code").value("DC-CORE-0410"))
+                .andExpect(jsonPath("$.detail").value("The request body is malformed or unreadable."))
+                .andExpect(jsonPath("$.retryable").value(false))
+                .andReturn();
+        assertThat(result.getResponse().getContentAsString())
+                .doesNotContainIgnoringCase("jackson").doesNotContain("NewOrder", "line:", "column:");
+    }
+
+    @Test
+    void frameworkRejectionsGetThePlatformExtensions() throws Exception {
+        try (AutoCloseable scope = RequestContext.open(new CorrelationId(CORRELATION), Map.of())) {
+            mockMvc(defaults(), List.of()).perform(post("/missing"))
+                    .andExpect(status().isMethodNotAllowed())
+                    .andExpect(jsonPath("$.code").value("DC-CORE-0499"))
+                    .andExpect(jsonPath("$.traceId").value(CORRELATION))
+                    .andExpect(jsonPath("$.retryable").value(false))
+                    .andExpect(jsonPath("$.timestamp").exists());
+        }
     }
 
     @Test
@@ -102,8 +135,21 @@ class PlatformExceptionHandlerTest {
     }
 
     @Test
-    void requestBodyValidationMapsTo400WithRedactedErrors() throws Exception {
-        mockMvc(defaults(), List.of())
+    void requestBodyValidationOmitsRejectedValuesByDefault() throws Exception {
+        MvcResult result = mockMvc(defaults(), List.of())
+                .perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
+                        .content("{\"name\":\"\",\"password\":\"hunter2!\"}"))
+                .andExpect(status().isBadRequest())
+                .andExpect(jsonPath("$.code").value("DC-CORE-0400"))
+                .andExpect(jsonPath("$.errors[?(@.field=='name')].message").exists())
+                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
+                .andReturn();
+        assertThat(result.getResponse().getContentAsString()).doesNotContain("hunter2!");
+    }
+
+    @Test
+    void requestBodyValidationMapsTo400WithRedactedErrorsWhenRejectedValuesAreOn() throws Exception {
+        mockMvc(withRejectedValues(), List.of())
                 .perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                         .content("{\"name\":\"\",\"password\":\"hunter2!\"}"))
                 .andExpect(status().isBadRequest())
@@ -115,7 +161,7 @@ class PlatformExceptionHandlerTest {
 
     @Test
     void mapValidationOffFallsBackToDefaultSpringBody() throws Exception {
-        ErrorsProperties off = new ErrorsProperties(true, false, "https://errors.dc.com/", false);
+        ErrorsProperties off = new ErrorsProperties(true, false, "https://errors.dc.com/", false, false);
         mockMvc(off, List.of())
                 .perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                         .content("{\"name\":\"\",\"password\":\"hunter2!\"}"))
@@ -125,7 +171,7 @@ class PlatformExceptionHandlerTest {
 
     @Test
     void includeStacktraceAddsTheExtension() throws Exception {
-        ErrorsProperties withTrace = new ErrorsProperties(true, true, "https://errors.dc.com/", true);
+        ErrorsProperties withTrace = new ErrorsProperties(true, true, "https://errors.dc.com/", true, false);
         mockMvc(withTrace, List.of()).perform(get("/boom"))
                 .andExpect(jsonPath("$.stacktrace").exists());
     }
@@ -140,8 +186,9 @@ class PlatformExceptionHandlerTest {
     }
 
     @Test
-    void correlationExtensionIsOmittedOutsideAnOpenContext() throws Exception {
+    void traceIdExtensionIsOmittedOutsideAnOpenContextAndCorrelationIdIsGone() throws Exception {
         mockMvc(defaults(), List.of()).perform(get("/missing"))
+                .andExpect(jsonPath("$.traceId").doesNotExist())
                 .andExpect(jsonPath("$.correlationId").doesNotExist());
     }
 
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactoryTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactoryTest.java
new file mode 100644
index 0000000..fdfcfa4
--- /dev/null
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactoryTest.java
@@ -0,0 +1,70 @@
+package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import ae.gov.dubaicustoms.platform.core.ErrorCode;
+import ae.gov.dubaicustoms.platform.errors.autoconfigure.ErrorsProperties;
+import java.time.Clock;
+import java.util.LinkedHashMap;
+import java.util.List;
+import java.util.Map;
+import org.junit.jupiter.params.ParameterizedTest;
+import org.junit.jupiter.params.provider.CsvSource;
+import org.junit.jupiter.api.Test;
+import org.springframework.http.ProblemDetail;
+
+class ProblemDetailFactoryTest {
+
+    private static final ErrorCode CODE = new ErrorCode("DC-TEST-0001");
+
+    private static ProblemDetailFactory factory(boolean includeRejectedValues) {
+        return new ProblemDetailFactory(
+                new ErrorsProperties(true, false, "https://errors.dc.com/", true, includeRejectedValues),
+                List.of(), Clock.systemUTC());
+    }
+
+    @ParameterizedTest
+    @CsvSource({"400,false", "404,false", "409,false", "422,false", "429,true", "500,false", "502,true",
+            "503,true", "504,true"})
+    void retryableFollowsTheStatus(int status, boolean retryable) {
+        ProblemDetail problem = factory(false).create(status, CODE, "client-authored detail", "/x", null);
+        assertThat(problem.getProperties()).containsEntry("retryable", retryable);
+    }
+
+    @ParameterizedTest
+    @CsvSource({"500", "502", "503", "504"})
+    void everyServerErrorGetsTheGenericDetail(int status) {
+        ProblemDetail problem = factory(false).create(status, CODE, "db-7.internal refused", "/x", null);
+        assertThat(problem.getDetail()).isEqualTo(ProblemDetailFactory.GENERIC_SERVER_ERROR_DETAIL);
+    }
+
+    @Test
+    void clientErrorsKeepTheirAuthoredDetail() {
+        assertThat(factory(false).create(404, CODE, "order 42 not found", "/x", null).getDetail())
+                .isEqualTo("order 42 not found");
+    }
+
+    @Test
+    void decorateReplacesAFrameworkServerErrorDetail() {
+        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
+                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Async request timed out at node-3");
+        factory(false).decorate(problem, CODE, null);
+        assertThat(problem.getDetail()).isEqualTo(ProblemDetailFactory.GENERIC_SERVER_ERROR_DETAIL);
+        assertThat(problem.getProperties()).containsEntry("code", "DC-TEST-0001").containsEntry("retryable", true);
+    }
+
+    @Test
+    void rejectedValuesOnlyWhenEnabledAndCredentialsAlwaysRedacted() {
+        Map<String, Object> off = new LinkedHashMap<>();
+        factory(false).putRejectedValue(off, "email", "someone@example.ae");
+        assertThat(off).isEmpty();
+
+        Map<String, Object> on = new LinkedHashMap<>();
+        factory(true).putRejectedValue(on, "email", "someone@example.ae");
+        assertThat(on).containsEntry("rejectedValue", "someone@example.ae");
+
+        Map<String, Object> secret = new LinkedHashMap<>();
+        factory(true).putRejectedValue(secret, "apiToken", "s3cr3t");
+        assertThat(secret).containsEntry("rejectedValue", "REDACTED");
+    }
+}
diff --git a/examples/example-minimal/src/main/java/ae/gov/dubaicustoms/example/minimal/Application.java b/examples/example-minimal/src/main/java/ae/gov/dubaicustoms/example/minimal/Application.java
index 4bfaa83..5af8eeb 100644
--- a/examples/example-minimal/src/main/java/ae/gov/dubaicustoms/example/minimal/Application.java
+++ b/examples/example-minimal/src/main/java/ae/gov/dubaicustoms/example/minimal/Application.java
@@ -6,7 +6,7 @@ import org.springframework.boot.autoconfigure.SpringBootApplication;
 /**
  * The smallest possible platform service: it depends on {@code platform-starter-core},
  * {@code -errors}, and {@code -logging} and nothing else. Booting it proves the platform floor —
- * every request gets a correlation ID, logs are structured JSON, and any thrown
+ * every request is correlated by its W3C trace ID, logs are structured JSON, and any thrown
  * {@link ae.gov.dubaicustoms.platform.errors.BusinessException} renders as an RFC-9457
  * {@code application/problem+json} response — without the service writing a single line of
  * cross-cutting code.
diff --git a/examples/example-minimal/src/main/java/ae/gov/dubaicustoms/example/minimal/PingController.java b/examples/example-minimal/src/main/java/ae/gov/dubaicustoms/example/minimal/PingController.java
index 64ec50a..c37968f 100644
--- a/examples/example-minimal/src/main/java/ae/gov/dubaicustoms/example/minimal/PingController.java
+++ b/examples/example-minimal/src/main/java/ae/gov/dubaicustoms/example/minimal/PingController.java
@@ -9,8 +9,8 @@ import org.springframework.web.bind.annotation.RestController;
 /**
  * Two endpoints that together demonstrate the floor:
  * <ul>
- *   <li>{@code GET /ping} — a healthy response; the correlation ID is on the log line and the
- *       {@code X-Correlation-Id} response header without any code here doing it;</li>
+ *   <li>{@code GET /ping} — a healthy response; the request's W3C trace ID is on the log line
+ *       ({@code traceId}) without any code here doing it;</li>
  *   <li>{@code GET /widgets/{id}} — always absent, so it throws {@link NotFoundException}. The
  *       platform (not this class) maps it to a 404 {@code application/problem+json} body carrying the
  *       {@link ErrorCode}. There is deliberately no {@code @RestControllerAdvice} in this service.</li>
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingProperties.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingProperties.java
index 1eade40..bf38214 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingProperties.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/LoggingProperties.java
@@ -15,7 +15,7 @@ import org.springframework.validation.annotation.Validated;
  * @param enabled master kill switch for the whole capability
  * @param format log output format; {@code json} in every deployed environment, {@code console}
  *        for humans (auto-selected when the {@code local} profile is active)
- * @param includeMdc copy MDC entries (correlationId, …) into JSON log fields
+ * @param includeMdc copy MDC entries (traceId, spanId, …) into JSON log fields
  * @param serviceName service name stamped on every JSON log line; defaults to
  *        {@code spring.application.name}
  * @since 0.1.0
@@ -27,7 +27,7 @@ public record LoggingProperties(
         @DefaultValue("true") boolean enabled,
         /** Output format: json (deployed) or console (humans; auto-picked on the local profile). */
         @DefaultValue("json") Format format,
-        /** Copy MDC entries (correlationId, ...) into JSON log fields. */
+        /** Copy MDC entries (traceId, spanId, ...) into JSON log fields. */
         @DefaultValue("true") boolean includeMdc,
         /** Service name stamped on every JSON line; defaults to spring.application.name. */
         @DefaultValue("") String serviceName) {
diff --git a/logging/platform-logging-autoconfigure/src/main/resources/ae/gov/dubaicustoms/platform/logging/logback-platform.xml b/logging/platform-logging-autoconfigure/src/main/resources/ae/gov/dubaicustoms/platform/logging/logback-platform.xml
index 6e20de1..cd521d5 100644
--- a/logging/platform-logging-autoconfigure/src/main/resources/ae/gov/dubaicustoms/platform/logging/logback-platform.xml
+++ b/logging/platform-logging-autoconfigure/src/main/resources/ae/gov/dubaicustoms/platform/logging/logback-platform.xml
@@ -1,6 +1,6 @@
 <?xml version="1.0" encoding="UTF-8"?>
 <!-- Platform JSON logging: one logstash-encoded line per event on stdout, ECS-ish field names
-     (@timestamp, level, logger, message, service, correlationId via MDC, stack_trace).
+     (@timestamp, level, logger, message, service, traceId/spanId via MDC, stack_trace).
      Activated by PlatformLoggingEnvironmentPostProcessor defaulting logging.config to this file;
      Boot processes it, so <springProperty> works. Setting logging.config yourself replaces it. -->
 <configuration>
@@ -10,7 +10,7 @@
 
   <appender name="PLATFORM_JSON" class="ch.qos.logback.core.ConsoleAppender">
     <encoder class="net.logstash.logback.encoder.LogstashEncoder">
-      <!-- correlationId (and everything else the core filter publishes) rides in via MDC. -->
+      <!-- traceId/spanId (and everything else the core filter publishes) ride in via MDC. -->
       <includeMdc>${includeMdc}</includeMdc>
       <customFields>{"service":"${service}"}</customFields>
       <fieldNames>
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java
index ecbdfde..233b584 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java
@@ -65,7 +65,8 @@ class JsonLogOutputTest {
                 .contains("\"logger\":\"json.probe\"")
                 .contains("\"message\":\"probe message\"")
                 .contains("\"service\":\"orders\"")
-                .contains("\"correlationId\":\"0123456789abcdef0123456789abcdef\"");
+                .contains("\"traceId\":\"0123456789abcdef0123456789abcdef\"")
+                .doesNotContain("\"correlationId\"");
         // Renamed/dropped by fieldNames config; their presence would mean the resource drifted.
         assertThat(jsonLine).doesNotContain("\"logger_name\"").doesNotContain("\"level_value\"");
     }
diff --git a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java
index af90e3f..9951a77 100644
--- a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java
+++ b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java
@@ -10,16 +10,16 @@ import org.springframework.core.env.MapPropertySource;
 
 /**
  * Contributes the platform's management-plane defaults — actuator exposure, liveness/readiness
- * health groups, correlation baggage, and the local OTLP-off posture — as a LOWEST-precedence
+ * health groups, and the local OTLP-off posture — as a LOWEST-precedence
  * property source named {@code platform-observability-defaults}, so user configuration always
  * wins and the source name is visible in {@code /actuator/env} for debuggability
  * (property-conventions §7).
  *
- * <p>Cardinality rationale (mandatory, phase-05 spec): the correlation id is HIGH-cardinality —
- * one value per request. It must never become a metric tag or observation key-value (it would
- * explode time-series storage), so the platform propagates it exclusively through tracing
- * baggage ({@code management.tracing.baggage.remote-fields}); log/MDC correlation is already
- * handled by the core filter.
+ * <p>Cardinality rationale (mandatory, phase-05 spec): the correlation id — the W3C trace ID — is
+ * HIGH-cardinality, one value per request. It must never become a metric tag or observation
+ * key-value (it would explode time-series storage); it travels only in the standard
+ * {@code traceparent} header, and log/MDC correlation is handled by the core filter. No custom
+ * correlation header is mirrored into tracing baggage any more.
  *
  * <p>Stateless and thread-safe.
  *
@@ -51,9 +51,6 @@ public class PlatformObservabilityEnvironmentPostProcessor implements Environmen
             // membership validation on, a service without a DB would fail startup.
             defaults.put("management.endpoint.health.validate-group-membership", "false");
         }
-        // Correlation propagates via baggage ONLY — see the class javadoc cardinality rationale.
-        // Header name mirrors the core filter's dc.platform.core.correlation.header-name default.
-        defaults.put("management.tracing.baggage.remote-fields", "X-Correlation-Id");
         if (!environment.getProperty("dc.platform.observability.otlp.enabled", Boolean.class, false)) {
             // Local default OFF: no collector on laptops; flipping dc.platform.observability
             // .otlp.enabled=true removes these defaults so Boot's export config takes over.
diff --git a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/package-info.java b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/package-info.java
index d93fadb..da24078 100644
--- a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/package-info.java
+++ b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/package-info.java
@@ -1,8 +1,8 @@
 /**
  * Auto-configuration for the observability capability: common meter tags
  * ({@code service}/{@code env}/{@code platform.version}), liveness/readiness health-group and
- * actuator-exposure defaults contributed before the context exists, correlation propagation via
- * tracing baggage (never metric tags — cardinality), and the {@code platform} actuator endpoint
+ * actuator-exposure defaults contributed before the context exists (the trace ID is never a metric
+ * tag — cardinality), and the {@code platform} actuator endpoint
  * reporting active capabilities.
  *
  * <p>Kill switch: {@code dc.platform.observability.enabled}. OTLP export is OFF by default
diff --git a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessorTest.java b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessorTest.java
index dfd3db6..d5906e9 100644
--- a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessorTest.java
+++ b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessorTest.java
@@ -27,8 +27,8 @@ class PlatformObservabilityEnvironmentPostProcessorTest {
                 .isEqualTo("readinessState,db,rabbit,redis");
         assertThat(environment.getProperty("management.endpoint.health.validate-group-membership"))
                 .isEqualTo("false");
-        assertThat(environment.getProperty("management.tracing.baggage.remote-fields"))
-                .isEqualTo("X-Correlation-Id");
+        // HD5: no custom correlation header is mirrored into baggage; traceparent is the only carrier.
+        assertThat(environment.getProperty("management.tracing.baggage.remote-fields")).isNull();
         assertThat(environment.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("false");
         assertThat(environment.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("false");
     }
diff --git a/openapi/platform-openapi-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/openapi/autoconfigure/internal/ProblemDetailOpenApiCustomizer.java b/openapi/platform-openapi-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/openapi/autoconfigure/internal/ProblemDetailOpenApiCustomizer.java
index 4872554..bf7039f 100644
--- a/openapi/platform-openapi-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/openapi/autoconfigure/internal/ProblemDetailOpenApiCustomizer.java
+++ b/openapi/platform-openapi-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/openapi/autoconfigure/internal/ProblemDetailOpenApiCustomizer.java
@@ -57,7 +57,7 @@ public class ProblemDetailOpenApiCustomizer implements OpenApiCustomizer {
 
     private static Schema<?> problemDetailSchema() {
         // Mirrors org.springframework.http.ProblemDetail plus the platform's extensions
-        // (errors-autoconfigure: code, correlationId, timestamp; errors[] on validation failures).
+        // (errors-autoconfigure: code, traceId, timestamp, retryable; errors[] on validation failures).
         Map<String, Schema> properties = Map.of(
                 "type", new StringSchema(),
                 "title", new StringSchema(),
@@ -65,8 +65,9 @@ public class ProblemDetailOpenApiCustomizer implements OpenApiCustomizer {
                 "detail", new StringSchema(),
                 "instance", new StringSchema(),
                 "code", new StringSchema(),
-                "correlationId", new StringSchema(),
-                "timestamp", new StringSchema().format("date-time"));
+                "traceId", new StringSchema(),
+                "timestamp", new StringSchema().format("date-time"),
+                "retryable", new Schema<Boolean>().type("boolean"));
         return new Schema<>().type("object").properties(properties);
     }
 
diff --git a/restclient/platform-restclient-api/src/main/java/ae/gov/dubaicustoms/platform/restclient/RemoteCallException.java b/restclient/platform-restclient-api/src/main/java/ae/gov/dubaicustoms/platform/restclient/RemoteCallException.java
index fbbe79a..7814d63 100644
--- a/restclient/platform-restclient-api/src/main/java/ae/gov/dubaicustoms/platform/restclient/RemoteCallException.java
+++ b/restclient/platform-restclient-api/src/main/java/ae/gov/dubaicustoms/platform/restclient/RemoteCallException.java
@@ -80,7 +80,12 @@ public final class RemoteCallException extends PlatformException {
      * Returns the correlation id echoed by the callee, if any.
      *
      * @return the remote correlation id; empty if the callee did not echo one
+     * @deprecated the platform no longer reads or echoes custom correlation headers: caller and
+     *             callee share one W3C trace (propagated in {@code traceparent}), so the caller's own
+     *             trace ID already joins both sides' logs. The platform client always passes
+     *             {@code null}, so this is empty for platform-created exceptions.
      */
+    @Deprecated(since = "1.0.0")
     public Optional<String> remoteCorrelationId() {
         return Optional.ofNullable(remoteCorrelationId);
     }
diff --git a/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfiguration.java b/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfiguration.java
index d6ab2db..4fa3253 100644
--- a/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfiguration.java
+++ b/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfiguration.java
@@ -23,7 +23,7 @@ import org.springframework.web.client.RestClient;
  * Activates when: RestClient (spring-web) on the classpath AND dc.platform.restclient.enabled != false
  * Backs off when: user defines a PlatformRestClientFactory bean
  * Beans: platformRestClientFactory — DefaultPlatformRestClientFactory: JDK HttpClient request
- *                 factory with per-client connect/read timeouts, correlation-header propagation,
+ *                 factory with per-client connect/read timeouts, traceparent propagation (observation),
  *                 non-2xx responses mapped to RemoteCallException, http.client.requests
  *                 observations (client.name = platform client name) on the ObservationRegistry bean
  *                 when one exists (resolved lazily per builder; NOOP otherwise), ordered
diff --git a/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/RestClientProperties.java b/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/RestClientProperties.java
index 9d4b2a1..ba46ebb 100644
--- a/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/RestClientProperties.java
+++ b/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/RestClientProperties.java
@@ -13,11 +13,13 @@ import org.springframework.validation.annotation.Validated;
  * via {@code @EnableConfigurationProperties} (never scanned).
  *
  * @param enabled master kill switch for the whole capability
- * @param propagateCorrelation propagate the current {@code RequestContext} correlation id as an
- *        outbound header
  * @param defaults connect/read timeouts applied when a client has no override
  * @param clients per-client timeout overrides, keyed by the name passed to
  *        {@link ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory#builder(String)}
+ * <p>{@code dc.platform.restclient.propagate-correlation} is gone: every client is instrumented with
+ * the observation registry, so the W3C {@code traceparent} (the platform's only correlation id) is
+ * always propagated and no custom header is sent. The key remains in the metadata as deprecated.
+ *
  * @since 0.2.0
  */
 @Validated
@@ -25,8 +27,6 @@ import org.springframework.validation.annotation.Validated;
 public record RestClientProperties(
         /** Master switch for the capability. */
         @DefaultValue("true") boolean enabled,
-        /** Propagate the current RequestContext correlation id as an outbound header. */
-        @DefaultValue("true") boolean propagateCorrelation,
         /** Connect/read timeouts applied when a client has no override. */
         Defaults defaults,
         /** Per-client timeout overrides, keyed by client name. */
diff --git a/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java b/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java
index 5e2c922..36d4732 100644
--- a/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java
+++ b/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java
@@ -1,6 +1,5 @@
 package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;
 
-import ae.gov.dubaicustoms.platform.core.context.RequestContext;
 import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientCustomizer;
 import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
 import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
@@ -26,9 +25,6 @@ import org.springframework.web.client.RestClient;
  */
 public final class DefaultPlatformRestClientFactory implements PlatformRestClientFactory {
 
-    /** Matches the default header name in {@code platform-core-autoconfigure}'s CoreProperties. */
-    private static final String CORRELATION_HEADER = "X-Correlation-Id";
-
     private final RestClientProperties properties;
     private final List<PlatformRestClientCustomizer> customizers;
     private final Supplier<ObservationRegistry> observationRegistry;
@@ -61,16 +57,11 @@ public final class DefaultPlatformRestClientFactory implements PlatformRestClien
                 .requestFactory(requestFactory)
                 .observationRegistry(observationRegistry.get())
                 .observationConvention(new ClientNameConvention(clientName));
-        if (properties.propagateCorrelation()) {
-            builder.requestInterceptor((request, body, execution) -> {
-                RequestContext.correlationId().ifPresent(id -> request.getHeaders().set(CORRELATION_HEADER, id.value()));
-                return execution.execute(request, body);
-            });
-        }
+        // No correlation interceptor: the observation registry above propagates traceparent, the
+        // platform's only correlation id. No custom correlation header is sent or read.
         builder.defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (request, response) -> {
             String body = readBody(response.getBody());
-            String remoteCorrelationId = response.getHeaders().getFirst(CORRELATION_HEADER);
-            throw new RemoteCallException(clientName, response.getStatusCode().value(), body, remoteCorrelationId, null);
+            throw new RemoteCallException(clientName, response.getStatusCode().value(), body, null, null);
         });
         for (PlatformRestClientCustomizer customizer : customizers) {
             customizer.customize(clientName, builder);
diff --git a/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/package-info.java b/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/package-info.java
index 0affc35..78dd22d 100644
--- a/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/package-info.java
+++ b/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/package-info.java
@@ -1,6 +1,6 @@
 /**
  * Auto-configuration for the REST client capability: {@code PlatformRestClientFactory} on the
- * JDK HttpClient request factory, with correlation propagation, per-client timeouts, error
+ * JDK HttpClient request factory, with W3C traceparent propagation, per-client timeouts, error
  * mapping, and an optional guarded token-relay customizer.
  *
  * @since 0.2.0
diff --git a/restclient/platform-restclient-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json b/restclient/platform-restclient-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json
new file mode 100644
index 0000000..08288f3
--- /dev/null
+++ b/restclient/platform-restclient-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json
@@ -0,0 +1,13 @@
+{
+  "properties": [
+    {
+      "name": "dc.platform.restclient.propagate-correlation",
+      "type": "java.lang.Boolean",
+      "description": "Removed: every platform client propagates the W3C traceparent (the platform's only correlation id) through its observation registry; no custom correlation header is sent.",
+      "deprecation": {
+        "level": "error",
+        "reason": "Correlation is the W3C trace ID, propagated in traceparent by the instrumented client."
+      }
+    }
+  ]
+}
diff --git a/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfigurationTest.java b/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfigurationTest.java
index 77b7997..fe42b55 100644
--- a/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfigurationTest.java
+++ b/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfigurationTest.java
@@ -57,12 +57,10 @@ class PlatformRestClientAutoConfigurationTest {
     @Test
     void propertiesBindFromKebabKeys() {
         runner.withPropertyValues(
-                        "dc.platform.restclient.propagate-correlation=false",
                         "dc.platform.restclient.defaults.connect-timeout=5s",
                         "dc.platform.restclient.clients.orders.read-timeout=1s")
                 .run(context -> {
                     RestClientProperties properties = context.getBean(RestClientProperties.class);
-                    assertThat(properties.propagateCorrelation()).isFalse();
                     assertThat(properties.defaults().connectTimeout()).isEqualTo(java.time.Duration.ofSeconds(5));
                     assertThat(properties.timeoutsFor("orders").readTimeout()).isEqualTo(java.time.Duration.ofSeconds(1));
                 });
diff --git a/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactoryTest.java b/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactoryTest.java
index ecdef64..74882d8 100644
--- a/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactoryTest.java
+++ b/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactoryTest.java
@@ -35,7 +35,7 @@ class DefaultPlatformRestClientFactoryTest {
     private final MockWebServer server = new MockWebServer();
 
     private final RestClientProperties defaultProperties =
-            new RestClientProperties(true, true, new RestClientProperties.Defaults(null, null), Map.of());
+            new RestClientProperties(true, new RestClientProperties.Defaults(null, null), Map.of());
 
     @BeforeEach
     void startServer() throws IOException {
@@ -48,26 +48,10 @@ class DefaultPlatformRestClientFactoryTest {
     }
 
     @Test
-    void propagatesCorrelationHeaderWhenContextOpen() throws Exception {
+    void neverSendsACustomCorrelationHeader() throws Exception {
         server.enqueue(new MockResponse());
         RestClient client = new DefaultPlatformRestClientFactory(defaultProperties, List.of())
                 .builder("orders").baseUrl(server.url("/").toString()).build();
-        CorrelationId id = CorrelationId.random();
-
-        try (AutoCloseable scope = RequestContext.open(id, Map.of())) {
-            client.get().uri("/widgets").retrieve().toBodilessEntity();
-        }
-
-        assertThat(server.takeRequest().getHeader("X-Correlation-Id")).isEqualTo(id.value());
-    }
-
-    @Test
-    void doesNotSetCorrelationHeaderWhenPropagationDisabled() throws Exception {
-        server.enqueue(new MockResponse());
-        RestClientProperties disabled =
-                new RestClientProperties(true, false, new RestClientProperties.Defaults(null, null), Map.of());
-        RestClient client = new DefaultPlatformRestClientFactory(disabled, List.of())
-                .builder("orders").baseUrl(server.url("/").toString()).build();
 
         try (AutoCloseable scope = RequestContext.open(CorrelationId.random(), Map.of())) {
             client.get().uri("/widgets").retrieve().toBodilessEntity();
@@ -80,6 +64,7 @@ class DefaultPlatformRestClientFactoryTest {
     void mapsNon2xxResponsesToRemoteCallException() {
         server.enqueue(new MockResponse().setResponseCode(503).setBody("service unavailable")
                 .addHeader("X-Correlation-Id", "remote-abc"));
+        // A callee's custom correlation header is not read either.
         RestClient client = new DefaultPlatformRestClientFactory(defaultProperties, List.of())
                 .builder("orders").baseUrl(server.url("/").toString()).build();
 
@@ -89,7 +74,7 @@ class DefaultPlatformRestClientFactoryTest {
                     RemoteCallException remoteCallException = (RemoteCallException) e;
                     assertThat(remoteCallException.status()).isEqualTo(503);
                     assertThat(remoteCallException.bodySnippet()).isEqualTo("service unavailable");
-                    assertThat(remoteCallException.remoteCorrelationId()).contains("remote-abc");
+                    assertThat(remoteCallException.remoteCorrelationId()).isEmpty();
                 });
     }
 
@@ -109,7 +94,7 @@ class DefaultPlatformRestClientFactoryTest {
     @Test
     void readTimeoutAbortsASlowResponseWithinTheConfiguredBound() {
         server.enqueue(new MockResponse().setBody("late").setHeadersDelay(3, TimeUnit.SECONDS));
-        RestClientProperties shortRead = new RestClientProperties(true, true,
+        RestClientProperties shortRead = new RestClientProperties(true,
                 new RestClientProperties.Defaults(null, Duration.ofMillis(300)), Map.of());
         RestClient client = new DefaultPlatformRestClientFactory(shortRead, List.of())
                 .builder("orders").baseUrl(server.url("/").toString()).build();
@@ -122,7 +107,7 @@ class DefaultPlatformRestClientFactoryTest {
     @Test
     void perClientReadTimeoutOverridesTheDefaultAtCallTime() {
         server.enqueue(new MockResponse().setBody("late").setHeadersDelay(3, TimeUnit.SECONDS));
-        RestClientProperties overridden = new RestClientProperties(true, true, new RestClientProperties.Defaults(null, null),
+        RestClientProperties overridden = new RestClientProperties(true, new RestClientProperties.Defaults(null, null),
                 Map.of("orders", new RestClientProperties.ClientOverride(null, Duration.ofMillis(300))));
         RestClient client = new DefaultPlatformRestClientFactory(overridden, List.of())
                 .builder("orders").baseUrl(server.url("/").toString()).build();
@@ -202,7 +187,7 @@ class DefaultPlatformRestClientFactoryTest {
 
     @Test
     void appliesPerClientTimeoutOverride() {
-        RestClientProperties withOverride = new RestClientProperties(true, true, new RestClientProperties.Defaults(null, null),
+        RestClientProperties withOverride = new RestClientProperties(true, new RestClientProperties.Defaults(null, null),
                 Map.of("orders", new RestClientProperties.ClientOverride(Duration.ofMillis(1), null)));
 
         RestClientProperties.Defaults resolved = withOverride.timeoutsFor("orders");
diff --git a/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemDetailAccessDeniedHandler.java b/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemDetailAccessDeniedHandler.java
index c48b996..96126e2 100644
--- a/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemDetailAccessDeniedHandler.java
+++ b/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemDetailAccessDeniedHandler.java
@@ -12,6 +12,7 @@ public final class ProblemDetailAccessDeniedHandler implements AccessDeniedHandl
     @Override
     public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
             throws IOException {
-        ProblemResponses.write(response, 403, "Forbidden", "The current principal lacks the required authority");
+        ProblemResponses.write(response, 403, ProblemResponses.ACCESS_DENIED, "Forbidden",
+                "The current principal lacks the required authority");
     }
 }
diff --git a/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemDetailAuthenticationEntryPoint.java b/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemDetailAuthenticationEntryPoint.java
index 3c888fe..ee858cb 100644
--- a/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemDetailAuthenticationEntryPoint.java
+++ b/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemDetailAuthenticationEntryPoint.java
@@ -12,7 +12,7 @@ public final class ProblemDetailAuthenticationEntryPoint implements Authenticati
     @Override
     public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
             throws IOException {
-        ProblemResponses.write(response, 401, "Unauthorized",
+        ProblemResponses.write(response, 401, ProblemResponses.UNAUTHENTICATED, "Unauthorized",
                 "Full authentication is required to access this resource");
     }
 }
diff --git a/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemResponses.java b/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemResponses.java
index f01754f..8e3cf6d 100644
--- a/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemResponses.java
+++ b/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemResponses.java
@@ -1,23 +1,44 @@
 package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;
 
+import ae.gov.dubaicustoms.platform.core.ErrorCode;
+import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
+import ae.gov.dubaicustoms.platform.core.context.RequestContext;
 import jakarta.servlet.http.HttpServletResponse;
 import java.io.IOException;
+import java.time.Instant;
 
 /**
  * Shared RFC-9457 problem-body writer for the security filter chain's entry point and denied
  * handler, which run outside the DispatcherServlet and so cannot reach the errors capability's
- * {@code @RestControllerAdvice}.
+ * {@code @RestControllerAdvice}. Emits the same platform extensions as the errors capability
+ * ({@code code}, {@code traceId}, {@code timestamp}, {@code retryable}); the reason a request was
+ * rejected is never included.
  */
 final class ProblemResponses {
 
+    /** Missing, expired or otherwise unacceptable credentials. */
+    static final ErrorCode UNAUTHENTICATED = new ErrorCode("DC-SEC-0401");
+
+    /** Authenticated principal without the required authority. */
+    static final ErrorCode ACCESS_DENIED = new ErrorCode("DC-SEC-0403");
+
     private ProblemResponses() {
     }
 
-    static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
+    static void write(HttpServletResponse response, int status, ErrorCode code, String title, String detail)
+            throws IOException {
         response.setStatus(status);
         response.setContentType("application/problem+json");
-        response.getWriter().write("""
-                {"status":%d,"title":"%s","detail":"%s"}""".formatted(status, escape(title), escape(detail)));
+        StringBuilder body = new StringBuilder()
+                .append("{\"status\":").append(status)
+                .append(",\"title\":\"").append(escape(title))
+                .append("\",\"detail\":\"").append(escape(detail))
+                .append("\",\"code\":\"").append(code.value()).append('"');
+        RequestContext.correlationId().map(CorrelationId::value)
+                .ifPresent(traceId -> body.append(",\"traceId\":\"").append(traceId).append('"'));
+        body.append(",\"timestamp\":\"").append(Instant.now()).append('"')
+                .append(",\"retryable\":false}");
+        response.getWriter().write(body.toString());
     }
 
     private static String escape(String value) {
diff --git a/security/platform-security-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/security/autoconfigure/PlatformSecurityWebTest.java b/security/platform-security-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/security/autoconfigure/PlatformSecurityWebTest.java
index d7affb6..949976f 100644
--- a/security/platform-security-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/security/autoconfigure/PlatformSecurityWebTest.java
+++ b/security/platform-security-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/security/autoconfigure/PlatformSecurityWebTest.java
@@ -3,6 +3,7 @@ package ae.gov.dubaicustoms.platform.security.autoconfigure;
 import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
 import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
 import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
+import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
 import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
 
 import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
@@ -36,7 +37,24 @@ class PlatformSecurityWebTest {
     void anonymousRequestIsUnauthorizedWithProblemBody() throws Exception {
         mockMvc.perform(get("/secure"))
                 .andExpect(status().isUnauthorized())
-                .andExpect(content().contentType("application/problem+json"));
+                .andExpect(content().contentType("application/problem+json"))
+                .andExpect(jsonPath("$.code").value("DC-SEC-0401"))
+                .andExpect(jsonPath("$.retryable").value(false))
+                .andExpect(jsonPath("$.timestamp").exists())
+                .andExpect(jsonPath("$.traceId").doesNotExist());
+    }
+
+    @Test
+    void unauthorizedBodyCarriesTheTraceIdOfTheRequestContext() throws Exception {
+        // MockMvc runs the filter chain on the calling thread, so the open context is the request's.
+        String traceId = "0af7651916cd43dd8448eb211c80319c";
+        try (AutoCloseable scope = ae.gov.dubaicustoms.platform.core.context.RequestContext.open(
+                new ae.gov.dubaicustoms.platform.core.context.CorrelationId(traceId), java.util.Map.of())) {
+            mockMvc.perform(get("/secure"))
+                    .andExpect(status().isUnauthorized())
+                    .andExpect(jsonPath("$.code").value("DC-SEC-0401"))
+                    .andExpect(jsonPath("$.traceId").value(traceId));
+        }
     }
 
     @Test
diff --git a/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/arch/PlatformUsageRules.java b/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/arch/PlatformUsageRules.java
index 282b746..c61fe72 100644
--- a/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/arch/PlatformUsageRules.java
+++ b/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/arch/PlatformUsageRules.java
@@ -120,7 +120,7 @@ public final class PlatformUsageRules {
                     }
                 })
                 .because("throw PlatformException subtypes (BusinessException/NotFoundException/ConflictException) "
-                        + "with an ErrorCode — the platform maps them to RFC 9457 with correlation IDs; do not "
+                        + "with an ErrorCode — the platform maps them to RFC 9457 with trace IDs; do not "
                         + "write your own @RestControllerAdvice")
                 .allowEmptyShould(true);
     }
@@ -144,7 +144,7 @@ public final class PlatformUsageRules {
                     }
                 })
                 .because("subclassing ResponseEntityExceptionHandler is banned here → throw PlatformException "
-                        + "subtypes with an ErrorCode; the platform maps them to RFC 9457 with correlation IDs "
+                        + "subtypes with an ErrorCode; the platform maps them to RFC 9457 with trace IDs "
                         + "(docs/modules/errors.md)")
                 .allowEmptyShould(true);
     }
diff --git a/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/assertj/ProblemDetailAssert.java b/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/assertj/ProblemDetailAssert.java
index fb6f84d..ff7c24f 100644
--- a/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/assertj/ProblemDetailAssert.java
+++ b/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/assertj/ProblemDetailAssert.java
@@ -8,7 +8,7 @@ import org.springframework.http.ProblemDetail;
 
 /**
  * AssertJ assertion for RFC-9457 {@link ProblemDetail} bodies produced by the platform's error
- * handling, with first-class access to the platform extensions ({@code code}, {@code correlationId},
+ * handling, with first-class access to the platform extensions ({@code code}, {@code traceId},
  * {@code timestamp}).
  *
  * <pre>{@code
@@ -90,7 +90,7 @@ public final class ProblemDetailAssert extends AbstractAssert<ProblemDetailAsser
 
     /**
      * Asserts an extension property is present (non-null), for values that are non-deterministic
-     * (e.g. {@code correlationId}, {@code timestamp}).
+     * (e.g. {@code traceId}, {@code timestamp}).
      *
      * @param name the property name; never {@code null}
      * @return this assertion
diff --git a/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/assertj/package-info.java b/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/assertj/package-info.java
index 7b3e1bb..6e81e1e 100644
--- a/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/assertj/package-info.java
+++ b/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/assertj/package-info.java
@@ -2,7 +2,7 @@
  * AssertJ extensions for platform types: {@link
  * ae.gov.dubaicustoms.platform.test.assertj.PlatformAssertions#assertThatProblem} asserts over
  * RFC-9457 {@link org.springframework.http.ProblemDetail} bodies, including the platform's {@code code}
- * and {@code correlationId} extensions.
+ * and {@code traceId} extensions.
  *
  * @since 0.2.0
  */
diff --git a/test/platform-test-api/src/test/java/ae/gov/dubaicustoms/platform/test/assertj/ProblemDetailAssertTest.java b/test/platform-test-api/src/test/java/ae/gov/dubaicustoms/platform/test/assertj/ProblemDetailAssertTest.java
index f64303d..e75d13a 100644
--- a/test/platform-test-api/src/test/java/ae/gov/dubaicustoms/platform/test/assertj/ProblemDetailAssertTest.java
+++ b/test/platform-test-api/src/test/java/ae/gov/dubaicustoms/platform/test/assertj/ProblemDetailAssertTest.java
@@ -15,7 +15,7 @@ class ProblemDetailAssertTest {
         ProblemDetail problem = ProblemDetail.forStatus(404);
         problem.setDetail("order 'unknown' was not found");
         problem.setProperty("code", "DC-ORD-0001");
-        problem.setProperty("correlationId", "abc-123");
+        problem.setProperty("traceId", "abc-123");
         return problem;
     }
 
@@ -25,8 +25,8 @@ class ProblemDetailAssertTest {
                 .hasStatus(404)
                 .hasCode("DC-ORD-0001")
                 .hasDetailContaining("not found")
-                .hasProperty("correlationId", "abc-123")
-                .hasPropertyPresent("correlationId");
+                .hasProperty("traceId", "abc-123")
+                .hasPropertyPresent("traceId");
     }
 
     @Test
diff --git a/tooling/hardening/lib/harness.sh b/tooling/hardening/lib/harness.sh
index 6f22ea7..aaae6d2 100644
--- a/tooling/hardening/lib/harness.sh
+++ b/tooling/hardening/lib/harness.sh
@@ -5,8 +5,10 @@
 #   hh_init <phase-slug> <scenario-name>        # run dir, evidence dir, docker network, cleanup trap
 #   hh_infra <name> <image> [docker-run args…]  # backing service on the run network (Postgres, Rabbit…)
 #   hh_generate <artifactId> <features|->       # archetype app OUTSIDE the repo, from the local catalog
+#   hh_add_sources <dir> <package-subpath>      # consumer code (e.g. a probe controller) into the app
 #   hh_build_app                                # generated `mvn package`, then a container image
 #   hh_app_start [env KEY=VALUE…]               # app container on the run network, port published
+#                                               # (HH_APP_NAME selects the container name; default "app")
 #   hh_wait_ready [timeout-s]                   # /actuator/health/readiness == UP
 #   hh_http <name> <method> <path> [curl args…] # saves status + body to evidence, sets HH_STATUS/HH_BODY
 #   hh_expect_status <code> / hh_expect_json <python-expr>
@@ -99,6 +101,15 @@ hh_generate() {
   hh_log "generated $artifact (features=$features) in $HH_APP_DIR"
 }
 
+hh_add_sources() {
+  local src=$1 subpath=$2 target
+  target="$HH_APP_DIR/src/main/java/$subpath"
+  mkdir -p "$target"
+  cp "$src"/*.java "$target"/ || hh_abort "could not copy consumer sources from $src"
+  (cd "$src" && sha256sum ./*.java) > "$HH_EVIDENCE/consumer-sources.sha256"
+  hh_log "added consumer sources from $src to $subpath"
+}
+
 hh_build_app() {
   (cd "$HH_APP_DIR" && "${HH_MVN[@]}" package -DskipTests -l "$(hh_native "$HH_EVIDENCE/app-build.log")") \
       || hh_abort "generated app build failed (app-build.log)"
@@ -128,8 +139,8 @@ hh_app_start() {
   local env_args=()
   local kv
   for kv in "$@"; do env_args+=(-e "$kv"); done
-  HH_APP_CONTAINER="$HH_RUN_ID-app"
-  docker run -d --name "$HH_APP_CONTAINER" --network "$HH_NETWORK" --network-alias app \
+  HH_APP_CONTAINER="$HH_RUN_ID-${HH_APP_NAME:-app}"
+  docker run -d --name "$HH_APP_CONTAINER" --network "$HH_NETWORK" --network-alias "${HH_APP_NAME:-app}" \
       --label "dc-hardening=$HH_RUN_ID" -p 127.0.0.1::8080 ${env_args[@]+"${env_args[@]}"} "$HH_APP_IMAGE" >/dev/null \
       || hh_abort "app container did not start"
   HH_CONTAINERS+=("$HH_APP_CONTAINER")
@@ -195,7 +206,8 @@ hh_app_stop_graceful() {
   docker stop -t "$grace" "$HH_APP_CONTAINER" >/dev/null
   ended=$(date +%s)
   code=$(docker inspect -f '{{.State.ExitCode}}' "$HH_APP_CONTAINER")
-  docker logs "$HH_APP_CONTAINER" > "$HH_EVIDENCE/app.log" 2>&1 || true
+  docker logs "$HH_APP_CONTAINER" > "$HH_EVIDENCE/${HH_APP_NAME:-app}.log" 2>&1 || true
+  [ "${HH_APP_NAME:-app}" = app ] || cp "$HH_EVIDENCE/${HH_APP_NAME}.log" "$HH_EVIDENCE/app.log"
   echo "shutdown_seconds=$((ended - started)) exit_code=$code" >> "$HH_EVIDENCE/run-identity.txt"
   # 143 = 128+SIGTERM: the JVM honoured the signal; 137 would mean docker had to SIGKILL after the grace.
   case "$code" in
@@ -218,7 +230,7 @@ hh_finish() {
   fi
   local c
   for c in ${HH_CONTAINERS[@]+"${HH_CONTAINERS[@]}"}; do
-    case "$c" in *-app) ;; *) docker logs "$c" > "$HH_EVIDENCE/infra-${c#"$HH_RUN_ID"-}.log" 2>&1 ;; esac
+    case "$c" in *-app*) ;; *) docker logs "$c" > "$HH_EVIDENCE/infra-${c#"$HH_RUN_ID"-}.log" 2>&1 ;; esac
   done
   docker ps -aq --filter "label=dc-hardening=$HH_RUN_ID" | xargs -r docker rm -f >/dev/null 2>&1
   docker network rm "$HH_NETWORK" >/dev/null 2>&1
diff --git a/tooling/hardening/scenarios/phase-01-core-errors.sh b/tooling/hardening/scenarios/phase-01-core-errors.sh
new file mode 100644
index 0000000..308fafc
--- /dev/null
+++ b/tooling/hardening/scenarios/phase-01-core-errors.sh
@@ -0,0 +1,151 @@
+#!/usr/bin/env bash
+# Hardening phase 1 (core + errors), Gate F: an archetype-generated consumer, run in a container, proves
+# the correlation and error contracts of docs/production-readiness/phase-01-core-errors/scope.md
+# (AC1-AC13, AC18) over real HTTP. Fixtures, not certified here: logging, observability (tracing bridge),
+# security, openapi, validation, restclient (the archetype baseline + the `restclient` feature).
+#
+#   HH_MAVEN_REPO=~/.m2-hardening/phase-01 bash tooling/hardening/scenarios/phase-01-core-errors.sh
+source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"
+HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
+CHECKS=(python -I "$(hh_native "$HERE/phase-01/checks.py")")
+
+hex() { python -I -c "import secrets,sys; print(secrets.token_hex(int(sys.argv[1])))" "$1"; }
+tp() { printf '00-%s-%s-%s' "$1" "$(hex 8)" "${2:-01}"; }
+header_absent() { # name: the last response's headers must not contain it
+  if grep -qi "^$1:" "$HH_EVIDENCE/http/$HH_LAST_HTTP.headers"; then hh_fail "$HH_LAST_HTTP: response carries $1"
+  else hh_pass "$HH_LAST_HTTP: no $1 header"; fi
+}
+
+hh_init phase-01 core-errors
+
+hh_step "generate a consumer (restclient feature as the outbound fixture) and add the probe"
+hh_generate hardening-core restclient
+hh_add_sources "$HERE/phase-01/probe" ae/gov/dubaicustoms/hardening/probe
+hh_build_app
+
+hh_step "boot (sampling 1.0, core autoconfigure package at DEBUG — env relaxed binding lowercases class names — so the rejected-traceparent marker is visible)"
+hh_app_start SPRING_PROFILES_ACTIVE=default MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0 \
+    LOGGING_LEVEL_AE_GOV_DUBAICUSTOMS_PLATFORM_CORE_AUTOCONFIGURE=DEBUG
+hh_wait_ready 180
+HH_STARTUP_HEALTHY=$HH_READY_SECONDS
+
+T=$(hex 16)
+hh_step "AC1 valid traceparent is continued; RequestContext, MDC and @Async carry it"
+hh_http ac1-valid GET /probe/context -H "traceparent: $(tp "$T")" -D "$HH_EVIDENCE/http/ac1-valid.headers"
+hh_expect_status 200
+hh_expect_json "context == inbound trace" "b['context'] == '$T' and b['mdcTraceId'] == '$T'"
+hh_expect_json "spanId in MDC" "len(b['mdcSpanId']) == 16"
+hh_expect_json "@Async sees the trace" "b['async']['context'] == '$T' and b['async']['mdcTraceId'] == '$T'"
+hh_expect_json "@Async ran on another thread" "b['async']['thread'] != b['thread']"
+hh_expect_json "no correlationId MDC key" "'mdcCorrelationId' not in b"
+header_absent x-correlation-id
+
+hh_step "AC2 missing traceparent starts a new trace"
+hh_http ac2-missing GET /probe/context
+hh_expect_status 200
+hh_expect_json "new 32-hex trace" "len(b['context']) == 32 and b['context'] == b['mdcTraceId'] and b['context'] != '$T'"
+
+hh_step "AC3 malformed / oversized traceparent starts a new trace"
+BAD="00-zzzz-INJECTEDVALUE-01"
+hh_http ac3-malformed GET /probe/context -H "traceparent: $BAD"
+hh_expect_status 200
+hh_expect_json "new trace for malformed" "len(b['context']) == 32 and 'INJECTED' not in b['context']"
+OVERSIZE="00-$T-$(printf 'a%.0s' $(seq 1 300))-01"
+hh_http ac3-oversized GET /probe/context -H "traceparent: $OVERSIZE"
+hh_expect_status 200
+hh_expect_json "new trace for oversized" "len(b['context']) == 32 and b['context'] != '$T'"
+
+hh_step "AC4 a stray X-Correlation-Id is ignored"
+STRAY=$(hex 16)
+hh_http ac4-stray GET /probe/context -H "traceparent: $(tp "$T")" -H "X-Correlation-Id: $STRAY" \
+    -D "$HH_EVIDENCE/http/ac4-stray.headers"
+hh_expect_status 200
+hh_expect_json "trace unaffected by the stray header" "b['context'] == '$T'"
+header_absent x-correlation-id
+
+hh_step "AC5 the platform REST client propagates traceparent only"
+hh_http ac5-outbound GET /probe/outbound -H "traceparent: $(tp "$T")" -H "X-Correlation-Id: $STRAY"
+hh_expect_status 200
+hh_expect_json "downstream traceparent has the same trace" "b['downstream']['traceparent'].startswith('00-$T-')"
+hh_expect_json "no correlation header downstream" "'x-correlation-id' not in b['downstream']"
+
+hh_step "AC10 malformed JSON → 400 DC-CORE-0410 without parser internals"
+hh_http ac10-malformed POST /probe/applicants -H "traceparent: $(tp "$T")" -H "Content-Type: application/json" \
+    --data '{"name": [oops'
+hh_expect_status 400
+hh_expect_json "code + fixed detail + traceId" \
+    "b['code'] == 'DC-CORE-0410' and b['detail'] == 'The request body is malformed or unreadable.' and b['traceId'] == '$T'"
+hh_expect_json "no parser internals" "'jackson' not in json.dumps(b).lower() and 'Applicant' not in json.dumps(b)"
+
+hh_step "AC11 validation → 400 DC-CORE-0400 with field paths, no rejected values"
+PII="not-an-email-PII-4711"
+hh_http ac11-validation POST /probe/applicants -H "traceparent: $(tp "$T")" -H "Content-Type: application/json" \
+    --data "{\"name\":\"\",\"email\":\"$PII\"}"
+hh_expect_status 400
+hh_expect_json "code + traceId + retryable=false" "b['code'] == 'DC-CORE-0400' and b['traceId'] == '$T' and b['retryable'] is False"
+hh_expect_json "field paths present" "sorted(e['field'] for e in b['errors']) == ['email', 'name']"
+hh_expect_json "no rejectedValue, PII not echoed" "all('rejectedValue' not in e for e in b['errors']) and '$PII' not in json.dumps(b)"
+
+hh_step "AC12 unknown exception → 500 generic body; details only in the log"
+hh_http ac12-boom GET /probe/boom -H "traceparent: $(tp "$T")"
+hh_expect_status 500
+hh_expect_json "generic 500" "b['code'] == 'DC-CORE-0500' and b['detail'] == 'An unexpected error occurred.' and b['traceId'] == '$T'"
+hh_expect_json "no internals in the body" "'secret-host' not in json.dumps(b) and 'stacktrace' not in b"
+
+hh_step "AC13 401 / framework 404 carry code + traceId"
+hh_http ac13-unauthenticated GET /hello -H "traceparent: $(tp "$T")"
+hh_expect_status 401
+hh_expect_json "401 contract" "b['code'] == 'DC-SEC-0401' and b['traceId'] == '$T' and b['retryable'] is False"
+hh_http framework-404 GET /probe/does-not-exist -H "traceparent: $(tp "$T")"
+hh_expect_status 404
+hh_expect_json "404 contract" "b['code'] == 'DC-CORE-0499' and b['traceId'] == '$T'"
+
+hh_step "AC8 200 parallel requests x 3 rounds: zero context bleed"
+if "${CHECKS[@]}" concurrent "$HH_BASE" 200 3 > "$HH_EVIDENCE/ac8-concurrency.json"; then
+  hh_pass "AC8 $(cat "$HH_EVIDENCE/ac8-concurrency.json")"
+else
+  hh_fail "AC8 $(cat "$HH_EVIDENCE/ac8-concurrency.json")"
+fi
+
+hh_step "AC18 SIGTERM with an in-flight slow request: it completes, shutdown completes"
+curl -s -o "$HH_EVIDENCE/http/ac18-inflight.body" -w '%{http_code}' --max-time 60 "$HH_BASE/probe/slow?ms=6000" \
+    > "$HH_EVIDENCE/http/ac18-inflight.status" &
+INFLIGHT=$!
+sleep 1
+hh_app_stop_graceful
+wait "$INFLIGHT" || true
+if [ "$(cat "$HH_EVIDENCE/http/ac18-inflight.status")" = 200 ]; then
+  hh_pass "in-flight request completed with 200 during graceful shutdown"
+else
+  hh_fail "in-flight request status $(cat "$HH_EVIDENCE/http/ac18-inflight.status") during graceful shutdown"
+fi
+
+hh_step "logs: trace correlation, sanitized rejection marker, no raw hostile input"
+if "${CHECKS[@]}" logs "$(hh_native "$HH_EVIDENCE/app.log")" "$T" "INJECTEDVALUE" "$PII" > "$HH_EVIDENCE/logs-check.json"; then
+  hh_pass "logs $(cat "$HH_EVIDENCE/logs-check.json")"
+else
+  hh_fail "logs $(cat "$HH_EVIDENCE/logs-check.json")"
+fi
+if grep -q "rejected inbound traceparent" "$HH_EVIDENCE/app.log"; then hh_pass "sanitized rejection marker logged"
+else hh_fail "no sanitized rejection marker in the log"; fi
+if grep -q "db-secret-host" "$HH_EVIDENCE/app.log" && grep "db-secret-host" "$HH_EVIDENCE/app.log" | grep -q "$T"; then
+  hh_pass "AC12 full exception detail is in the log, on the request's trace"
+else
+  hh_fail "AC12 exception detail not found in the log with traceId $T"
+fi
+
+hh_step "AC6 never-sampled: trace ID still in context, MDC and error bodies"
+HH_APP_NAME=unsampled hh_app_start SPRING_PROFILES_ACTIVE=default MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.0
+hh_wait_ready 180
+U=$(hex 16)
+hh_http ac6-unsampled GET /probe/context -H "traceparent: $(tp "$U" 00)"
+hh_expect_status 200
+hh_expect_json "unsampled trace kept" "b['context'] == '$U' and b['mdcTraceId'] == '$U'"
+hh_http ac6-unsampled-new GET /probe/context
+hh_expect_json "fresh unsampled trace" "len(b['context']) == 32 and b['context'] == b['mdcTraceId']"
+hh_http ac6-unsampled-error GET /probe/boom -H "traceparent: $(tp "$U" 00)"
+hh_expect_status 500
+hh_expect_json "traceId in an unsampled error body" "b['traceId'] == '$U'"
+HH_APP_NAME=unsampled hh_app_stop_graceful
+
+echo "startup_healthy_seconds=$HH_STARTUP_HEALTHY" >> "$HH_EVIDENCE/run-identity.txt"
diff --git a/tooling/hardening/scenarios/phase-01/checks.py b/tooling/hardening/scenarios/phase-01/checks.py
new file mode 100644
index 0000000..314507a
--- /dev/null
+++ b/tooling/hardening/scenarios/phase-01/checks.py
@@ -0,0 +1,72 @@
+"""Checks for the phase-01 consumer scenario that are awkward in shell: concurrency and log scans.
+
+    python -I checks.py concurrent BASE_URL N ROUNDS     # N parallel /probe/context calls per round
+    python -I checks.py logs APP_LOG TRACE_ID [FORBIDDEN...]
+
+Exit code 0 = pass, 1 = fail; a one-line JSON summary goes to stdout either way.
+"""
+import concurrent.futures
+import json
+import secrets
+import sys
+import urllib.request
+
+
+def get_context(base, trace_id):
+    request = urllib.request.Request(base + '/probe/context',
+                                     headers={'traceparent': f'00-{trace_id}-{secrets.token_hex(8)}-01'})
+    with urllib.request.urlopen(request, timeout=30) as response:
+        headers = {k.lower(): v for k, v in response.getheaders()}
+        return json.load(response), headers
+
+
+def concurrent_check(base, n, rounds):
+    bleed = []
+    for round_no in range(rounds):
+        ids = [secrets.token_hex(16) for _ in range(n)]
+        with concurrent.futures.ThreadPoolExecutor(max_workers=n) as pool:
+            replies = list(pool.map(lambda trace_id: get_context(base, trace_id), ids))
+        for sent, (body, headers) in zip(ids, replies):
+            seen = {'context': body.get('context'), 'mdc': body.get('mdcTraceId'),
+                    'async': body.get('async', {}).get('context'), 'asyncMdc': body.get('async', {}).get('mdcTraceId')}
+            if any(value != sent for value in seen.values()) or 'x-correlation-id' in headers \
+                    or 'mdcCorrelationId' in body:
+                bleed.append({'round': round_no, 'sent': sent, 'seen': seen})
+    print(json.dumps({'check': 'concurrent', 'requests': n * rounds, 'rounds': rounds, 'bleed': len(bleed),
+                      'examples': bleed[:3]}))
+    return not bleed
+
+
+def logs_check(path, trace_id, forbidden):
+    events, non_json, problems = [], 0, []
+    with open(path, encoding='utf-8', errors='replace') as log:
+        for line in log:
+            line = line.strip()
+            if not line.startswith('{'):
+                non_json += 1
+                continue
+            try:
+                events.append(json.loads(line))
+            except ValueError:
+                problems.append('unparseable JSON line')
+    with open(path, encoding='utf-8', errors='replace') as log:
+        raw = log.read()
+    if not any(event.get('traceId') == trace_id for event in events):
+        problems.append(f'no event carries traceId {trace_id}')
+    if any('correlationId' in event for event in events):
+        problems.append('an event carries the removed correlationId field')
+    for value in forbidden:
+        if value in raw:
+            problems.append(f'forbidden value present in log: {value[:40]}')
+    print(json.dumps({'check': 'logs', 'events': len(events), 'non_json_lines': non_json, 'problems': problems}))
+    return not problems
+
+
+if __name__ == '__main__':
+    if sys.argv[1] == 'concurrent':
+        ok = concurrent_check(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
+    elif sys.argv[1] == 'logs':
+        ok = logs_check(sys.argv[2], sys.argv[3], sys.argv[4:])
+    else:
+        raise SystemExit('unknown check ' + sys.argv[1])
+    sys.exit(0 if ok else 1)
diff --git a/tooling/hardening/scenarios/phase-01/probe/HardeningProbe.java b/tooling/hardening/scenarios/phase-01/probe/HardeningProbe.java
new file mode 100644
index 0000000..5fc935e
--- /dev/null
+++ b/tooling/hardening/scenarios/phase-01/probe/HardeningProbe.java
@@ -0,0 +1,143 @@
+package ae.gov.dubaicustoms.hardening.probe;
+
+import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
+import ae.gov.dubaicustoms.platform.core.context.RequestContext;
+import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
+import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
+import jakarta.validation.Valid;
+import jakarta.validation.constraints.Email;
+import jakarta.validation.constraints.NotBlank;
+import java.util.HashMap;
+import java.util.Locale;
+import java.util.Map;
+import java.util.Optional;
+import java.util.concurrent.CompletableFuture;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.slf4j.MDC;
+import org.springframework.boot.web.server.context.WebServerApplicationContext;
+import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Configuration;
+import org.springframework.scheduling.annotation.Async;
+import org.springframework.scheduling.annotation.EnableAsync;
+import org.springframework.stereotype.Service;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.PostMapping;
+import org.springframework.web.bind.annotation.RequestBody;
+import org.springframework.web.bind.annotation.RequestHeader;
+import org.springframework.web.bind.annotation.RequestMapping;
+import org.springframework.web.bind.annotation.RequestParam;
+import org.springframework.web.bind.annotation.RestController;
+import org.springframework.web.client.RestClient;
+
+/**
+ * Consumer-side probe for the hardening phase-01 harness (never part of a real service). Uses only the
+ * platform's public API and documented extension points: {@link SecurityCustomizer} to open
+ * {@code /probe/**}, {@link PlatformRestClientFactory} for the outbound call, {@link RequestContext}
+ * to read the correlation id.
+ */
+public final class HardeningProbe {
+
+    private HardeningProbe() {
+    }
+
+    @Configuration(proxyBeanMethods = false)
+    @EnableAsync
+    static class ProbeConfiguration {
+
+        @Bean
+        SecurityCustomizer probePathsArePublic() {
+            return http -> http.authorizeHttpRequests(auth -> auth.requestMatchers("/probe/**").permitAll());
+        }
+    }
+
+    @Service
+    public static class AsyncSnapshot {
+
+        @Async
+        public CompletableFuture<Map<String, String>> capture() {
+            return CompletableFuture.completedFuture(snapshot());
+        }
+    }
+
+    public record Applicant(@NotBlank String name, @Email String email) {
+    }
+
+    @RestController
+    @RequestMapping("/probe")
+    public static class ProbeController {
+
+        private static final Logger log = LoggerFactory.getLogger(ProbeController.class);
+
+        private final AsyncSnapshot async;
+        private final PlatformRestClientFactory factory;
+        private final WebServerApplicationContext context;
+        private volatile RestClient self;
+
+        // The port is resolved on first use: touching the web server during context refresh starts its
+        // connector early, and the regular start then fails with "port already in use".
+        public ProbeController(AsyncSnapshot async, PlatformRestClientFactory factory,
+                WebServerApplicationContext context) {
+            this.async = async;
+            this.factory = factory;
+            this.context = context;
+        }
+
+        private RestClient self() {
+            if (self == null) {
+                self = factory.builder("probe-self")
+                        .baseUrl("http://127.0.0.1:" + context.getWebServer().getPort())
+                        .build();
+            }
+            return self;
+        }
+
+        @GetMapping("/context")
+        public Map<String, Object> context() throws Exception {
+            Map<String, Object> body = new HashMap<>(snapshot());
+            body.put("async", async.capture().get());
+            log.info("probe context");
+            return body;
+        }
+
+        @GetMapping("/outbound")
+        public Map<String, Object> outbound() {
+            Map<String, Object> body = new HashMap<>(snapshot());
+            body.put("downstream", self().get().uri("/probe/headers").retrieve().body(Map.class));
+            return body;
+        }
+
+        @GetMapping("/headers")
+        public Map<String, String> headers(@RequestHeader Map<String, String> headers) {
+            Map<String, String> lower = new HashMap<>();
+            headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
+            return lower;
+        }
+
+        @PostMapping("/applicants")
+        public Map<String, String> apply(@Valid @RequestBody Applicant applicant) {
+            return Map.of("accepted", applicant.name());
+        }
+
+        @GetMapping("/boom")
+        public String boom() {
+            throw new IllegalStateException("cannot reach jdbc:oracle:thin:@db-secret-host:1521/PRD");
+        }
+
+        @GetMapping("/slow")
+        public Map<String, Object> slow(@RequestParam long ms) throws InterruptedException {
+            Thread.sleep(ms);
+            return Map.of("slept", ms);
+        }
+    }
+
+    static Map<String, String> snapshot() {
+        Map<String, String> values = new HashMap<>();
+        RequestContext.correlationId().map(CorrelationId::value).ifPresent(id -> values.put("context", id));
+        Optional.ofNullable(MDC.get("traceId")).ifPresent(id -> values.put("mdcTraceId", id));
+        Optional.ofNullable(MDC.get("spanId")).ifPresent(id -> values.put("mdcSpanId", id));
+        Optional.ofNullable(MDC.get("correlationId")).ifPresent(id -> values.put("mdcCorrelationId", id));
+        values.put("thread", Thread.currentThread().getName());
+        return values;
+    }
+}
diff --git a/tooling/platform-service-archetype/src/main/resources/archetype-resources/AGENTS.md b/tooling/platform-service-archetype/src/main/resources/archetype-resources/AGENTS.md
index 58c3dd1..30e59b3 100644
--- a/tooling/platform-service-archetype/src/main/resources/archetype-resources/AGENTS.md
+++ b/tooling/platform-service-archetype/src/main/resources/archetype-resources/AGENTS.md
@@ -15,7 +15,7 @@ auto-configuration. Your job is business logic; DO NOT re-implement what the pla
 3. **Errors:** throw `PlatformException` subtypes (`BusinessException`, `NotFoundException`,
    `ConflictException`) with an `ErrorCode` (`DC-XX-NNNN`, where `XX` is this service's claimed
    namespace). NEVER write your own `@RestControllerAdvice` or build `ProblemDetail` by hand — the
-   platform maps everything to RFC 9457 with correlation IDs.
+   platform maps everything to RFC 9457 with the request's trace ID.
 4. **Security:** endpoints are authenticated by default. Use `@RequiresPermission("res:action")`
    for authorization and `CurrentUserAccessor` for identity. Do not define your own
    `SecurityFilterChain`; use `SecurityCustomizer` beans for adjustments.
@@ -31,7 +31,7 @@ auto-configuration. Your job is business logic; DO NOT re-implement what the pla
 9. **Resilience/scheduling/locking/idempotency/caching/flags/audit:** use the platform
    annotations and APIs (`@Retry`-style r4j config, `@LockedSchedule`, `@Idempotent`,
    `@Cacheable` + platform key convention, `FeatureFlags`, `@Audited`) — do not hand-roll.
-10. **Logging:** SLF4J, parameterized, no secrets/PII. Correlation IDs are automatic — never log
+10. **Logging:** SLF4J, parameterized, no secrets/PII. Trace IDs (the correlation id) are automatic — never log
     them manually or generate your own.
 
 ## Testing rules
diff --git a/tooling/platform-service-archetype/src/main/resources/archetype-resources/CLAUDE.md b/tooling/platform-service-archetype/src/main/resources/archetype-resources/CLAUDE.md
index 58c3dd1..30e59b3 100644
--- a/tooling/platform-service-archetype/src/main/resources/archetype-resources/CLAUDE.md
+++ b/tooling/platform-service-archetype/src/main/resources/archetype-resources/CLAUDE.md
@@ -15,7 +15,7 @@ auto-configuration. Your job is business logic; DO NOT re-implement what the pla
 3. **Errors:** throw `PlatformException` subtypes (`BusinessException`, `NotFoundException`,
    `ConflictException`) with an `ErrorCode` (`DC-XX-NNNN`, where `XX` is this service's claimed
    namespace). NEVER write your own `@RestControllerAdvice` or build `ProblemDetail` by hand — the
-   platform maps everything to RFC 9457 with correlation IDs.
+   platform maps everything to RFC 9457 with the request's trace ID.
 4. **Security:** endpoints are authenticated by default. Use `@RequiresPermission("res:action")`
    for authorization and `CurrentUserAccessor` for identity. Do not define your own
    `SecurityFilterChain`; use `SecurityCustomizer` beans for adjustments.
@@ -31,7 +31,7 @@ auto-configuration. Your job is business logic; DO NOT re-implement what the pla
 9. **Resilience/scheduling/locking/idempotency/caching/flags/audit:** use the platform
    annotations and APIs (`@Retry`-style r4j config, `@LockedSchedule`, `@Idempotent`,
    `@Cacheable` + platform key convention, `FeatureFlags`, `@Audited`) — do not hand-roll.
-10. **Logging:** SLF4J, parameterized, no secrets/PII. Correlation IDs are automatic — never log
+10. **Logging:** SLF4J, parameterized, no secrets/PII. Trace IDs (the correlation id) are automatic — never log
     them manually or generate your own.
 
 ## Testing rules
diff --git a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/java/client/GreetingClient.java b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/java/client/GreetingClient.java
index 7a0c3c6..b32285a 100644
--- a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/java/client/GreetingClient.java
+++ b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/main/java/client/GreetingClient.java
@@ -14,7 +14,7 @@ import org.springframework.web.client.RestClient;
 
 /**
  * Feature sample (features=restclient): an outbound HTTP client built from
- * {@link PlatformRestClientFactory}. The platform pre-wires correlation-id propagation, bearer-token
+ * {@link PlatformRestClientFactory}. The platform pre-wires W3C trace (traceparent) propagation, bearer-token
  * relay for authenticated requests, per-client timeouts ({@code dc.platform.restclient.clients.greeting.*})
  * and maps non-2xx responses to {@code RemoteCallException} — never build a raw {@code RestTemplate}
  * or {@code WebClient}.
diff --git a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/HttpIntegrationTestSupport.java b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/HttpIntegrationTestSupport.java
index 20fe126..5bae962 100644
--- a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/HttpIntegrationTestSupport.java
+++ b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/HttpIntegrationTestSupport.java
@@ -35,25 +35,40 @@ public abstract class HttpIntegrationTestSupport {
         registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwkSetUri);
     }
 
-    /** GET {@code path}; {@code bearer} and {@code correlationId} are optional (null = header omitted). */
-    protected HttpResponse<String> get(String path, String bearer, String correlationId)
+    /**
+     * GET {@code path}; {@code bearer} and {@code traceId} are optional (null = header omitted). A
+     * {@code traceId} is sent the standard way, as a W3C {@code traceparent}: the platform continues
+     * that trace, and its trace ID is the request's correlation id in logs and error bodies.
+     */
+    protected HttpResponse<String> get(String path, String bearer, String traceId)
             throws IOException, InterruptedException {
-        return HTTP.send(request(path, bearer, correlationId), HttpResponse.BodyHandlers.ofString());
+        return HTTP.send(request(path, bearer, traceId), HttpResponse.BodyHandlers.ofString());
     }
 
-    protected HttpRequest request(String path, String bearer, String correlationId) {
+    protected HttpRequest request(String path, String bearer, String traceId) {
         HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                 .timeout(Duration.ofSeconds(10))
                 .GET();
         if (bearer != null) {
             builder.header("Authorization", "Bearer " + bearer);
         }
-        if (correlationId != null) {
-            builder.header("X-Correlation-Id", correlationId);
+        if (traceId != null) {
+            builder.header("traceparent", traceparent(traceId));
         }
         return builder.build();
     }
 
+    /** A fresh random 128-bit W3C trace ID (32 lowercase hex). */
+    protected static String randomTraceId() {
+        return java.util.UUID.randomUUID().toString().replace("-", "");
+    }
+
+    /** {@code traceparent} for {@code traceId}, sampled, with a random parent span id. */
+    protected static String traceparent(String traceId) {
+        String spanId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
+        return "00-" + traceId + "-" + spanId + "-01";
+    }
+
     protected static HttpClient http() {
         return HTTP;
     }
diff --git a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/StructuredLoggingTest.java b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/StructuredLoggingTest.java
index 7c54c74..7c61fc0 100644
--- a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/StructuredLoggingTest.java
+++ b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/StructuredLoggingTest.java
@@ -2,7 +2,6 @@ package ${package};
 
 import static org.assertj.core.api.Assertions.assertThat;
 
-import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
 import ${package}.hello.HelloService;
 import com.jayway.jsonpath.JsonPath;
 import java.net.http.HttpResponse;
@@ -21,8 +20,9 @@ import org.springframework.boot.test.system.OutputCaptureExtension;
 
 /**
  * Deployment-mode structured logging, asserted on the events this service actually emits: every event
- * is one JSON object on stdout carrying the agreed fields, the inbound correlation id reaches the
- * application log, pooled request threads never leak a previous request's id, and bearer tokens never
+ * is one JSON object on stdout carrying the agreed fields, the caller's W3C trace ID (sent in
+ * {@code traceparent}) reaches the application log as {@code traceId}, pooled request threads never
+ * leak a previous request's trace, no custom correlation header is echoed, and bearer tokens never
  * reach the log.
  */
 @ExtendWith(OutputCaptureExtension.class)
@@ -31,59 +31,65 @@ class StructuredLoggingTest extends HttpIntegrationTestSupport {
     private static final String GREETING_EVENT = "Composing greeting";
 
     @Test
-    void requestEventIsJsonWithServiceAndInboundCorrelation(CapturedOutput output) throws Exception {
-        String correlationId = CorrelationId.random().value();
+    void requestEventIsJsonWithServiceAndTheCallersTrace(CapturedOutput output) throws Exception {
+        String traceId = randomTraceId();
         String token = ISSUER.token("alice");
 
-        HttpResponse<String> response = get("/hello?name=alice", token, correlationId);
+        HttpResponse<String> response = get("/hello?name=alice", token, traceId);
 
         assertThat(response.statusCode()).isEqualTo(200);
-        assertThat(response.headers().firstValue("X-Correlation-Id")).contains(correlationId);
-        Map<String, Object> event = singleEvent(output, GREETING_EVENT, correlationId);
+        assertThat(response.headers().firstValue("X-Correlation-Id")).isEmpty();
+        Map<String, Object> event = singleEvent(output, GREETING_EVENT, traceId);
         assertThat(event)
                 .containsEntry("level", "INFO")
                 .containsEntry("logger", HelloService.class.getName())
                 .containsEntry("service", "${artifactId}")
-                .containsEntry("message", GREETING_EVENT);
+                .containsEntry("message", GREETING_EVENT)
+                .doesNotContainKey("correlationId");
+        assertThat((String) event.get("spanId")).matches("[0-9a-f]{16}");
         assertThat(OffsetDateTime.parse((String) event.get("@timestamp"))).isNotNull();
         assertThat(output.getAll()).doesNotContain(token);
     }
 
     @Test
-    void invalidInboundCorrelationIdIsReplacedNotEchoed(CapturedOutput output) throws Exception {
-        HttpResponse<String> response = get("/hello", ISSUER.token("alice"), "not-a-valid-id");
+    void invalidTraceparentStartsANewTraceAndIsNeverLoggedRaw(CapturedOutput output) throws Exception {
+        String invalid = "00-not-a-trace-INJECTED-01";
+        HttpResponse<String> response = http().send(
+                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/hello"))
+                        .header("Authorization", "Bearer " + ISSUER.token("alice"))
+                        .header("traceparent", invalid)
+                        .GET().build(),
+                HttpResponse.BodyHandlers.ofString());
 
-        String issued = response.headers().firstValue("X-Correlation-Id").orElseThrow();
-        assertThat(issued).matches("[0-9a-f]{32}");
-        assertThat(singleEvent(output, GREETING_EVENT, issued)).isNotNull();
-        assertThat(output.getAll()).doesNotContain("\"correlationId\":\"not-a-valid-id\"");
+        assertThat(response.statusCode()).isEqualTo(200);
+        assertThat(events(output).stream().filter(event -> GREETING_EVENT.equals(event.get("message"))))
+                .anySatisfy(event -> assertThat((String) event.get("traceId")).matches("[0-9a-f]{32}"));
+        assertThat(output.getAll()).doesNotContain(invalid).doesNotContain("INJECTED");
     }
 
     @Test
-    void concurrentRequestsKeepTheirOwnCorrelationAndPooledThreadsDoNotLeakIt(CapturedOutput output) {
+    void concurrentRequestsKeepTheirOwnTraceAndPooledThreadsDoNotLeakIt(CapturedOutput output) {
         String token = ISSUER.token("alice");
-        List<String> ids = IntStream.range(0, 8).mapToObj(i -> CorrelationId.random().value()).toList();
+        List<String> ids = IntStream.range(0, 8).mapToObj(i -> randomTraceId()).toList();
 
         List<HttpResponse<String>> responses = ids.stream()
                 .map(id -> http().sendAsync(request("/hello?name=c", token, id), HttpResponse.BodyHandlers.ofString()))
                 .toList().stream().map(CompletableFuture::join).toList();
 
-        for (int i = 0; i < ids.size(); i++) {
-            assertThat(responses.get(i).statusCode()).isEqualTo(200);
-            assertThat(responses.get(i).headers().firstValue("X-Correlation-Id")).contains(ids.get(i));
+        for (HttpResponse<String> response : responses) {
+            assertThat(response.statusCode()).isEqualTo(200);
         }
-        Set<String> logged = events(output).stream()
-                .filter(event -> GREETING_EVENT.equals(event.get("message")))
-                .map(event -> (String) event.get("correlationId"))
-                .filter(ids::contains)
-                .collect(Collectors.toSet());
-        assertThat(logged).containsExactlyInAnyOrderElementsOf(ids);
-
-        // A follow-up request without the header gets a FRESH id on a reused worker thread.
-        String fresh = http().sendAsync(request("/hello", token, null), HttpResponse.BodyHandlers.ofString())
-                .join().headers().firstValue("X-Correlation-Id").orElseThrow();
-        assertThat(ids).doesNotContain(fresh);
-        assertThat(singleEvent(output, GREETING_EVENT, fresh)).isNotNull();
+        Set<String> logged = greetingTraceIds(output);
+        assertThat(logged).containsAll(ids);
+
+        // A follow-up request without traceparent gets a FRESH trace on a reused worker thread.
+        http().sendAsync(request("/hello", token, null), HttpResponse.BodyHandlers.ofString()).join();
+        Set<String> after = greetingTraceIds(output);
+        after.removeAll(logged);
+        assertThat(after).singleElement().satisfies(fresh -> {
+            assertThat(fresh).matches("[0-9a-f]{32}");
+            assertThat(ids).doesNotContain(fresh);
+        });
     }
 
     @Test
@@ -102,12 +108,20 @@ class StructuredLoggingTest extends HttpIntegrationTestSupport {
                 .contains("\n");
     }
 
-    private static Map<String, Object> singleEvent(CapturedOutput output, String message, String correlationId) {
+    private static Set<String> greetingTraceIds(CapturedOutput output) {
+        return events(output).stream()
+                .filter(event -> GREETING_EVENT.equals(event.get("message")))
+                .map(event -> (String) event.get("traceId"))
+                .filter(java.util.Objects::nonNull)
+                .collect(Collectors.toCollection(java.util.HashSet::new));
+    }
+
+    private static Map<String, Object> singleEvent(CapturedOutput output, String message, String traceId) {
         List<Map<String, Object>> matches = events(output).stream()
                 .filter(event -> message.equals(event.get("message")))
-                .filter(event -> correlationId.equals(event.get("correlationId")))
+                .filter(event -> traceId.equals(event.get("traceId")))
                 .toList();
-        assertThat(matches).as("events '%s' with correlationId %s", message, correlationId).hasSize(1);
+        assertThat(matches).as("events '%s' with traceId %s", message, traceId).hasSize(1);
         return matches.getFirst();
     }
 
diff --git a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/client/GreetingClientTest.java b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/client/GreetingClientTest.java
index 617ed46..c620d5d 100644
--- a/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/client/GreetingClientTest.java
+++ b/tooling/platform-service-archetype/src/main/resources/archetype-resources/src/test/java/client/GreetingClientTest.java
@@ -7,7 +7,6 @@ import static org.assertj.core.api.Assertions.assertThatThrownBy;
 import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
 
 import ${package}.HttpIntegrationTestSupport;
-import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
 import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
 import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
 import com.jayway.jsonpath.JsonPath;
@@ -46,7 +45,7 @@ import org.springframework.web.client.ResourceAccessException;
  * Drives {@link GreetingClient} — built by the platform's {@link PlatformRestClientFactory} — through
  * REAL calls to a loopback stub: URI composition, method, headers, JSON both ways, empty bodies, 4xx/5xx
  * mapping to {@link RemoteCallException}, a bounded read timeout, a refused connection, and the
- * inbound-request → application log → outbound-call chain (correlation id + bearer-token relay).
+ * inbound-request → application log → outbound-call chain (W3C trace + bearer-token relay).
  */
 @ExtendWith(OutputCaptureExtension.class)
 class GreetingClientTest extends HttpIntegrationTestSupport {
@@ -86,7 +85,7 @@ class GreetingClientTest extends HttpIntegrationTestSupport {
         assertThat(request.method()).isEqualTo("GET");
         assertThat(request.path()).isEqualTo("/greetings/bob");
         assertThat(request.header("Accept")).contains("application/json");
-        // Called outside any inbound request: no identity and no correlation to relay.
+        // Called outside any inbound request: no identity to relay, and never a custom correlation header.
         assertThat(request.header("Authorization")).isNull();
         assertThat(request.header("X-Correlation-Id")).isNull();
     }
@@ -123,14 +122,13 @@ class GreetingClientTest extends HttpIntegrationTestSupport {
     }
 
     @Test
-    void serverErrorMapsToRemoteCallExceptionWithRemoteCorrelation() {
-        String remoteId = CorrelationId.random().value();
-        STUB.respond(503, "unavailable", Map.of("X-Correlation-Id", remoteId));
+    void serverErrorMapsToRemoteCallExceptionWithStatusAndBody() {
+        STUB.respond(503, "unavailable");
 
         assertThatThrownBy(() -> client.fetch("bob"))
                 .isInstanceOfSatisfying(RemoteCallException.class, e -> {
                     assertThat(e.status()).isEqualTo(503);
-                    assertThat(e.remoteCorrelationId()).contains(remoteId);
+                    assertThat(e.bodySnippet()).isEqualTo("unavailable");
                 });
     }
 
@@ -156,17 +154,19 @@ class GreetingClientTest extends HttpIntegrationTestSupport {
     }
 
     @Test
-    void inboundCorrelationAndTokenFlowThroughTheLogToTheOutboundCall(CapturedOutput output) throws Exception {
+    void inboundTraceAndTokenFlowThroughTheLogToTheOutboundCall(CapturedOutput output) throws Exception {
         STUB.respond(200, "{\"message\":\"relayed\"}");
-        String correlationId = CorrelationId.random().value();
+        String traceId = randomTraceId();
         String token = ISSUER.token("alice");
 
-        HttpResponse<String> response = get("/test-support/relay", token, correlationId);
+        HttpResponse<String> response = get("/test-support/relay", token, traceId);
 
         assertThat(response.statusCode()).isEqualTo(200);
         assertThat(response.body()).isEqualTo("relayed");
         Recorded outbound = STUB.take();
-        assertThat(outbound.header("X-Correlation-Id")).isEqualTo(correlationId);
+        // Same trace downstream, carried only by the standard header.
+        assertThat(outbound.header("traceparent")).startsWith("00-" + traceId + "-");
+        assertThat(outbound.header("X-Correlation-Id")).isNull();
         assertThat(outbound.header("Authorization")).isEqualTo("Bearer " + token);
         List<Map<String, Object>> fetchEvents = output.getOut().lines()
                 .filter(line -> line.startsWith("{"))
@@ -174,7 +174,7 @@ class GreetingClientTest extends HttpIntegrationTestSupport {
                 .filter(event -> "Fetching remote greeting".equals(event.get("message")))
                 .toList();
         assertThat(fetchEvents).singleElement().satisfies(event ->
-                assertThat(event).containsEntry("correlationId", correlationId));
+                assertThat(event).containsEntry("traceId", traceId));
         assertThat(output.getAll()).doesNotContain(token);
     }
 
diff --git a/tooling/scripts/golden-path.sh b/tooling/scripts/golden-path.sh
index fd00467..56b0655 100755
--- a/tooling/scripts/golden-path.sh
+++ b/tooling/scripts/golden-path.sh
@@ -11,7 +11,8 @@
 #      generated `mvn verify` (every expected test class must run: no zero-test or skipped passes), check
 #      the executable jar, boot it with `java -jar` and probe it over real HTTP: readiness, capability
 #      report (present AND absent capabilities), 401 for missing/untrusted/expired/malformed tokens, 200
-#      for a token from a loopback JWKS issuer, correlation echo, structured JSON log events;
+#      for a token from a loopback JWKS issuer, W3C trace continuation (traceparent in, traceId in the
+#      log, no custom correlation header out), structured JSON log events;
 #   4. for the requested service (data,restclient) additionally: the documented `mvn spring-boot:run`
 #      launch, the `local` profile (console logs), `prod` with each mandatory setting missing (must fail
 #      at startup with an actionable message), and `prod` with externally supplied fixture settings,
@@ -88,7 +89,9 @@ echo "   workdir=$WORKDIR  repo=$MAVEN_REPO  evidence=$EVIDENCE"
 step "1 Install the platform into the isolated repository"
 START=$(date +%s)
 if [ "${GP_SKIP_INSTALL:-0}" != 1 ]; then
-  "${MVN[@]}" -T"${GP_THREADS:-1C}" install -DskipTests -l "$(native_path "$EVIDENCE/platform-install.log")" \
+  # clean: a stale target/jacoco.exec from an earlier local build no longer matches the classes and
+  # fails the JaCoCo check even with tests skipped (CI checkouts are clean; developer trees are not).
+  "${MVN[@]}" -T"${GP_THREADS:-1C}" clean install -DskipTests -l "$(native_path "$EVIDENCE/platform-install.log")" \
       || fail "platform install (see $EVIDENCE/platform-install.log)"
 fi
 REV=$("${MVN[@]}" -q -N help:evaluate -Dexpression=revision -DforceStdout)
@@ -291,9 +294,13 @@ probe_http() { # label features log artifact [prod]
   status_of 401 "$label untrusted token" -H "Authorization: Bearer $UNTRUSTED" "$base/hello"
   status_of 401 "$label expired token" -H "Authorization: Bearer $EXPIRED" "$base/hello"
   status_of 401 "$label malformed token" -H "Authorization: Bearer not-a-jwt" "$base/hello"
-  status_of 200 "$label authenticated /hello" -H "Authorization: Bearer $TOKEN" -H "X-Correlation-Id: $cid" "$base/hello?name=gate"
+  # The trace id is the only correlation id (HD5): sent as traceparent, it must appear as the log's traceId;
+  # a stray X-Correlation-Id is ignored and never echoed.
+  status_of 200 "$label authenticated /hello" -H "Authorization: Bearer $TOKEN" \
+      -H "traceparent: 00-$cid-0102030405060708-01" -H "X-Correlation-Id: ffffffffffffffffffffffffffffffff" \
+      "$base/hello?name=gate"
   [ "$(cat "$WORKDIR/body")" = "Hello, gate!" ] || fail "$label: unexpected /hello body: $(cat "$WORKDIR/body")"
-  grep -qi "^x-correlation-id: $cid" "$WORKDIR/headers" || fail "$label: correlation id not echoed"
+  if grep -qi "^x-correlation-id:" "$WORKDIR/headers"; then fail "$label: a custom correlation header was echoed"; fi
   if [ -n "$prod" ]; then
     status_of 401 "$label anonymous /actuator/platform in prod" "$base/actuator/platform"
     status_of 200 "$label authenticated /actuator/platform" -H "Authorization: Bearer $TOKEN" "$base/actuator/platform"
diff --git a/tooling/scripts/support/GateSupport.java b/tooling/scripts/support/GateSupport.java
index d1a3d37..87b5aae 100644
--- a/tooling/scripts/support/GateSupport.java
+++ b/tooling/scripts/support/GateSupport.java
@@ -17,12 +17,12 @@ import java.util.Map;
  * <pre>
  *   java -cp classes:platform-test-api.jar GateSupport idp DIR   # loopback JWKS + tokens until DIR/stop exists
  *   java -cp ... GateSupport freeport                            # prints a free loopback TCP port
- *   java -cp ... GateSupport logcheck LOG SERVICE [CORRELATION_ID] [FORBIDDEN...]
+ *   java -cp ... GateSupport logcheck LOG SERVICE [TRACE_ID] [FORBIDDEN...]
  * </pre>
  *
  * logcheck parses every stdout line that starts with '{' as a JSON object and requires the structured
  * log contract (@timestamp, level, logger, message, service=SERVICE). Non-JSON lines (the Boot banner, JVM
- * warnings) are counted and reported, not parsed. With CORRELATION_ID, at least one event must carry it;
+ * warnings) are counted and reported, not parsed. With TRACE_ID, at least one event must carry it as {@code traceId} (no event may carry {@code correlationId});
  * any FORBIDDEN string (e.g. a bearer token) appearing anywhere in the log fails the check.
  */
 public final class GateSupport {
@@ -33,7 +33,7 @@ public final class GateSupport {
             case "freeport" -> System.out.println(freePort());
             case "logcheck" -> System.exit(logcheck(args));
             default -> {
-                System.err.println("usage: GateSupport idp DIR | freeport | logcheck LOG SERVICE [CORRELATION_ID] [FORBIDDEN...]");
+                System.err.println("usage: GateSupport idp DIR | freeport | logcheck LOG SERVICE [TRACE_ID] [FORBIDDEN...]");
                 System.exit(2);
             }
         }
@@ -92,7 +92,10 @@ public final class GateSupport {
             if (!service.equals(event.get("service"))) {
                 problems.add("event with service=" + event.get("service") + " (expected " + service + ")");
             }
-            if (correlationId != null && correlationId.equals(event.get("correlationId"))) {
+            if (event.containsKey("correlationId")) {
+                problems.add("event carries the removed correlationId field: " + abbreviate(line));
+            }
+            if (correlationId != null && correlationId.equals(event.get("traceId"))) {
                 correlated = true;
             }
         }
@@ -100,7 +103,7 @@ public final class GateSupport {
             problems.add("no JSON log events found");
         }
         if (!correlated) {
-            problems.add("no event carries correlationId " + correlationId);
+            problems.add("no event carries traceId " + correlationId);
         }
         for (String secret : forbidden) {
             if (!secret.isEmpty() && content.contains(secret)) {
```
