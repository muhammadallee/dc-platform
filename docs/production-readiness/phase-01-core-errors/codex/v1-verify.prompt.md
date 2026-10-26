# Verification pass — DC Platform hardening Phase 1 (plan §5.5)

Material changes were made after Round 2 to address YOUR Round 2 findings F11–F15 and the AC notes you maintained. For each finding below state **accept** (the diff resolves it) or **reject** (with reasons, file:line). This is a verification pass, not a third review round: stay on these findings and on regressions the diff itself introduces.

## Your Round 2 findings and re-evaluation (verbatim)

Reviewed `952d0a1` against `f231689`, excluding `docs/production-readiness` and `docs/book`. Findings are based on source and cached dependency bytecode. Maven/container gates were not rerun; an attempted in-memory probe could not start under sandbox restrictions. No execution pass is claimed.

No Critical findings or new confirmed header/log-injection defects. No specific Kafka, audit, or messaging compile regression was established.

New finding numbers continue after Round 1’s F1–F10.

### F11 — Boot’s default decorator still lets empty snapshots inherit context
- Severity: High
- Location: `core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java:41`
- Failure scenario: Capture a context-free runnable through `ContextPropagatingTaskDecorator`, then execute it inside caller A’s context—for example, a previously decorated callback invoked during request processing. The runnable sees A’s trace ID and extras.
- Proof or reasoning: `getValue()` returns `null` for an empty context, so Micrometer omits the accessor from the snapshot. Cached Boot 4.1.0 bytecode shows its configured decorator uses the no-argument constructor; that constructor builds a snapshot factory whose `clearMissing` default is `false`. Consequently, neither `setValue()` nor the new isolating scope runs. The revised tests call the clearing method directly and therefore miss this integration path.
- Proposed fix: Configure the platform’s default decorator to use `clearMissing(true)`, respecting consumer overrides, or explicitly represent absence in captured platform context. Test the actual decorator with an empty capture applied inside an existing context, including restoration afterward.

### F12 — The core capability descriptor still cannot be overridden
- Severity: High
- Location: `core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java:35`
- Failure scenario: A consumer supplies a `coreCapabilityDescriptor` bean → startup encounters competing bean definitions with Boot’s default override prohibition. Supplying the descriptor under another name leaves both descriptors active.
- Proof or reasoning: Unlike the revised errors descriptor, the core descriptor has no `@ConditionalOnMissingBean`. Its tests contain no descriptor-override case. This is an inherited gap that remains within the explicitly required Phase 1 override contract.
- Proposed fix: Add back-off for the consumer’s core descriptor and test bean identity and absence of duplicate descriptors. At minimum, support the documented-name replacement consistently with errors; reconcile any name-only exception with R4.3’s type-based requirement.

### F13 — Native MVC method-validation errors omit field details
- Severity: Medium
- Location: `errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java:165`
- Failure scenario: A controller without class-level `@Validated` declares `@RequestParam @Min(1) int quantity`; a request supplies `quantity=0` → the response is 400 but lacks the required field path and validation message.
- Proof or reasoning: Spring MVC raises `HandlerMethodValidationException`. The platform handles `MethodArgumentNotValidException` and `ConstraintViolationException`, but not this native MVC validation path. The inherited handler delegates to `handleExceptionInternal`, which assigns `DC-CORE-0499` and decorates the body without constructing `errors[]`. Cached Spring 7.0.8 bytecode confirms this delegation.
- Proposed fix: Override `handleHandlerMethodValidationException`, map input-validation results into field/message entries, and apply the existing rejected-value policy. Preserve 500 semantics for return-value validation failures. Add MVC tests for constrained query parameters and nested validated arguments.

### F14 — Framework 5xx errors lose their server-side diagnostic
- Severity: Medium
- Location: `errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java:162`
- Failure scenario: A `DeferredResult` times out, or MVC encounters a response-conversion failure before commitment → the client receives a generic 503/500, but normal production logging contains no corresponding exception stack trace.
- Proof or reasoning: Framework exceptions enter the inherited specific handlers and then `handleExceptionInternal`; they bypass both platform methods that call `log.error`. The new hook replaces details through `factory.decorate` but never logs the exception. Spring’s superclass does not log the normal, uncommitted-response path. Thus R3.2’s server-side diagnostic requirement remains unmet.
- Proposed fix: Log handled framework 5xx exceptions with their throwable while the request context is active, avoiding duplicate logging. Add captured-log assertions for an async timeout and a framework 500, including the trace ID.

### F15 — Direct requests to the error endpoint omit `instance`
- Severity: Medium
- Location: `errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorController.java:66`
- Failure scenario: Request `/error` directly in the minimal service, or as an authenticated caller → a 500 ProblemDetail is returned without `instance`.
- Proof or reasoning: A normal REQUEST dispatch has no `jakarta.servlet.error.request_uri` attribute. The controller passes `null` to the factory, which consequently never sets `instance`. Its direct converter write bypasses MVC’s usual ProblemDetail instance completion.
- Proposed fix: Fall back to `request.getRequestURI()` when the error-dispatch URI attribute is absent. Test direct access as well as container ERROR dispatch.

## Round 1 disposition

