# Verification pass 2 — follow-up (DC Platform hardening Phase 1)

Your verification pass 2 accepted R4.3/F12/AC16 and HI-0010 and rejected the regression pass on one item (verbatim below). State **accept** or **reject** (file:line) for that item only, and flag any regression this small diff introduces.

## Your verification pass 2 (verbatim)

**R4.3 / F12 / AC16 — ACCEPT under narrowed A3.**

- [CoreContextAutoConfiguration.java:54](C:/Users/Ali/AppData/Local/Temp/hd/codex-v2/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java:54) uses the correct filter-specific condition. Cached Boot bytecode confirms that it checks `CorrelationIdFilter` and its parameterized `FilterRegistrationBean`, rather than suppressing registration for unrelated filters.
- [PlatformBannerAutoConfiguration.java:38](C:/Users/Ali/AppData/Local/Temp/hd/codex-v2/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java:38) infers `PlatformBannerRunner` from the factory method’s return type. A consumer bean exposing that type under another name replaces it; unrelated `ApplicationRunner` beans do not.
- Descriptor name-only replacement is explicitly reconciled in [decision-log.md:639](C:/Users/Ali/AppData/Local/Temp/hd/codex-v2/docs/decisions/decision-log.md:639). The core descriptor remains behind the capability kill switch. I also verified the cited Boot health-indicator and `SslInfo` annotations in cached bytecode.

**HI-0010 — ACCEPT for the Phase 1 errors/security paths.**

[ErrorsProperties.java:31](C:/Users/Ali/AppData/Local/Temp/hd/codex-v2/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorsProperties.java:31) supplies the default used by the shared factory for exception, validation, MVC-framework, and servlet-error responses. [PlatformSecurityAutoConfiguration.java:61](C:/Users/Ali/AppData/Local/Temp/hd/codex-v2/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/PlatformSecurityAutoConfiguration.java:61) supplies the identical key/default to both security handlers.

Literal repository-wide uniformity remains false: [RateLimitFilter.java:72](C:/Users/Ali/AppData/Local/Temp/hd/codex-v2/ratelimit/platform-ratelimit-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/ratelimit/autoconfigure/internal/RateLimitFilter.java:72) emits `about:blank`, and `RateLimitExceptionAdvice.java:27` leaves Spring’s default type. These are pre-existing, outside this diff; they do not reopen D99.

**Regression pass — REJECT pending one unrecorded behavior change.**

[P2] [PlatformBannerRunner.java:40](C:/Users/Ali/AppData/Local/Temp/hd/codex-v2/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerRunner.java:40) changes the logger category from `PlatformBannerAutoConfiguration` to `PlatformBannerRunner`. Consumers configuring the former category—for suppression, routing, or enabling INFO—silently lose that configuration’s effect. Preserve the existing category, or explicitly accept and document this additional migration.

The requested compatibility checks otherwise show:

- **Bare consumer filter:** Boot preserves `FILTER_ORDER`; REQUEST/ASYNC/ERROR are retained. Boot maps `OncePerRequestFilter` to **all five** dispatch types, adding FORWARD/INCLUDE to the former registration’s three. Mapping does not guarantee another invocation during nested dispatches because once-per-request suppression still applies.
- **Legacy reserved names:** A correctly typed correlation registration or banner runner still works under its old name. A name-only replacement of another type can now cause a bean-definition collision with overriding disabled. The banner migration is recorded in [CHANGELOG.md:32](C:/Users/Ali/AppData/Local/Temp/hd/codex-v2/CHANGELOG.md:32).
- **Injected default filter:** An old registration factory taking `CorrelationIdFilter` as an argument must now provide that filter itself or construct it inline; the platform no longer supplies an injectable default filter bean.

This was source and cached-bytecode verification. I did not rerun tests or treat the pending consumer/golden-path runs as completed.

## Diff cf16aa8..9fc9067 (docs/production-readiness excluded)
```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index b1d2281..ed8418b 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -30,7 +30,13 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   consumer `CorrelationIdFilter` bean is registered by Boot (it is `Ordered` at `FILTER_ORDER` and runs on
   every dispatch type) instead of being wrapped by the platform registration. The banner is a public
   `PlatformBannerRunner`; an `ApplicationRunner` named `platformBannerRunner` no longer replaces it —
-  define a `PlatformBannerRunner` bean, or set `dc.platform.core.banner-enabled=false`.
+  define a `PlatformBannerRunner` bean, or set `dc.platform.core.banner-enabled=false`. The banner keeps
+  its `PlatformBannerAutoConfiguration` log category. A consumer registration whose factory method took
+  the platform's `CorrelationIdFilter` as a parameter must now create the filter itself (there is no
+  default filter bean to inject), and a bean that reused a reserved name with another type now collides
+  instead of silently replacing the platform bean. A bare consumer `CorrelationIdFilter` bean is mapped by
+  Boot to every dispatch type (FORWARD and INCLUDE too); `OncePerRequestFilter` still runs it once per
+  request.
 
 ### Added
 - Errors: `PlatformErrorController` — the servlet error page (`sendError`, exceptions escaping the
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerRunner.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerRunner.java
index 782e522..f142eeb 100644
--- a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerRunner.java
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerRunner.java
@@ -30,6 +30,9 @@ import org.springframework.boot.ApplicationRunner;
  * }
  * }</pre>
  *
+ * <p>Logs under the {@code ae.gov.dubaicustoms.platform.core.autoconfigure.PlatformBannerAutoConfiguration}
+ * category.
+ *
  * <p>Thread-safe; descriptors are read when {@link #run(ApplicationArguments)} is called. Constructor
  * arguments must not be {@code null}.
  *
@@ -37,7 +40,8 @@ import org.springframework.boot.ApplicationRunner;
  */
 public class PlatformBannerRunner implements ApplicationRunner {
 
-    private static final Logger log = LoggerFactory.getLogger(PlatformBannerRunner.class);
+    // Keeps the category the banner has always logged under, so consumer log configuration still applies.
+    private static final Logger log = LoggerFactory.getLogger(PlatformBannerAutoConfiguration.class);
 
     private final ObjectProvider<CapabilityDescriptor> descriptors;
 
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java
index ff01481..25a3f47 100644
--- a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java
@@ -81,6 +81,24 @@ class PlatformBannerAutoConfigurationTest {
                         .containsOnlyKeys("coreCapabilityDescriptor", "ordersCapabilityDescriptor"));
     }
 
+    @Test
+    void bannerKeepsItsLogCategory() {
+        // Consumers route or silence the banner by this category; moving the runner must not change it.
+        ch.qos.logback.classic.Logger category = (ch.qos.logback.classic.Logger)
+                org.slf4j.LoggerFactory.getLogger(PlatformBannerAutoConfiguration.class);
+        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
+                new ch.qos.logback.core.read.ListAppender<>();
+        appender.start();
+        category.addAppender(appender);
+        try {
+            runner.run(context -> context.getBean(PlatformBannerRunner.class).run(new DefaultApplicationArguments()));
+            assertThat(appender.list).singleElement()
+                    .satisfies(event -> assertThat(event.getFormattedMessage()).startsWith("platform: core[ACTIVE]"));
+        } finally {
+            category.detachAppender(appender);
+        }
+    }
+
     private static org.springframework.beans.factory.ObjectProvider<CapabilityDescriptor> emptyProvider() {
         return new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                 .getBeanProvider(CapabilityDescriptor.class);
```