| Round 1 finding | Status | Assessment |
|---|---|---|
| F1 — Rejected bearer tokens bypass writer | **Resolved** | Resource-server authentication and denied handlers now use the platform writers. Rejected-token tests and consumer scenarios were added. |
| F2 — Null customizer source | **Resolved** | Framework decoration now occurs in `handleExceptionInternal` with the originating exception; the integration customizer dereferences it. |
| F3 — Additional filter after registration override | **Resolved** | Filter creation now also backs off on `platformCorrelationFilterRegistration`. Actual servlet-registration assertions remain absent. |
| F4 — Empty snapshots retain caller context | **Partially resolved** | Explicit clearing and nonempty replacement now isolate scopes. Boot’s default empty-snapshot path still bypasses clearing: F11. |
| F5 — Async redispatch loses trace ID | **Resolved** | ASYNC dispatch registration and filtering are enabled. Consumer success/failure scenarios were added; timeout and redispatch-cleanup coverage remain absent. |
| F6 — Minimal service loses correlation | **Resolved** | Core starter now supplies the tracing bridge and Boot infrastructure; minimal-service tests cover valid and missing headers in error responses. |
| F7 — Security standard fields omitted | **Resolved** | Writers now emit `type` and `instance`, with full-contract assertions for 401/403. |
| F8 — Unsafe/incomplete rejection diagnostics | **Resolved** | Diagnostics use propagation outcome and a fixed length-only marker, without header content. |
| F9 — Servlet error dispatch outside contract | **Resolved** | The new error controller handles servlet dispatches, with security ERROR permission and consumer trace checks. F15 concerns a separate direct-request path. |
| F10 — Formatting-sensitive uniqueness gate | **Resolved** | Javac AST parsing handles formatting/qualified names and rejects unsupported nonliteral constructions; negative fixtures were added. |

## Acceptance criteria not met

- **AC1–AC2 — SLICE evidence incomplete:** The tracing slice asserts context/MDC, while the minimal-service tests assert ProblemDetails. No added test verifies the real tracing-to-ProblemDetail path and that same request’s logged MDC together. Consumer coverage is improved.
- **AC3 — Consumer assertions incomplete:** The log check excludes `INJECTEDVALUE`, but does not check absence of the oversized header value; marker presence is checked globally rather than for each rejected case. A2 appropriately permits unit-only control-character coverage.
- **AC8 — SLICE assertion incomplete:** The concurrent tracing test still checks only async `context`, not async `mdcTraceId` (`TraceCorrelationIntegrationTest.java:290`). Concurrent ProblemDetail coverage has been added.
- **AC9 — Incomplete isolation/cleanup evidence:** F11 remains. Tests also do not establish cleanup after actual async/error redispatches.
- **AC11 / R3.3:** Native MVC method-validation responses lack field/message entries, as described in F13.
- **AC12 — SLICE log assertions absent:** Consumer coverage now includes both exception categories, but slice tests still do not assert their stack traces and trace IDs in logs. F14 additionally violates R3.2 on framework failures.
- **AC16:** F12 remains. Per-type overrides are also not universally demonstrated: banner and registration overrides remain name-based, and registration tests do not inspect actual servlet registrations.
- **AC17 — Unverified, not demonstrated failed:** Downstream `-amd test-compile` and the golden-path gate could not be executed here.
- **R3.1:** F15 leaves a reachable error response without `instance`.
- **R4.1 / A1:** Replacement metadata remains absent. A1 explicitly makes this a **Low, non-blocking owner-acceptance item**; the new metadata tests verify level and migration reason.

No additional implementation or test-definition gaps were found for **AC4–AC7, AC10, AC13–AC15, or AC18**, subject to A1 above. In particular, AC18 now waits for handler entry and requires exit 143. This is not an execution certification.

These dispositions assess the **proposed changes**, not completed implementation. The checkout remains at `952d0a1`; I did not rerun tests or independently verify the reported execution evidence.

| Finding | Disposition | Assessment |
|---|---|---|
| **F11** | **Accept** | Capturing absence as a non-null empty map addresses snapshot omission. The proposed no-argument decorator test exercises the failing path; assert empty context/MDC inside the task and restoration of the caller’s trace and extras afterward. |
| **F12** | **Maintain concern** | The name guard fixes the documented-name collision. D93 provides a sound reason to avoid blanket `CapabilityDescriptor` type back-off, which would suppress unrelated capabilities. However, it does not reconcile the explicit type-based R4.3 requirement. Accept the functional fix; keep the requirement discrepancy open until explicitly amended. |
| **F13** | **Accept** | The proposed mapping addresses native MVC input validation and preserves return-validation 500 semantics. Ensure the nested-object test actually exercises `HandlerMethodValidationException`: `@Valid` alone can exercise the already-supported argument-validation handler. Preserve the validation configuration switch and rejected-value policy. |
| **F14** | **Accept** | Logging framework 5xx exceptions with their throwable in `handleExceptionInternal` addresses the missing diagnostic. The proposed timeout and framework-500 tests should assert the matching request trace ID and one platform error event per exception. |
| **F15** | **Accept** | Falling back to `request.getRequestURI()` supplies `instance` for direct requests while retaining the original URI on ERROR dispatch. |

The Round 1 dispositions remain:

| Finding | Disposition |
|---|---|
| **F1, F2** | **Accept** — previous resolutions stand. |
| **F3** | **Accept** — functional resolution stands; registration evidence remains subject to AC16 below. |
| **F4** | **Accept** — the proposed F11 change closes its remaining empty-capture path. |
| **F5** | **Accept** — previous resolution stands; cleanup evidence is addressed under AC9. |
| **F6–F10** | **Accept** — previous resolutions stand. |

| AC / requirement note | Disposition | Assessment |
|---|---|---|
| **AC1–AC2** | **Accept** | Same-request assertions connecting real tracing, the ProblemDetail and the structured log resolve the gap for continued and fresh traces. Select the relevant request log event rather than merely finding the trace ID somewhere in captured output. |
| **AC3** | **Reject** | A global marker count equal to the number of rejected requests does **not** prove one marker per request: two markers for one request and zero for another still pass. Isolate each request’s log capture or associate markers with each request’s trace ID. The oversized-value exclusion is accepted; retain the existing malformed-value exclusion and A2 unit coverage. |
| **AC8** | **Accept** | Adding async `mdcTraceId` to each concurrent request’s expected-trace assertions closes the identified assertion gap. |
| **AC9** | **Accept** | F11 plus actual ASYNC/ERROR edge cleanup checks addresses the stated gaps. Record dispatch type and await completion so the test proves both redispatch paths occurred and their exit checks ran; an empty or REQUEST-only collection must not pass. Retain pool-thread cleanup coverage. |
| **AC11 / R3.3** | **Accept** | Addressed by F13, with the native-handler coverage qualification above. |
| **AC12** | **Maintain concern** | The proposed slice test covers only an unknown exception. AC12 also requires captured stack-trace and matching-trace assertions for a **5xx `PlatformException`**. Framework exceptions covered by F14 do not substitute for that category. |
| **AC16 / R4.3** | **Maintain concern** | The shared-type rationale for name-based infrastructure overrides is reasonable, but D93/D96 must be reconciled explicitly with R4.3. Also, inspecting `ServletContextInitializerBeans` establishes Boot’s collected registration initializers, not completed servlet-container registrations. Assert the consumer registration’s identity and absence of an automatically adapted duplicate; use a started container’s registrations or invocation assertions to close the actual-registration evidence gap. |
| **AC17** | **Maintain concern** | The reported downstream compile success is useful evidence, not a demonstrated failure. Closure still requires successful downstream compilation and a completed green golden-path run against the final fixes, with recorded revision and results. “Executed and recorded” alone does not establish green gates. |
| **R3.1** | **Accept** | F15 addresses the identified missing-`instance` path. |
| **R4.1 / A1** | **Maintain concern** | Unchanged: replacement metadata remains a **Low, non-blocking owner-acceptance item** under A1. The response does not resolve that acceptance. |
| **AC4–AC7, AC10, AC13–AC15, AC18** | **Accept** | No additional implementation or test-definition concern identified; A1 remains applicable. This is not execution certification. |

**New concerns**

No new Critical concerns identified. The remaining issues above concern requirement reconciliation, incomplete evidence, and assertions that could pass without proving the intended behavior.

## Diff 952d0a1..8f62fcf (docs/production-readiness excluded)
```diff
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java
index 56ff8fa..2487879 100644
--- a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java
@@ -32,7 +32,9 @@ public class PlatformBannerAutoConfiguration {
 
     private static final Logger log = LoggerFactory.getLogger(PlatformBannerAutoConfiguration.class);
 
+    // CapabilityDescriptor is a shared type (one bean per capability): overridden by name (D93).
     @Bean
+    @ConditionalOnMissingBean(name = "coreCapabilityDescriptor")
     CapabilityDescriptor coreCapabilityDescriptor() {
         return new CapabilityDescriptor("core", "ACTIVE", "");
     }
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java
index 3dcba55..2779a40 100644
--- a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java
@@ -35,10 +35,11 @@ public final class RequestContextThreadLocalAccessor implements ThreadLocalAcces
         return KEY;
     }
 
+    // Never null: an empty map records "no context" in the snapshot, so applying it isolates the task
+    // even under Boot's default decorator, whose snapshot factory does not clear missing values.
     @Override
     public Map<String, String> getValue() {
-        Map<String, String> snapshot = RequestContext.asMap();
-        return snapshot.isEmpty() ? null : snapshot;
+        return RequestContext.asMap();
     }
 
     @Override
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java
index 5379fb7..a75ddc2 100644
--- a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java
@@ -53,9 +53,26 @@ class CoreContextAutoConfigurationTest {
 
     @Test
     void backsOffWhenUserRegistrationPresent() {
-        FilterRegistrationBean<CorrelationIdFilter> mine = new FilterRegistrationBean<>(new CorrelationIdFilter(Tracer.NOOP));
+        CorrelationIdFilter theirs = new CorrelationIdFilter(Tracer.NOOP);
+        FilterRegistrationBean<CorrelationIdFilter> mine = new FilterRegistrationBean<>(theirs);
+        mine.setEnabled(false); // a consumer may even switch the filter off
         runner.withBean("platformCorrelationFilterRegistration", FilterRegistrationBean.class, () -> mine)
-                .run(context -> assertThat(context.getBean("platformCorrelationFilterRegistration")).isSameAs(mine));
+                .run(context -> {
+                    assertThat(context.getBean("platformCorrelationFilterRegistration")).isSameAs(mine);
+                    assertThat(context).doesNotHaveBean("correlationIdFilter");
+                    // What Boot would register with the servlet container: no second, auto-registered
+                    // CorrelationIdFilter next to the consumer's (disabled) registration.
+                    var initializers = new org.springframework.boot.web.servlet.ServletContextInitializerBeans(
+                            context.getBeanFactory());
+                    java.util.List<Object> correlationFilters = new java.util.ArrayList<>();
+                    initializers.forEach(initializer -> {
+                        if (initializer instanceof FilterRegistrationBean<?> registration
+                                && registration.getFilter() instanceof CorrelationIdFilter filter) {
+                            correlationFilters.add(filter);
+                        }
+                    });
+                    assertThat(correlationFilters).containsExactly(theirs);
+                });
     }
 
     @Test
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java
index cf3418f..45b6118 100644
--- a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java
@@ -74,4 +74,17 @@ class PlatformBannerAutoConfigurationTest {
                             .contains("platform: alpha[ACTIVE], core[ACTIVE], zeta[ACTIVE] (inmemory)");
                 });
     }
+
+    @Test
+    void consumerCoreDescriptorReplacesThePlatformOne() {
+        ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor mine =
+                new ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor("core", "ACTIVE", "custom");
+        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
+                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
+                        PlatformBannerAutoConfiguration.class))
+                .withBean("coreCapabilityDescriptor", ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor.class,
+                        () -> mine)
+                .run(context -> assertThat(context)
+                        .getBean(ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor.class).isSameAs(mine));
+    }
 }
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/TraceCorrelationIntegrationTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/TraceCorrelationIntegrationTest.java
index 4be6ef5..9218657 100644
--- a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/TraceCorrelationIntegrationTest.java
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/TraceCorrelationIntegrationTest.java
@@ -56,8 +56,9 @@ class TraceCorrelationIntegrationTest {
     private static final JsonMapper JSON = JsonMapper.builder().build();
     private static final HttpClient HTTP = HttpClient.newHttpClient();
 
-    /** Records the MDC at the very edge of the servlet chain (before tracing, after everything). */
+    /** Records the MDC at the very edge of the servlet chain (before tracing, after everything), per dispatch. */
     static final ConcurrentLinkedQueue<Map<String, String>> EDGE_MDC = new ConcurrentLinkedQueue<>();
+    static final ConcurrentLinkedQueue<String> EDGE_DISPATCHES = new ConcurrentLinkedQueue<>();
 
     @SpringBootConfiguration
     @EnableAutoConfiguration
@@ -72,13 +73,29 @@ class TraceCorrelationIntegrationTest {
                     chain.doFilter(request, response);
                 } finally {
                     EDGE_MDC.add(Optional.ofNullable(MDC.getCopyOfContextMap()).orElse(Map.of()));
+                    EDGE_DISPATCHES.add(request.getDispatcherType().name());
                 }
             };
             FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
             registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
+            registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ASYNC,
+                    jakarta.servlet.DispatcherType.ERROR);
             return registration;
         }
 
+        // Inner rejection: sendError → a container ERROR dispatch the edge recorder also sees.
+        @Bean
+        FilterRegistrationBean<Filter> rejecting() {
+            Filter filter = (request, response, chain) -> {
+                if ("/reject".equals(((jakarta.servlet.http.HttpServletRequest) request).getRequestURI())) {
+                    ((jakarta.servlet.http.HttpServletResponse) response).sendError(409);
+                    return;
+                }
+                chain.doFilter(request, response);
+            };
+            return new FilterRegistrationBean<>(filter);
+        }
+
         // Instrumented like any Boot-managed client: the observation registry adds traceparent.
         @Bean
         RestClient selfClient(ObservationRegistry observationRegistry) {
@@ -124,6 +141,11 @@ class TraceCorrelationIntegrationTest {
             return body;
         }
 
+        @GetMapping("/callable")
+        java.util.concurrent.Callable<Map<String, String>> callable() {
+            return TraceCorrelationIntegrationTest::snapshot;
+        }
+
         @GetMapping("/headers")
         Map<String, String> headers(@RequestHeader Map<String, String> headers) {
             Map<String, String> lowerCased = new HashMap<>();
@@ -271,6 +293,23 @@ class TraceCorrelationIntegrationTest {
             assertThat(EDGE_MDC).hasSize(40).allSatisfy(mdc -> assertThat(mdc).isEmpty());
         }
 
+        @Test
+        void asyncAndErrorRedispatchesSeeTheTraceAndLeaveTheEdgesClean() throws Exception {
+            EDGE_MDC.clear();
+            EDGE_DISPATCHES.clear();
+            String traceId = randomTraceId();
+            Map<String, Object> body = get("/callable", Map.of("traceparent", traceparent(traceId)));
+            assertThat(body).containsEntry("context", traceId).containsEntry("mdcTraceId", traceId);
+
+            HttpRequest rejected = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/reject"))
+                    .header("traceparent", traceparent(traceId)).build();
+            assertThat(HTTP.send(rejected, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
+
+            // The redispatches really happened and every edge — REQUEST, ASYNC and ERROR — was clean.
+            assertThat(EDGE_DISPATCHES).contains("REQUEST", "ASYNC", "ERROR");
+            assertThat(EDGE_MDC).hasSize(EDGE_DISPATCHES.size() * 2).allSatisfy(mdc -> assertThat(mdc).isEmpty());
+        }
+
         @Test
         void twoHundredConcurrentRequestsNeverBleedContext() throws Exception {
             // Plan §3.11: concurrency tests must pass 3 consecutive runs, so the round repeats in-test.
@@ -287,7 +326,7 @@ class TraceCorrelationIntegrationTest {
                                 .containsEntry("context", expected).containsEntry("mdcTraceId", expected);
                         @SuppressWarnings("unchecked")
                         Map<String, String> async = (Map<String, String>) body.get("async");
-                        assertThat(async).containsEntry("context", expected);
+                        assertThat(async).containsEntry("context", expected).containsEntry("mdcTraceId", expected);
                     }
                 }
             }
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessorTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessorTest.java
index 3a6752a..75ca9f7 100644
--- a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessorTest.java
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessorTest.java
@@ -32,8 +32,33 @@ class RequestContextThreadLocalAccessorTest {
     }
 
     @Test
-    void capturesNothingWithoutAContext() {
-        assertThat(accessor.getValue()).isNull();
+    void capturesAbsenceExplicitly() {
+        // Never null: a null value would drop the accessor from the snapshot entirely.
+        assertThat(accessor.getValue()).isNotNull().isEmpty();
+    }
+
+    @Test
+    void bootsDefaultDecoratorRunsAContextFreeTaskWithoutTheExecutingCallersContext() throws Exception {
+        // Boot wires ContextPropagatingTaskDecorator with its no-arg constructor (clearMissing=false):
+        // the path the explicit-absence capture exists for.
+        org.springframework.core.task.support.ContextPropagatingTaskDecorator decorator =
+                new org.springframework.core.task.support.ContextPropagatingTaskDecorator();
+        java.util.Map<String, Object> seen = new java.util.HashMap<>();
+        Runnable contextFree = decorator.decorate(() -> {
+            seen.put("context", RequestContext.correlationId());
+            seen.put("mdcTraceId", java.util.Optional.ofNullable(MDC.get("traceId")));
+            seen.put("mdcTenant", java.util.Optional.ofNullable(MDC.get("tenant")));
+        });
+        CorrelationId callerA = new CorrelationId("11111111111111111111111111111111");
+        try (AutoCloseable outer = RequestContext.open(callerA, Map.of("tenant", "dxb"))) {
+            contextFree.run(); // e.g. a callback decorated earlier, invoked while serving caller A
+            assertThat(RequestContext.correlationId()).as("caller A restored").contains(callerA);
+            assertThat(MDC.get("traceId")).isEqualTo(callerA.value());
+            assertThat(MDC.get("tenant")).isEqualTo("dxb");
+        }
+        assertThat(seen.get("context")).isEqualTo(java.util.Optional.empty());
+        assertThat(seen.get("mdcTraceId")).isEqualTo(java.util.Optional.empty());
+        assertThat(seen.get("mdcTenant")).isEqualTo(java.util.Optional.empty());
     }
 
     @Test
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorController.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorController.java
index 27cbe71..1e7218a 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorController.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorController.java
@@ -63,7 +63,9 @@ public class PlatformErrorController implements ErrorController {
     public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
         int status = statusOf(request);
         Throwable failure = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
-        String path = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
+        // A container ERROR dispatch carries the failing URI; a direct request to the error path does not.
+        Object failingUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
+        String path = failingUri instanceof String uri ? uri : request.getRequestURI();
         ErrorCode code = status >= 500 ? PlatformExceptionHandler.UNEXPECTED_ERROR : PlatformExceptionHandler.REQUEST_REJECTED;
         if (status >= 500) {
             log.error("servlet error dispatch [{}] status {} for {}", code.value(), status, path, failure);
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java
index 672edb1..b592ea7 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java
@@ -159,6 +159,11 @@ public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {
     @Override
     protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
             HttpStatusCode statusCode, WebRequest request) {
+        if (statusCode.value() >= 500) {
+            // Framework 5xx (async timeout, conversion failure, …) reach only this hook: log them here,
+            // once, while the request context (trace ID) is still open.
+            log.error("framework exception [{}] status {}", UNEXPECTED_ERROR.value(), statusCode.value(), exception);
+        }
         ResponseEntity<Object> entity = super.handleExceptionInternal(exception, body, headers, statusCode, request);
         if (entity != null && entity.getBody() instanceof ProblemDetail problem
                 && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
@@ -171,6 +176,52 @@ public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {
         return entity;
     }
 
+    /**
+     * Spring MVC's native method validation (constraints on {@code @RequestParam}/{@code @PathVariable}
+     * or on {@code @Valid} arguments of a controller without class-level {@code @Validated}) maps to the
+     * same 400 {@code errors[]} shape as body validation. Return-value validation is a server bug and
+     * keeps its 500 with the generic body.
+     */
+    @Override
+    protected ResponseEntity<Object> handleHandlerMethodValidationException(
+            org.springframework.web.method.annotation.HandlerMethodValidationException exception, HttpHeaders headers,
+            HttpStatusCode status, WebRequest request) {
+        if (!properties.mapValidation() || status.value() >= 500) {
+            return super.handleHandlerMethodValidationException(exception, headers, status, request);
+        }
+        List<Map<String, Object>> errors = new ArrayList<>();
+        for (org.springframework.validation.method.ParameterValidationResult result
+                : exception.getParameterValidationResults()) {
+            String parameter = result.getMethodParameter().getParameterName();
+            if (result instanceof org.springframework.validation.method.ParameterErrors parameterErrors) {
+                parameterErrors.getFieldErrors().forEach(fieldError -> {
+                    Map<String, Object> entry = new LinkedHashMap<>();
+                    entry.put("field", fieldError.getField());
+                    entry.put("message", fieldError.getDefaultMessage());
+                    factory.putRejectedValue(entry, fieldError.getField(), fieldError.getRejectedValue());
+                    errors.add(entry);
+                });
+                parameterErrors.getGlobalErrors().forEach(globalError -> {
+                    Map<String, Object> entry = new LinkedHashMap<>();
+                    entry.put("field", parameter);
+                    entry.put("message", globalError.getDefaultMessage());
+                    errors.add(entry);
+                });
+            } else {
+                result.getResolvableErrors().forEach(error -> {
+                    Map<String, Object> entry = new LinkedHashMap<>();
+                    entry.put("field", parameter);
+                    entry.put("message", error.getDefaultMessage());
+                    factory.putRejectedValue(entry, parameter, result.getArgument());
+                    errors.add(entry);
+                });
+            }
+        }
+        ProblemDetail problem = factory.create(400, VALIDATION_FAILED, "Validation failed.", instancePathOf(request), exception);
+        problem.setProperty("errors", errors);
+        return ResponseEntity.badRequest().headers(headers).body(problem);
+    }
+
     @Override
     protected ResponseEntity<Object> handleHttpMessageNotReadable(
             org.springframework.http.converter.HttpMessageNotReadableException exception, HttpHeaders headers,
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorContractIntegrationTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorContractIntegrationTest.java
index e88b9d9..2d63bc7 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorContractIntegrationTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorContractIntegrationTest.java
@@ -137,4 +137,10 @@ class ErrorContractIntegrationTest {
         List<String> names = List.of(context.getBeanNamesForType(PlatformErrorController.class));
         assertThat(names).containsExactly("platformErrorController");
     }
+
+    @Test
+    void aDirectRequestToTheErrorPathStillHasAnInstance() throws Exception {
+        Map<String, Object> body = call("/error", "application/json", 500);
+        assertThat(body).containsEntry("instance", "/error").containsEntry("code", "DC-CORE-0500");
+    }
 }
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
index bea4a44..8db4e58 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
@@ -213,6 +213,25 @@ class PlatformExceptionHandlerTest {
             throw new TestInfraException();
         }
 
+        // Constraints on handler parameters: Spring MVC's native method validation applies to the
+        // whole method, so even the @Valid body failure surfaces as HandlerMethodValidationException.
+        @GetMapping("/quantity")
+        String quantity(@org.springframework.web.bind.annotation.RequestParam @jakarta.validation.constraints.Min(1)
+                int quantity) {
+            return "ok";
+        }
+
+        @PostMapping("/copies")
+        String copies(@jakarta.validation.Valid @RequestBody NewOrder order,
+                @org.springframework.web.bind.annotation.RequestParam @jakarta.validation.constraints.Min(1) int copies) {
+            return "ok";
+        }
+
+        @GetMapping("/unwritable")
+        Unwritable unwritable() {
+            return new Unwritable(); // the JSON converter fails while writing → framework 500
+        }
+
         @GetMapping("/denied")
         String denied() {
             throw new org.springframework.security.access.AccessDeniedException("method security says no");
@@ -229,6 +248,12 @@ class PlatformExceptionHandlerTest {
         }
     }
 
+    public static final class Unwritable {
+        public String getValue() {
+            throw new IllegalStateException("serializer reached db-secret-host");
+        }
+    }
+
     static final class TestInfraException extends PlatformException {
         TestInfraException() {
             super(new ErrorCode("DC-TEST-0500"), "transport down");
@@ -267,4 +292,48 @@ class PlatformExceptionHandlerTest {
             }
         }
     }
+
+    @Test
+    void nativeMethodValidationOfAQueryParameterMapsToTheErrorsShape() throws Exception {
+        mockMvc(defaults(), List.of()).perform(get("/quantity").param("quantity", "0"))
+                .andExpect(status().isBadRequest())
+                .andExpect(jsonPath("$.code").value("DC-CORE-0400"))
+                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
+                .andExpect(jsonPath("$.errors[0].message").exists())
+                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
+    }
+
+    @Test
+    void nativeMethodValidationOfANestedArgumentListsItsFieldPaths() throws Exception {
+        mockMvc(defaults(), List.of())
+                .perform(post("/copies").param("copies", "1").contentType(MediaType.APPLICATION_JSON)
+                        .content("{\"name\":\"\",\"password\":\"0123456789abcdef\"}"))
+                .andExpect(status().isBadRequest())
+                .andExpect(jsonPath("$.code").value("DC-CORE-0400"))
+                .andExpect(jsonPath("$.errors[?(@.field=='name')].message").exists());
+    }
+
+    @Test
+    void frameworkServerErrorsAreLoggedOnceWithTheThrowableAndTheTrace() throws Exception {
+        ch.qos.logback.classic.Logger handlerLog =
+                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PlatformExceptionHandler.class);
+        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
+                new ch.qos.logback.core.read.ListAppender<>();
+        appender.start();
+        handlerLog.addAppender(appender);
+        try (AutoCloseable scope = RequestContext.open(new CorrelationId(CORRELATION), Map.of())) {
+            mockMvc(defaults(), List.of()).perform(get("/unwritable"))
+                    .andExpect(status().isInternalServerError())
+                    .andExpect(jsonPath("$.code").value("DC-CORE-0500"))
+                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
+                    .andExpect(jsonPath("$.traceId").value(CORRELATION));
+        } finally {
+            handlerLog.detachAppender(appender);
+        }
+        assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
+                .singleElement().satisfies(event -> {
+                    assertThat(event.getThrowableProxy()).isNotNull();
+                    assertThat(event.getMDCPropertyMap()).containsEntry("traceId", CORRELATION);
+                });
+    }
 }
diff --git a/examples/example-minimal/src/test/java/ae/gov/dubaicustoms/example/minimal/ErrorLogCorrelationTest.java b/examples/example-minimal/src/test/java/ae/gov/dubaicustoms/example/minimal/ErrorLogCorrelationTest.java
new file mode 100644
index 0000000..a4310a0
--- /dev/null
+++ b/examples/example-minimal/src/test/java/ae/gov/dubaicustoms/example/minimal/ErrorLogCorrelationTest.java
@@ -0,0 +1,107 @@
+package ae.gov.dubaicustoms.example.minimal;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import ae.gov.dubaicustoms.platform.core.ErrorCode;
+import ae.gov.dubaicustoms.platform.core.PlatformException;
+import com.jayway.jsonpath.JsonPath;
+import java.net.URI;
+import java.net.http.HttpClient;
+import java.net.http.HttpRequest;
+import java.net.http.HttpResponse;
+import java.util.List;
+import java.util.Map;
+import java.util.UUID;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.api.extension.ExtendWith;
+import org.junit.jupiter.params.ParameterizedTest;
+import org.junit.jupiter.params.provider.ValueSource;
+import org.springframework.boot.test.context.SpringBootTest;
+import org.springframework.boot.test.context.TestConfiguration;
+import org.springframework.boot.test.system.CapturedOutput;
+import org.springframework.boot.test.system.OutputCaptureExtension;
+import org.springframework.boot.test.web.server.LocalServerPort;
+import org.springframework.context.annotation.Bean;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.RestController;
+
+/**
+ * One request, real tracing, the minimal starters only (phase-01 AC1/AC2/AC12 at slice level): the trace
+ * ID in a 5xx problem body is the trace ID of that request's own ERROR log event, which carries the full
+ * stack trace — for an unknown exception and for a 5xx {@link PlatformException}, on a continued and on
+ * a freshly started trace. The body itself never carries the exception message.
+ */
+@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
+@ExtendWith(OutputCaptureExtension.class)
+class ErrorLogCorrelationTest {
+
+    private static final HttpClient HTTP = HttpClient.newHttpClient();
+
+    @LocalServerPort
+    int port;
+
+    @TestConfiguration(proxyBeanMethods = false)
+    static class FailingEndpoints {
+
+        @Bean
+        Failing failing() {
+            return new Failing();
+        }
+    }
+
+    @RestController
+    static class Failing {
+
+        static final ErrorCode PRICING_UNAVAILABLE = new ErrorCode("DC-XMPL-0500");
+
+        @GetMapping("/test/unknown")
+        String unknown() {
+            throw new IllegalStateException("cannot reach db-secret-host");
+        }
+
+        @GetMapping("/test/platform")
+        String platform() {
+            throw new PlatformException(PRICING_UNAVAILABLE, "pricing-7.internal refused") { };
+        }
+    }
+
+    private HttpResponse<String> get(String path, String traceparent) throws Exception {
+        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
+        if (traceparent != null) {
+            request.header("traceparent", traceparent);
+        }
+        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
+    }
+
+    private static List<Map<String, Object>> errorEvents(CapturedOutput output, String traceId) {
+        return output.getOut().lines()
+                .filter(line -> line.startsWith("{"))
+                .map(line -> JsonPath.parse(line).<Map<String, Object>>json())
+                .filter(event -> "ERROR".equals(event.get("level")) && traceId.equals(event.get("traceId")))
+                .toList();
+    }
+
+    @ParameterizedTest
+    @ValueSource(strings = {"/test/unknown", "/test/platform"})
+    void continuedTraceLinksTheBodyToItsOwnLogEvent(String path, CapturedOutput output) throws Exception {
+        String traceId = UUID.randomUUID().toString().replace("-", "");
+        HttpResponse<String> response = get(path, "00-" + traceId + "-0102030405060708-01");
+
+        assertThat(response.statusCode()).isEqualTo(500);
+        assertThat(JsonPath.<String>read(response.body(), "$.traceId")).isEqualTo(traceId);
+        assertThat(JsonPath.<String>read(response.body(), "$.detail")).isEqualTo("An unexpected error occurred.");
+        assertThat(response.body()).doesNotContain("db-secret-host", "pricing-7");
+        assertThat(errorEvents(output, traceId)).singleElement().satisfies(event ->
+                assertThat((String) event.get("stack_trace")).containsAnyOf("db-secret-host", "pricing-7.internal"));
+    }
+
+    @Test
+    void freshTraceLinksTheBodyToItsOwnLogEvent(CapturedOutput output) throws Exception {
+        HttpResponse<String> response = get("/test/unknown", null);
+
+        String traceId = JsonPath.read(response.body(), "$.traceId");
+        assertThat(traceId).matches("[0-9a-f]{32}");
+        assertThat(errorEvents(output, traceId)).singleElement().satisfies(event ->
+                assertThat((String) event.get("stack_trace")).contains("db-secret-host"));
+    }
+}
diff --git a/tooling/hardening/scenarios/phase-01-core-errors.sh b/tooling/hardening/scenarios/phase-01-core-errors.sh
index 7cae626..db65775 100644
--- a/tooling/hardening/scenarios/phase-01-core-errors.sh
+++ b/tooling/hardening/scenarios/phase-01-core-errors.sh
@@ -11,6 +11,14 @@ CHECKS=(python -I "$(hh_native "$HERE/phase-01/checks.py")")
 
 hex() { python -I -c "import secrets,sys; print(secrets.token_hex(int(sys.argv[1])))" "$1"; }
 tp() { printf '00-%s-%s-%s' "$1" "$(hex 8)" "${2:-01}"; }
+markers() { # number of rejected-traceparent markers the app has logged so far
+  docker logs "$HH_APP_CONTAINER" 2>&1 | grep -c "rejected inbound traceparent" || true
+}
+expect_marker_delta() { # expected-delta before-count label
+  local now; now=$(markers)
+  if [ $((now - $2)) -eq "$1" ]; then hh_pass "$3: exactly $1 rejection marker(s) for this request"
+  else hh_fail "$3: expected $1 rejection marker(s), got $((now - $2))"; fi
+}
 header_absent() { # name: the last response's headers must not contain it
   if grep -qi "^$1:" "$HH_EVIDENCE/http/$HH_LAST_HTTP.headers"; then hh_fail "$HH_LAST_HTTP: response carries $1"
   else hh_pass "$HH_LAST_HTTP: no $1 header"; fi
@@ -49,6 +57,7 @@ HH_STARTUP_HEALTHY=$HH_READY_SECONDS
 
 T=$(hex 16)
 hh_step "AC1 valid traceparent is continued; RequestContext, MDC and @Async carry it"
+M0=$(markers)
 hh_http ac1-valid GET /probe/context -H "traceparent: $(tp "$T")" -D "$HH_EVIDENCE/http/ac1-valid.headers"
 hh_expect_status 200
 hh_expect_json "context == inbound trace" "b['context'] == '$T' and b['mdcTraceId'] == '$T'"
@@ -57,6 +66,7 @@ hh_expect_json "@Async sees the trace" "b['async']['context'] == '$T' and b['asy
 hh_expect_json "@Async ran on another thread" "b['async']['thread'] != b['thread']"
 hh_expect_json "no correlationId MDC key" "'mdcCorrelationId' not in b"
 header_absent x-correlation-id
+expect_marker_delta 0 "$M0" ac1-valid
 
 hh_step "AC2 missing traceparent starts a new trace"
 hh_http ac2-missing GET /probe/context
@@ -70,13 +80,23 @@ AC2_ERROR_TRACE=$(python -I -c "import json,sys; print(json.load(open(sys.argv[1
 
 hh_step "AC3 malformed / oversized traceparent starts a new trace"
 BAD="00-zzzz-INJECTEDVALUE-01"
+M0=$(markers)
 hh_http ac3-malformed GET /probe/context -H "traceparent: $BAD"
 hh_expect_status 200
 hh_expect_json "new trace for malformed" "len(b['context']) == 32 and 'INJECTED' not in b['context']"
-OVERSIZE="00-$T-$(printf 'a%.0s' $(seq 1 300))-01"
+expect_marker_delta 1 "$M0" ac3-malformed
+OVERSIZE_TAIL=$(printf 'a%.0s' $(seq 1 300))
+OVERSIZE="00-$T-$OVERSIZE_TAIL-01"
+M0=$(markers)
 hh_http ac3-oversized GET /probe/context -H "traceparent: $OVERSIZE"
 hh_expect_status 200
 hh_expect_json "new trace for oversized" "len(b['context']) == 32 and b['context'] != '$T'"
+expect_marker_delta 1 "$M0" ac3-oversized
+ZERO="00-00000000000000000000000000000000-$(hex 8)-01"
+M0=$(markers)
+hh_http ac3-zero GET /probe/context -H "traceparent: $ZERO"
+hh_expect_json "new trace for an all-zero trace id" "len(b['context']) == 32 and b['context'] != '0' * 32"
+expect_marker_delta 1 "$M0" ac3-zero
 
 hh_step "AC4 a stray X-Correlation-Id is ignored"
 STRAY=$(hex 16)
@@ -131,6 +151,15 @@ hh_expect_status 500
 hh_expect_json "async failure body carries the trace" \
     "b['code'] == 'DC-CORE-0500' and b['traceId'] == '$T' and 'secret-host' not in json.dumps(b)"
 
+hh_step "async timeout (DeferredResult never completed) → 503, retryable, on the trace"
+hh_http async-timeout GET /probe/async-timeout -H "traceparent: $(tp "$T")"
+hh_expect_status 503
+hh_expect_json "503 contract" "b['traceId'] == '$T' and b['retryable'] is True and b['detail'] == 'An unexpected error occurred.'"
+
+hh_step "a direct request to the error path still has an instance"
+hh_http direct-error GET /error -H "traceparent: $(tp "$T")" -H "Authorization: Bearer $TOKEN"
+hh_expect_json "direct /error contract" "b['instance'] == '/error' and b['traceId'] == '$T'"
+
 hh_step "servlet error dispatch (filter sendError) is a problem body with the trace, for JSON and HTML clients"
 for ACCEPT in application/json text/html; do
   slug=$(printf '%s' "$ACCEPT" | tr -c 'a-z' '_')
@@ -198,13 +227,17 @@ else
 fi
 
 hh_step "logs: trace correlation, sanitized rejection marker, no raw hostile input"
-if "${CHECKS[@]}" logs "$(hh_native "$HH_EVIDENCE/app.log")" "$T" "INJECTEDVALUE" "$PII" > "$HH_EVIDENCE/logs-check.json"; then
+if "${CHECKS[@]}" logs "$(hh_native "$HH_EVIDENCE/app.log")" "$T" "INJECTEDVALUE" "$PII" "$OVERSIZE_TAIL" \
+    > "$HH_EVIDENCE/logs-check.json"; then
   hh_pass "logs $(cat "$HH_EVIDENCE/logs-check.json")"
 else
   hh_fail "logs $(cat "$HH_EVIDENCE/logs-check.json")"
 fi
 if grep -q "rejected inbound traceparent" "$HH_EVIDENCE/app.log"; then hh_pass "sanitized rejection marker logged"
 else hh_fail "no sanitized rejection marker in the log"; fi
+if [ "$(grep -F "framework exception [DC-CORE-0500] status 503" "$HH_EVIDENCE/app.log" | grep -cF "\"traceId\":\"$T\"")" -gt 0 ]; then
+  hh_pass "async timeout logged as a framework 5xx on the request's trace"
+else hh_fail "async timeout not logged with traceId $T"; fi
 for NEEDLE in db-secret-host pricing-7.internal; do
   # grep -c reads its whole input: a `| grep -q` would SIGPIPE the producer under pipefail on a big log.
   if [ "$(grep -F "$NEEDLE" "$HH_EVIDENCE/app.log" | grep -cF "\"traceId\":\"$T\"")" -gt 0 ]; then
diff --git a/tooling/hardening/scenarios/phase-01/probe/HardeningProbe.java b/tooling/hardening/scenarios/phase-01/probe/HardeningProbe.java
index 6f3cb51..e84514f 100644
--- a/tooling/hardening/scenarios/phase-01/probe/HardeningProbe.java
+++ b/tooling/hardening/scenarios/phase-01/probe/HardeningProbe.java
@@ -167,6 +167,11 @@ public final class HardeningProbe {
             });
         }
 
+        @GetMapping("/async-timeout")
+        public org.springframework.web.context.request.async.DeferredResult<String> asyncTimeout() {
+            return new org.springframework.web.context.request.async.DeferredResult<>(200L); // never completed
+        }
+
         @GetMapping("/boom")
         public String boom() {
             throw new IllegalStateException("cannot reach jdbc:oracle:thin:@db-secret-host:1521/PRD");
```
