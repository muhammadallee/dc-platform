# Verification pass 2 — DC Platform hardening Phase 1 (owner decisions A1, A3, HI-0010)

You previously accepted F12's functional fix but held R4.3 open: plan §7.1 requires override **by type**, and the implementation overrode three beans by name only (`CapabilityDescriptor`, the banner `ApplicationRunner`, the correlation filter's `FilterRegistrationBean`). The repository owner reviewed this and decided:

1. **Single infrastructure beans override by type.** The correlation filter registration now follows Boot 4.1's own `webMvcObservationFilter` shape (`FilterRegistrationBean<…>` with the filter created inline, `@ConditionalOnMissingFilterBean`); `CorrelationIdFilter` is `Ordered` at `FILTER_ORDER` so a consumer filter bean that Boot registers keeps the position. The banner is a public `PlatformBannerRunner` backing off by type. (Decision D98, supersedes D96.)
2. **Capability descriptors stay name-based** (amendment A3, now narrowed to descriptors only). Precedent: Spring Boot 4.1.0 `spring-boot-health` bytecode — `DiskSpaceHealthContributorAutoConfiguration.diskSpaceHealthIndicator` is `@ConditionalOnMissingBean(name = "diskSpaceHealthIndicator")`, likewise `livenessStateHealthIndicator`, `readinessStateHealthIndicator`, `sslHealthIndicator`, while the single `SslInfo` bean backs off by type. The capability kill switch removes the descriptor. (D97.)
3. **A1 accepted:** removed properties carry no `replacement` metadata (no successor exists). (D100.)
4. **HI-0010:** problem `type` default changed from `https://errors.dc.com/` to the neutral `urn:dc:problem:` in both the errors capability and the security 401/403 path. (D99.)

## Your task

State **accept** or **reject** (with file:line reasons) for:
- **R4.3 / F12 / AC16** under the narrowed A3: are the filter registration and banner runner now genuinely type-overridable (a consumer bean under any name replaces the platform's; unrelated beans of the shared Spring types do not suppress it), and is the descriptor name-only rule now explicitly reconciled?
- **HI-0010**: is the default consistent everywhere a problem body is produced?
- **Regressions introduced by this diff**: behaviour changes for consumers who previously defined a `CorrelationIdFilter` bean or a bean named `platformCorrelationFilterRegistration` / `platformBannerRunner`; ordering or dispatch-type loss; anything a consumer would now get silently wrong.

Stay on these items; this is not a new review round. Evidence available to you: source and cached dependency bytecode in `~/.m2`. Targeted module tests (core 57, errors 58, security 20, 0 skipped) passed locally on the reviewed commit; the consumer harness and golden path are being re-run separately.

## Diff 685358d..cf16aa8 (docs/production-readiness excluded)

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index 36978e0..b1d2281 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -21,6 +21,16 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   `dc.platform.core.correlation.generate-if-missing`, `dc.platform.restclient.propagate-correlation`.
 - `CorrelationIdFilter`'s constructor takes a Micrometer `Tracer`; `RemoteCallException.remoteCorrelationId()`
   is deprecated (always empty for platform-created exceptions).
+- Problem `type` default is now `urn:dc:problem:<code>` (was `https://errors.dc.com/<code>`, a host the
+  platform does not own), for 401/403 bodies too. Clients matching on `type` must update, or set
+  `dc.platform.errors.type-base-uri` (D99).
+- Core overrides are by type (D98): the platform no longer defines a `correlationIdFilter` bean (the
+  filter is created inside `platformCorrelationFilterRegistration`), and backs off for any
+  `CorrelationIdFilter` bean or `FilterRegistrationBean<CorrelationIdFilter>` under any name. A
+  consumer `CorrelationIdFilter` bean is registered by Boot (it is `Ordered` at `FILTER_ORDER` and runs on
+  every dispatch type) instead of being wrapped by the platform registration. The banner is a public
+  `PlatformBannerRunner`; an `ApplicationRunner` named `platformBannerRunner` no longer replaces it —
+  define a `PlatformBannerRunner` bean, or set `dc.platform.core.banner-enabled=false`.
 
 ### Added
 - Errors: `PlatformErrorController` — the servlet error page (`sendError`, exceptions escaping the
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java
index 831a464..e88a0de 100644
--- a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java
@@ -6,7 +6,7 @@ import jakarta.servlet.DispatcherType;
 import org.springframework.beans.factory.ObjectProvider;
 import org.springframework.boot.autoconfigure.AutoConfiguration;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
-import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
+import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingFilterBean;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
 import org.springframework.boot.context.properties.EnableConfigurationProperties;
@@ -18,15 +18,17 @@ import org.springframework.core.Ordered;
 /*
  * Activates when: servlet web application AND platform-core-api on the classpath
  *                 AND dc.platform.core.enabled != false
- *                 (filter beans additionally: micrometer-tracing on the classpath)
- * Backs off when: user defines a CorrelationIdFilter bean (the registration then wraps theirs),
- *                 or a bean named platformCorrelationFilterRegistration (then the platform creates
- *                 NEITHER bean: a default filter left behind would be auto-registered by Boot)
- * Beans: correlationIdFilter — opens RequestContext with the current span's W3C trace ID (MDC
- *                              traceId/spanId) for the request and always closes it; never reads,
- *                              generates or echoes a correlation header;
- *        platformCorrelationFilterRegistration — registers the filter for all URLs at
- *                              FILTER_ORDER, on REQUEST, ASYNC and ERROR dispatches
+ *                 (registration additionally: micrometer-tracing on the classpath)
+ * Backs off when: user defines a CorrelationIdFilter bean or a FilterRegistrationBean<CorrelationIdFilter>
+ *                 (by type, Boot's @ConditionalOnMissingFilterBean — the same shape as Boot's own
+ *                 webMvcObservationFilter). A consumer's CorrelationIdFilter bean is then registered
+ *                 by Boot itself: it is Ordered at FILTER_ORDER and, as a OncePerRequestFilter, runs
+ *                 on every dispatch type, so it keeps the platform's position.
+ * Beans: platformCorrelationFilterRegistration — registers a CorrelationIdFilter (created inline,
+ *                              not a separate bean) for all URLs at FILTER_ORDER, on REQUEST, ASYNC
+ *                              and ERROR dispatches. The filter opens RequestContext with the current
+ *                              span's W3C trace ID (MDC traceId/spanId) for the request and always
+ *                              closes it; never reads, generates or echoes a correlation header.
  * Order: FILTER_ORDER = HIGHEST_PRECEDENCE + 10: after Boot's ServerHttpObservationFilter
  *        (HIGHEST_PRECEDENCE + 1), which continues or starts the trace from traceparent, and well
  *        before the Spring Security chain (-100), so 401/403 bodies and security log lines already
@@ -49,15 +51,10 @@ public class CoreContextAutoConfiguration {
     static class TraceCorrelationConfiguration {
 
         @Bean
-        @ConditionalOnMissingBean(value = CorrelationIdFilter.class, name = "platformCorrelationFilterRegistration")
-        CorrelationIdFilter correlationIdFilter(ObjectProvider<Tracer> tracer) {
-            return new CorrelationIdFilter(tracer.getIfAvailable(() -> Tracer.NOOP));
-        }
-
-        @Bean
-        @ConditionalOnMissingBean(name = "platformCorrelationFilterRegistration")
-        FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration(CorrelationIdFilter filter) {
-            FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
+        @ConditionalOnMissingFilterBean(CorrelationIdFilter.class)
+        FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration(ObjectProvider<Tracer> tracer) {
+            FilterRegistrationBean<CorrelationIdFilter> registration =
+                    new FilterRegistrationBean<>(new CorrelationIdFilter(tracer.getIfAvailable(() -> Tracer.NOOP)));
             registration.setOrder(FILTER_ORDER);
             registration.addUrlPatterns("/*");
             registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilter.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilter.java
index 4a5c64a..bc21e15 100644
--- a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilter.java
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilter.java
@@ -15,6 +15,7 @@ import java.util.Map;
 import java.util.Objects;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
+import org.springframework.core.Ordered;
 import org.springframework.web.filter.OncePerRequestFilter;
 import org.springframework.web.filter.ServerHttpObservationFilter;
 
@@ -37,14 +38,17 @@ import org.springframework.web.filter.ServerHttpObservationFilter;
  * observation it stored on the request.
  *
  * <p>Registered by {@code CoreContextAutoConfiguration} after the observation filter and before
- * the Spring Security filter chain; define your own {@code CorrelationIdFilter} bean to replace it.
+ * the Spring Security filter chain. To replace it, define your own {@code CorrelationIdFilter} bean
+ * (Boot registers it; it is {@link Ordered} at {@link CoreContextAutoConfiguration#FILTER_ORDER} and runs
+ * on every dispatch type) or a {@code FilterRegistrationBean<CorrelationIdFilter>} to control the
+ * registration yourself.
  *
  * <p>Thread-safe; the context it opens is confined to the dispatching thread and always closed.
  * Constructor arguments must not be {@code null}.
  *
  * @since 0.1.0
  */
-public class CorrelationIdFilter extends OncePerRequestFilter {
+public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {
 
     private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
 
@@ -65,6 +69,17 @@ public class CorrelationIdFilter extends OncePerRequestFilter {
         this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
     }
 
+    /**
+     * Returns {@link CoreContextAutoConfiguration#FILTER_ORDER}, so a consumer's own filter bean that Boot
+     * registers keeps the platform's position in the chain.
+     *
+     * @return the servlet filter order
+     */
+    @Override
+    public int getOrder() {
+        return CoreContextAutoConfiguration.FILTER_ORDER;
+    }
+
     @Override
     protected boolean shouldNotFilterAsyncDispatch() {
         return false;
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java
index 2487879..8e645bd 100644
--- a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java
@@ -1,12 +1,8 @@
 package ae.gov.dubaicustoms.platform.core.autoconfigure;
 
-import ae.gov.dubaicustoms.platform.core.autoconfigure.internal.PlatformBanner;
 import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
 import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
-import org.slf4j.Logger;
-import org.slf4j.LoggerFactory;
 import org.springframework.beans.factory.ObjectProvider;
-import org.springframework.boot.ApplicationRunner;
 import org.springframework.boot.autoconfigure.AutoConfiguration;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
@@ -16,7 +12,9 @@ import org.springframework.context.annotation.Bean;
 
 /*
  * Activates when: platform-core-api on the classpath AND dc.platform.core.enabled != false
- * Backs off when: user defines an ApplicationRunner bean named platformBannerRunner
+ * Backs off when: user defines a PlatformBannerRunner bean (by type); user defines a bean named
+ *                 coreCapabilityDescriptor (by name — CapabilityDescriptor is a one-per-capability
+ *                 type, overridden like Boot's health indicators, D93/D97)
  * Beans: coreCapabilityDescriptor — reports core[ACTIVE] into the banner (present even when the
  *                                   banner itself is switched off, for other report consumers);
  *        platformBannerRunner — logs one INFO line listing all active capabilities, sorted by
@@ -30,9 +28,6 @@ import org.springframework.context.annotation.Bean;
 @EnableConfigurationProperties(CoreProperties.class)
 public class PlatformBannerAutoConfiguration {
 
-    private static final Logger log = LoggerFactory.getLogger(PlatformBannerAutoConfiguration.class);
-
-    // CapabilityDescriptor is a shared type (one bean per capability): overridden by name (D93).
     @Bean
     @ConditionalOnMissingBean(name = "coreCapabilityDescriptor")
     CapabilityDescriptor coreCapabilityDescriptor() {
@@ -40,10 +35,10 @@ public class PlatformBannerAutoConfiguration {
     }
 
     @Bean
-    @ConditionalOnMissingBean(name = "platformBannerRunner")
+    @ConditionalOnMissingBean
     @ConditionalOnProperty(prefix = "dc.platform.core", name = "banner-enabled",
                            havingValue = "true", matchIfMissing = true)
-    ApplicationRunner platformBannerRunner(ObjectProvider<CapabilityDescriptor> descriptors) {
-        return args -> log.info("{}", PlatformBanner.format(descriptors.orderedStream().toList()));
+    PlatformBannerRunner platformBannerRunner(ObjectProvider<CapabilityDescriptor> descriptors) {
+        return new PlatformBannerRunner(descriptors);
     }
 }
diff --git a/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerRunner.java b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerRunner.java
new file mode 100644
index 0000000..782e522
--- /dev/null
+++ b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerRunner.java
@@ -0,0 +1,67 @@
+package ae.gov.dubaicustoms.platform.core.autoconfigure;
+
+import ae.gov.dubaicustoms.platform.core.autoconfigure.internal.PlatformBanner;
+import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
+import java.util.List;
+import java.util.Objects;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.beans.factory.ObjectProvider;
+import org.springframework.boot.ApplicationArguments;
+import org.springframework.boot.ApplicationRunner;
+
+/**
+ * Logs the platform startup banner: one INFO line listing every {@link CapabilityDescriptor} bean,
+ * sorted by capability name, once the application context is ready.
+ *
+ * <p>Registered by {@code PlatformBannerAutoConfiguration}, which backs off when the application
+ * defines its own {@code PlatformBannerRunner} bean (by type). Subclass it and override
+ * {@link #describe(List)} to change the line, or set {@code dc.platform.core.banner-enabled=false}
+ * and register an unrelated {@link ApplicationRunner} to replace the banner entirely:
+ * <pre>{@code
+ * @Bean
+ * PlatformBannerRunner platformBannerRunner(ObjectProvider<CapabilityDescriptor> descriptors) {
+ *     return new PlatformBannerRunner(descriptors) {
+ *         @Override
+ *         protected String describe(List<CapabilityDescriptor> active) {
+ *             return "my-service " + super.describe(active);
+ *         }
+ *     };
+ * }
+ * }</pre>
+ *
+ * <p>Thread-safe; descriptors are read when {@link #run(ApplicationArguments)} is called. Constructor
+ * arguments must not be {@code null}.
+ *
+ * @since 0.1.0
+ */
+public class PlatformBannerRunner implements ApplicationRunner {
+
+    private static final Logger log = LoggerFactory.getLogger(PlatformBannerRunner.class);
+
+    private final ObjectProvider<CapabilityDescriptor> descriptors;
+
+    /**
+     * Creates the runner.
+     *
+     * @param descriptors the capability descriptors to report; never {@code null}
+     */
+    public PlatformBannerRunner(ObjectProvider<CapabilityDescriptor> descriptors) {
+        this.descriptors = Objects.requireNonNull(descriptors, "descriptors must not be null");
+    }
+
+    @Override
+    public void run(ApplicationArguments args) {
+        log.info("{}", describe(descriptors.orderedStream().toList()));
+    }
+
+    /**
+     * Formats the banner line.
+     *
+     * @param active the capability descriptors present in the context; never {@code null}
+     * @return the line to log, for example {@code platform: core[ACTIVE], errors[ACTIVE]}
+     */
+    protected String describe(List<CapabilityDescriptor> active) {
+        return PlatformBanner.format(active);
+    }
+}
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java
index a75ddc2..7b196ef 100644
--- a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfigurationTest.java
@@ -4,6 +4,13 @@ import static org.assertj.core.api.Assertions.assertThat;
 
 import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
 import io.micrometer.tracing.Tracer;
+import jakarta.servlet.FilterChain;
+import jakarta.servlet.ServletException;
+import jakarta.servlet.ServletRequest;
+import jakarta.servlet.ServletResponse;
+import java.io.IOException;
+import java.util.ArrayList;
+import java.util.List;
 import java.util.Map;
 import org.junit.jupiter.api.Test;
 import org.springframework.boot.autoconfigure.AutoConfigurations;
@@ -11,11 +18,19 @@ import org.springframework.boot.test.context.FilteredClassLoader;
 import org.springframework.boot.test.context.runner.ApplicationContextRunner;
 import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
 import org.springframework.boot.web.servlet.FilterRegistrationBean;
+import org.springframework.boot.web.servlet.RegistrationBean;
+import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
+import org.springframework.context.ApplicationContext;
+import org.springframework.context.ConfigurableApplicationContext;
+import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Configuration;
 import org.springframework.core.Ordered;
+import org.springframework.web.filter.GenericFilterBean;
 
 /**
  * The mandatory 5-case ContextRunner matrix for CoreContextAutoConfiguration, plus the ordering
- * contract, the real-override contract (plan §7.1) and the config-default snapshot (plan §7.2).
+ * contract, the real-override contract by type (plan §7.1, D98) and the config-default snapshot
+ * (plan §7.2).
  */
 class CoreContextAutoConfigurationTest {
 
@@ -25,8 +40,8 @@ class CoreContextAutoConfigurationTest {
     @Test
     void activeByDefault() {
         runner.run(context -> {
-            assertThat(context).hasSingleBean(CorrelationIdFilter.class);
             assertThat(context).hasBean("platformCorrelationFilterRegistration");
+            assertThat(registeredCorrelationFilters(context)).hasSize(1);
         });
     }
 
@@ -34,44 +49,43 @@ class CoreContextAutoConfigurationTest {
     void killSwitchDisables() {
         runner.withPropertyValues("dc.platform.core.enabled=false")
                 .run(context -> {
-                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
+                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
+                    assertThat(registeredCorrelationFilters(context)).isEmpty();
+                });
+    }
+
+    @Test
+    void backsOffWhenUserFilterPresentUnderAnyName() {
+        runner.withUserConfiguration(UserFilter.class)
+                .run(context -> {
                     assertThat(context).doesNotHaveBean("platformCorrelationFilterRegistration");
+                    // Boot registers the consumer's filter itself, at the platform's position.
+                    CorrelationIdFilter mine = context.getBean("myCorrelationFilter", CorrelationIdFilter.class);
+                    List<RegistrationBean> registrations = correlationRegistrations(context);
+                    assertThat(registrations).hasSize(1);
+                    assertThat(((FilterRegistrationBean<?>) registrations.get(0)).getFilter()).isSameAs(mine);
+                    assertThat(registrations.get(0).getOrder()).isEqualTo(CoreContextAutoConfiguration.FILTER_ORDER);
                 });
     }
 
     @Test
-    void backsOffWhenUserFilterPresent() {
-        CorrelationIdFilter mine = new CorrelationIdFilter(Tracer.NOOP);
-        runner.withBean("mine", CorrelationIdFilter.class, () -> mine)
+    void backsOffWhenUserRegistrationPresentUnderAnyName() {
+        runner.withUserConfiguration(UserRegistration.class)
                 .run(context -> {
-                    assertThat(context).getBean(CorrelationIdFilter.class).isSameAs(mine);
-                    // The platform registration wraps the consumer's filter instead of a second one.
-                    assertThat(context.getBean("platformCorrelationFilterRegistration", FilterRegistrationBean.class)
-                            .getFilter()).isSameAs(mine);
+                    assertThat(context).doesNotHaveBean("platformCorrelationFilterRegistration");
+                    // The consumer's (disabled) registration is the only one: no second platform filter.
+                    assertThat(registeredCorrelationFilters(context))
+                            .containsExactly(context.getBean("myRegistration", FilterRegistrationBean.class).getFilter());
                 });
     }
 
     @Test
-    void backsOffWhenUserRegistrationPresent() {
-        CorrelationIdFilter theirs = new CorrelationIdFilter(Tracer.NOOP);
-        FilterRegistrationBean<CorrelationIdFilter> mine = new FilterRegistrationBean<>(theirs);
-        mine.setEnabled(false); // a consumer may even switch the filter off
-        runner.withBean("platformCorrelationFilterRegistration", FilterRegistrationBean.class, () -> mine)
+    void unrelatedFilterRegistrationsDoNotSuppressThePlatformOne() {
+        runner.withUserConfiguration(UnrelatedRegistration.class)
                 .run(context -> {
-                    assertThat(context.getBean("platformCorrelationFilterRegistration")).isSameAs(mine);
-                    assertThat(context).doesNotHaveBean("correlationIdFilter");
-                    // What Boot would register with the servlet container: no second, auto-registered
-                    // CorrelationIdFilter next to the consumer's (disabled) registration.
-                    var initializers = new org.springframework.boot.web.servlet.ServletContextInitializerBeans(
-                            context.getBeanFactory());
-                    java.util.List<Object> correlationFilters = new java.util.ArrayList<>();
-                    initializers.forEach(initializer -> {
-                        if (initializer instanceof FilterRegistrationBean<?> registration
-                                && registration.getFilter() instanceof CorrelationIdFilter filter) {
-                            correlationFilters.add(filter);
-                        }
-                    });
-                    assertThat(correlationFilters).containsExactly(theirs);
+                    assertThat(context).hasBean("platformCorrelationFilterRegistration");
+                    assertThat(context).hasBean("unrelatedRegistration");
+                    assertThat(registeredCorrelationFilters(context)).hasSize(1);
                 });
     }
 
@@ -86,7 +100,7 @@ class CoreContextAutoConfigurationTest {
         runner.withClassLoader(new FilteredClassLoader(Tracer.class))
                 .run(context -> {
                     assertThat(context).hasNotFailed();
-                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
+                    assertThat(context).doesNotHaveBean("platformCorrelationFilterRegistration");
                 });
     }
 
@@ -94,7 +108,7 @@ class CoreContextAutoConfigurationTest {
     void inactiveOutsideWebApplications() {
         new ApplicationContextRunner()
                 .withConfiguration(AutoConfigurations.of(CoreContextAutoConfiguration.class))
-                .run(context -> assertThat(context).doesNotHaveBean(CorrelationIdFilter.class));
+                .run(context -> assertThat(context).doesNotHaveBean("platformCorrelationFilterRegistration"));
     }
 
     @Test
@@ -108,6 +122,7 @@ class CoreContextAutoConfigurationTest {
                     .isEqualTo(CoreContextAutoConfiguration.FILTER_ORDER)
                     .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1)
                     .isLessThan(-100);
+            assertThat(new CorrelationIdFilter(Tracer.NOOP).getOrder()).isEqualTo(registration.getOrder());
         });
     }
 
@@ -117,7 +132,7 @@ class CoreContextAutoConfigurationTest {
                         "dc.platform.core.correlation.generate-if-missing=false")
                 .run(context -> {
                     assertThat(context).hasNotFailed();
-                    assertThat(context).hasSingleBean(CorrelationIdFilter.class);
+                    assertThat(context).hasBean("platformCorrelationFilterRegistration");
                 });
     }
 
@@ -131,4 +146,58 @@ class CoreContextAutoConfigurationTest {
                     .isEqualTo(Map.of("enabled", true, "banner-enabled", true));
         });
     }
+
+    /** What Boot would register with the servlet container for CorrelationIdFilter. */
+    private static List<RegistrationBean> correlationRegistrations(ApplicationContext context) {
+        List<RegistrationBean> found = new ArrayList<>();
+        new ServletContextInitializerBeans(((ConfigurableApplicationContext) context).getBeanFactory())
+                .forEach(initializer -> {
+                    if (initializer instanceof FilterRegistrationBean<?> registration
+                            && registration.getFilter() instanceof CorrelationIdFilter) {
+                        found.add(registration);
+                    }
+                });
+        return found;
+    }
+
+    private static List<Object> registeredCorrelationFilters(ApplicationContext context) {
+        return correlationRegistrations(context).stream()
+                .map(registration -> (Object) ((FilterRegistrationBean<?>) registration).getFilter())
+                .toList();
+    }
+
+    @Configuration(proxyBeanMethods = false)
+    static class UserFilter {
+
+        @Bean
+        CorrelationIdFilter myCorrelationFilter() {
+            return new CorrelationIdFilter(Tracer.NOOP);
+        }
+    }
+
+    @Configuration(proxyBeanMethods = false)
+    static class UserRegistration {
+
+        @Bean
+        FilterRegistrationBean<CorrelationIdFilter> myRegistration() {
+            FilterRegistrationBean<CorrelationIdFilter> mine = new FilterRegistrationBean<>(new CorrelationIdFilter(Tracer.NOOP));
+            mine.setEnabled(false); // a consumer may even switch the filter off
+            return mine;
+        }
+    }
+
+    @Configuration(proxyBeanMethods = false)
+    static class UnrelatedRegistration {
+
+        @Bean
+        FilterRegistrationBean<GenericFilterBean> unrelatedRegistration() {
+            return new FilterRegistrationBean<>(new GenericFilterBean() {
+                @Override
+                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
+                        throws IOException, ServletException {
+                    chain.doFilter(request, response);
+                }
+            });
+        }
+    }
 }
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java
index 45b6118..ff01481 100644
--- a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfigurationTest.java
@@ -24,7 +24,7 @@ class PlatformBannerAutoConfigurationTest {
     @Test
     void activeByDefault() {
         runner.run(context -> {
-            assertThat(context).hasBean("platformBannerRunner");
+            assertThat(context).hasSingleBean(PlatformBannerRunner.class);
             assertThat(context).hasSingleBean(CapabilityDescriptor.class);
         });
     }
@@ -49,10 +49,41 @@ class PlatformBannerAutoConfigurationTest {
     }
 
     @Test
-    void backsOffWhenUserRunnerPresent() {
-        ApplicationRunner mine = args -> { };
-        runner.withBean("platformBannerRunner", ApplicationRunner.class, () -> mine)
-                .run(context -> assertThat(context).getBean("platformBannerRunner").isSameAs(mine));
+    void backsOffWhenUserRunnerPresentUnderAnyName() {
+        runner.withBean("myBanner", PlatformBannerRunner.class,
+                        () -> new PlatformBannerRunner(emptyProvider()) {
+                            @Override
+                            protected String describe(java.util.List<CapabilityDescriptor> active) {
+                                return "mine";
+                            }
+                        })
+                .run(context -> {
+                    assertThat(context).hasSingleBean(PlatformBannerRunner.class);
+                    assertThat(context).hasBean("myBanner").doesNotHaveBean("platformBannerRunner");
+                });
+    }
+
+    @Test
+    void unrelatedApplicationRunnersDoNotSuppressTheBanner() {
+        ApplicationRunner unrelated = args -> { };
+        runner.withBean("platformBannerRunner2", ApplicationRunner.class, () -> unrelated)
+                .run(context -> {
+                    assertThat(context).hasSingleBean(PlatformBannerRunner.class);
+                    assertThat(context.getBeansOfType(ApplicationRunner.class)).hasSize(2);
+                });
+    }
+
+    @Test
+    void unrelatedDescriptorsDoNotSuppressTheCoreOne() {
+        runner.withBean("ordersCapabilityDescriptor", CapabilityDescriptor.class,
+                        () -> new CapabilityDescriptor("orders", "ACTIVE", ""))
+                .run(context -> assertThat(context.getBeansOfType(CapabilityDescriptor.class))
+                        .containsOnlyKeys("coreCapabilityDescriptor", "ordersCapabilityDescriptor"));
+    }
+
+    private static org.springframework.beans.factory.ObjectProvider<CapabilityDescriptor> emptyProvider() {
+        return new org.springframework.beans.factory.support.DefaultListableBeanFactory()
+                .getBeanProvider(CapabilityDescriptor.class);
     }
 
     @Test
@@ -68,8 +99,7 @@ class PlatformBannerAutoConfigurationTest {
                 .withBean("alphaCapabilityDescriptor", CapabilityDescriptor.class,
                         () -> new CapabilityDescriptor("alpha", "ACTIVE", ""))
                 .run(context -> {
-                    context.getBean("platformBannerRunner", ApplicationRunner.class)
-                            .run(new DefaultApplicationArguments());
+                    context.getBean(PlatformBannerRunner.class).run(new DefaultApplicationArguments());
                     assertThat(output.getOut())
                             .contains("platform: alpha[ACTIVE], core[ACTIVE], zeta[ACTIVE] (inmemory)");
                 });
diff --git a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/RegistrationOverrideContainerTest.java b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/RegistrationOverrideContainerTest.java
index 6f99adc..8197279 100644
--- a/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/RegistrationOverrideContainerTest.java
+++ b/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/RegistrationOverrideContainerTest.java
@@ -3,46 +3,115 @@ package ae.gov.dubaicustoms.platform.core.autoconfigure;
 import static org.assertj.core.api.Assertions.assertThat;
 
 import io.micrometer.tracing.Tracer;
+import jakarta.servlet.DispatcherType;
+import jakarta.servlet.FilterChain;
 import jakarta.servlet.FilterRegistration;
 import jakarta.servlet.ServletContext;
+import jakarta.servlet.ServletException;
+import jakarta.servlet.http.HttpServletRequest;
+import jakarta.servlet.http.HttpServletResponse;
+import java.io.IOException;
+import java.net.URI;
+import java.net.http.HttpClient;
+import java.net.http.HttpRequest;
+import java.net.http.HttpResponse;
 import java.util.Map;
+import java.util.Set;
+import java.util.concurrent.Callable;
+import java.util.concurrent.ConcurrentHashMap;
 import org.junit.jupiter.api.Test;
 import org.springframework.boot.SpringApplication;
 import org.springframework.boot.SpringBootConfiguration;
 import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
+import org.springframework.boot.web.server.context.WebServerApplicationContext;
 import org.springframework.boot.web.servlet.FilterRegistrationBean;
 import org.springframework.context.ConfigurableApplicationContext;
 import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Import;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.RestController;
 import org.springframework.web.context.WebApplicationContext;
 
 /**
- * Phase-01 AC16 in a STARTED servlet container: a consumer's own (here: disabled) registration named
- * {@code platformCorrelationFilterRegistration} is the only thing registered — the container holds no
- * CorrelationIdFilter at all, not a second auto-registered platform one.
+ * Phase-01 AC16 in a STARTED servlet container (D98): a consumer's own correlation filter or filter
+ * registration — under any bean name — replaces the platform's. With a consumer registration (here:
+ * disabled) the container holds no CorrelationIdFilter at all, not a second platform one; with a
+ * consumer filter bean, Boot registers that filter and it still runs on REQUEST, ASYNC and ERROR
+ * dispatches.
  */
 class RegistrationOverrideContainerTest {
 
+    private static final Set<DispatcherType> SEEN = ConcurrentHashMap.newKeySet();
+
     @SpringBootConfiguration
     @EnableAutoConfiguration
-    static class App {
+    static class RegistrationApp {
 
         @Bean
-        FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration() {
+        FilterRegistrationBean<CorrelationIdFilter> myCorrelationRegistration() {
             FilterRegistrationBean<CorrelationIdFilter> mine = new FilterRegistrationBean<>(new CorrelationIdFilter(Tracer.NOOP));
             mine.setEnabled(false);
             return mine;
         }
     }
 
+    @SpringBootConfiguration
+    @EnableAutoConfiguration
+    @Import(DispatchController.class)
+    static class FilterApp {
+
+        @Bean
+        CorrelationIdFilter myCorrelationFilter() {
+            return new CorrelationIdFilter(Tracer.NOOP) {
+                @Override
+                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
+                        FilterChain chain) throws ServletException, IOException {
+                    SEEN.add(request.getDispatcherType());
+                    super.doFilterInternal(request, response, chain);
+                }
+            };
+        }
+    }
+
+    @RestController
+    static class DispatchController {
+
+        @GetMapping("/callable")
+        Callable<String> callable() {
+            return () -> "ok";
+        }
+
+        @GetMapping("/conflict")
+        void conflict(HttpServletResponse response) throws IOException {
+            response.sendError(409);
+        }
+    }
+
     @Test
-    void theContainerHoldsNoPlatformCorrelationFilter() {
-        try (ConfigurableApplicationContext context = new SpringApplication(App.class)
+    void aConsumerRegistrationLeavesNoPlatformFilterInTheContainer() {
+        try (ConfigurableApplicationContext context = new SpringApplication(RegistrationApp.class)
                 .run("--server.port=0", "--spring.main.banner-mode=off")) {
             ServletContext servletContext = ((WebApplicationContext) context).getServletContext();
             Map<String, ? extends FilterRegistration> registrations = servletContext.getFilterRegistrations();
             assertThat(registrations.values())
                     .noneMatch(registration -> CorrelationIdFilter.class.getName().equals(registration.getClassName()));
-            assertThat(context.getBeansOfType(CorrelationIdFilter.class)).isEmpty();
+            assertThat(context.containsBean("platformCorrelationFilterRegistration")).isFalse();
+        }
+    }
+
+    @Test
+    void aConsumerFilterBeanRunsOnEveryDispatchInsteadOfThePlatformOne() throws Exception {
+        SEEN.clear();
+        try (ConfigurableApplicationContext context = new SpringApplication(FilterApp.class)
+                .run("--server.port=0", "--spring.main.banner-mode=off")) {
+            assertThat(context.containsBean("platformCorrelationFilterRegistration")).isFalse();
+            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
+            HttpClient http = HttpClient.newHttpClient();
+            for (String path : new String[] {"/callable", "/conflict"}) {
+                http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
+                        HttpResponse.BodyHandlers.discarding());
+            }
+            assertThat(SEEN).contains(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
         }
     }
 }
diff --git a/docs/book/chapters/01-core.md b/docs/book/chapters/01-core.md
index 269a76a..18a7891 100644
--- a/docs/book/chapters/01-core.md
+++ b/docs/book/chapters/01-core.md
@@ -277,8 +277,8 @@ Full generated list: [reference/properties.md](../../reference/properties.md).
 
 | Class | Activates when | Contributes | Backs off when |
 |---|---|---|---|
-| `CoreContextAutoConfiguration` | Servlet web app **and** `CorrelationId` on classpath **and** `core.enabled != false` | `correlationIdFilter`, `platformCorrelationFilterRegistration` | You define a `CorrelationIdFilter` bean (the platform registration then wraps *yours*), or a `FilterRegistrationBean` named `platformCorrelationFilterRegistration` |
-| `PlatformBannerAutoConfiguration` | `CorrelationId` on classpath **and** `core.enabled != false` | `coreCapabilityDescriptor`, `platformBannerRunner` | You define an `ApplicationRunner` bean named `platformBannerRunner`; the runner is additionally gated on `banner-enabled` |
+| `CoreContextAutoConfiguration` | Servlet web app **and** `CorrelationId` on classpath **and** `core.enabled != false` | `platformCorrelationFilterRegistration` | You define a `CorrelationIdFilter` bean or a `FilterRegistrationBean<CorrelationIdFilter>`, under any name (Boot then registers yours) |
+| `PlatformBannerAutoConfiguration` | `CorrelationId` on classpath **and** `core.enabled != false` | `coreCapabilityDescriptor`, `platformBannerRunner` | You define a `PlatformBannerRunner` bean (any name), or a bean named `coreCapabilityDescriptor`; the runner is additionally gated on `banner-enabled` |
 
 !!! note "The descriptor is contributed even when the banner is off"
     `coreCapabilityDescriptor` is not gated on `banner-enabled` — only the runner is. That way
@@ -295,9 +295,9 @@ Full generated list: [reference/properties.md](../../reference/properties.md).
 | Extension | How | Effect |
 |---|---|---|
 | Contribute a capability to the report | Declare a `CapabilityDescriptor` bean | Appears in the banner and `/actuator/platform` |
-| Replace the filter's behaviour | Declare a `CorrelationIdFilter` bean | The platform's registration wraps yours, at highest precedence |
-| Take over registration entirely | Declare a `FilterRegistrationBean` named `platformCorrelationFilterRegistration` | You control order and URL patterns |
-| Replace the banner | Declare an `ApplicationRunner` named `platformBannerRunner` | Your runner logs instead |
+| Replace the filter's behaviour | Declare a `CorrelationIdFilter` bean (any name) | Boot registers yours at the platform's order, on every dispatch type |
+| Take over registration entirely | Declare a `FilterRegistrationBean<CorrelationIdFilter>` (any name) | You control order and URL patterns |
+| Replace the banner | Declare a `PlatformBannerRunner` bean (any name) | Your runner logs instead |
 | Extend the exception taxonomy | Subclass `PlatformException` with your own `ErrorCode` | Rendered as a problem response by [errors](02-errors-validation.md) |
 
 ---
@@ -638,14 +638,15 @@ You *can* still end up ahead of it:
 If you must run something before correlation — an APM agent is the usual legitimate case — be aware
 that its log lines will not carry an id, and check whether it can read the header itself.
 
-To replace the filter but keep its precedence, declare a `CorrelationIdFilter` bean; the platform's
-registration wraps yours. To control precedence too, declare the `FilterRegistrationBean` by name:
+To replace the filter but keep its precedence, declare a `CorrelationIdFilter` bean; Boot registers it
+at the platform's order. To control precedence too, declare a `FilterRegistrationBean<CorrelationIdFilter>`
+under any name:
 
 ```java
 @Bean
-FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration(CorrelationIdFilter filter) {
-    FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
-    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
+FilterRegistrationBean<CorrelationIdFilter> correlationRegistration(Tracer tracer) {
+    FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter(tracer));
+    registration.setOrder(CoreContextAutoConfiguration.FILTER_ORDER);
     registration.addUrlPatterns("/api/*");
     return registration;
 }
diff --git a/docs/book/chapters/02-errors-validation.md b/docs/book/chapters/02-errors-validation.md
index 79bfc52..731b4f3 100644
--- a/docs/book/chapters/02-errors-validation.md
+++ b/docs/book/chapters/02-errors-validation.md
@@ -48,7 +48,7 @@ media type, five standard members, and an open extension mechanism:
 
 ```json
 {
-  "type": "https://errors.dc.com/DC-ORDER-0404",
+  "type": "urn:dc:problem:DC-ORDER-0404",
   "title": "DC-ORDER-0404",
   "status": 404,
   "detail": "order 8812 not found",
@@ -308,7 +308,7 @@ all four support `groups()` and `payload()` like any standard constraint.
 
 | Member | Source | Notes |
 |---|---|---|
-| `type` | `type-base-uri` + code | e.g. `https://errors.dc.com/DC-ORDER-0404` |
+| `type` | `type-base-uri` + code | e.g. `urn:dc:problem:DC-ORDER-0404` |
 | `title` | The error code | Deliberately the code, not prose — prose is not stable |
 | `status` | `HttpStatusHint`, or 500 | |
 | `detail` | The exception message | **Only for mapped exceptions.** Unmapped ones get a fixed generic string |
@@ -326,7 +326,7 @@ Full generated list: [reference/properties.md](../../reference/properties.md).
 | Key | Type | Default | Meaning | When you would change it |
 |---|---|---|---|---|
 | `dc.platform.errors.enabled` | Boolean | `true` | Kill switch | During an incident caused by a customizer, to fall back to Spring's defaults |
-| `dc.platform.errors.type-base-uri` | String | `https://errors.dc.com/` | Prefix for the problem `type` URI | When your organisation publishes error documentation at another host |
+| `dc.platform.errors.type-base-uri` | String | `urn:dc:problem:` | Prefix for the problem `type` URI | When your organisation publishes error documentation at another host |
 | `dc.platform.errors.map-validation` | Boolean | `true` | Map validation failures to 400 with `errors[]` | When a legacy client depends on Spring's default validation response shape |
 | `dc.platform.errors.include-stacktrace` | Boolean | `false` | Add a `stacktrace` extension to problem bodies | **Local debugging only.** See the warning below |
 | `dc.platform.validation.enabled` | Boolean | `true` | Kill switch | Essentially never |
@@ -443,7 +443,7 @@ HTTP/1.1 404
 Content-Type: application/problem+json
 X-Correlation-Id: 9f2c7a1e4b8d40f6a3c5e7d9b1f3a5c7
 
-{"type":"https://errors.dc.com/DC-ORDER-0404","title":"DC-ORDER-0404","status":404,
+{"type":"urn:dc:problem:DC-ORDER-0404","title":"DC-ORDER-0404","status":404,
  "detail":"order 8812 not found","instance":"/orders/8812","code":"DC-ORDER-0404",
  "correlationId":"9f2c7a1e4b8d40f6a3c5e7d9b1f3a5c7","timestamp":"2026-08-04T10:15:30.123Z"}
 ```
@@ -476,7 +476,7 @@ A bad request produces a 400 with per-field detail:
 
 ```json
 {
-  "type": "https://errors.dc.com/DC-CORE-0400",
+  "type": "urn:dc:problem:DC-CORE-0400",
   "title": "DC-CORE-0400",
   "status": 400,
   "detail": "Validation failed.",
@@ -948,7 +948,7 @@ the literal secret you posted — checked against the whole serialised body, not
 | Key | Default |
 |---|---|
 | `dc.platform.errors.enabled` | `true` |
-| `dc.platform.errors.type-base-uri` | `https://errors.dc.com/` |
+| `dc.platform.errors.type-base-uri` | `urn:dc:problem:` |
 | `dc.platform.errors.map-validation` | `true` |
 | `dc.platform.errors.include-stacktrace` | `false` — **keep it that way outside `local`** |
 | `dc.platform.validation.enabled` | `true` |
diff --git a/docs/book/crosscutting/extending.md b/docs/book/crosscutting/extending.md
index ce103f2..e6749b6 100644
--- a/docs/book/crosscutting/extending.md
+++ b/docs/book/crosscutting/extending.md
@@ -141,9 +141,10 @@ Commonly replaced, low risk:
     format. Plan it as a coordinated migration: dual-publish, migrate consumers, then switch.
 
 !!! warning "Name-based back-off is a foot-gun worth knowing about"
-    Several beans back off by **name**, not type: `platformCorrelationFilterRegistration`,
-    `platformBannerRunner`, `platformProblemDetailOpenApiCustomizer`, `platformCommonTagsCustomizer`,
-    `platformExceptionHandler`.
+    Several beans back off by **name**, not type: the per-capability `<cap>CapabilityDescriptor`
+    beans (by design, like Boot's health indicators), `platformProblemDetailOpenApiCustomizer`,
+    `platformCommonTagsCustomizer`, and — besides their type — `platformExceptionHandler` and
+    `platformValidationExceptionHandler`. The correlation filter and the banner back off by type.
 
     A bean that happens to share one of those names replaces platform behaviour silently. Conversely, a
     customizer you *meant* to run alongside the platform's must use a different name.
diff --git a/docs/decisions/decision-log.md b/docs/decisions/decision-log.md
index 6f7421c..73bfe14 100644
--- a/docs/decisions/decision-log.md
+++ b/docs/decisions/decision-log.md
@@ -632,6 +632,32 @@ One line of context per decision; details live in the commit bodies referenced.
 - **D95 — trace rejection judged by the propagator's outcome.** The core filter does not re-implement
   W3C validation: a present `traceparent` counts as rejected exactly when the current trace is not the
   one it names, and the marker carries the header length only.
-- **D96 — shared-type infrastructure beans override by name.** The banner `ApplicationRunner` and the
-  correlation filter's `FilterRegistrationBean` back off by name only: consumers routinely define other
-  beans of those Spring types, and type-based back-off would silently drop the platform's.
+- **D96 — shared-type infrastructure beans override by name.** *Superseded by D98.* The banner
+  `ApplicationRunner` and the correlation filter's `FilterRegistrationBean` backed off by name only:
+  consumers routinely define other beans of those Spring types, and type-based back-off would silently
+  drop the platform's.
+- **D97 — capability descriptors override by name, like Boot's health indicators (hardening A3).**
+  `CapabilityDescriptor` is collected one-bean-per-capability, so type-based back-off would let any
+  consumer descriptor remove every platform one. Spring Boot 4.1 makes the same choice for its collected
+  health indicators (`@ConditionalOnMissingBean(name = "diskSpaceHealthIndicator")`, likewise
+  `livenessStateHealthIndicator`, `readinessStateHealthIndicator`, `sslHealthIndicator`) while its
+  single beans (`SslInfo`) back off by type. The capability kill switch `dc.platform.<cap>.enabled`
+  removes the descriptor with the capability. Each remaining capability's descriptor gets the same
+  guard and a real-override test in its own hardening phase.
+- **D98 — single infrastructure beans override by type (supersedes D96).** The correlation filter is
+  registered the way Boot registers its own `ServerHttpObservationFilter`: one
+  `FilterRegistrationBean<CorrelationIdFilter>` with the filter created inline, guarded by
+  `@ConditionalOnMissingFilterBean(CorrelationIdFilter.class)`, so a consumer `CorrelationIdFilter` bean
+  or `FilterRegistrationBean<CorrelationIdFilter>` under any name replaces it, and unrelated filter
+  registrations do not. `CorrelationIdFilter` is `Ordered` at `FILTER_ORDER`, so a consumer filter bean
+  that Boot registers keeps the platform's position and dispatch types. The banner is a public
+  `PlatformBannerRunner` class backing off by type; unrelated `ApplicationRunner` beans no longer matter.
+- **D99 — neutral problem `type` default `urn:dc:problem:` (hardening HI-0010).** Supersedes the
+  `type-base-uri="https://errors.dc.com/"` default in `specs/phase-04-errors-logging-validation.md`: that
+  host is not owned by the platform. RFC 9457 allows a non-dereferenceable `type`; `dc` is not an
+  IANA-registered URN namespace, so the value is an opaque identifier that services replace with an
+  `https://` documentation base once one exists. 401/403 bodies read the same key and default.
+- **D100 — no `replacement` for the removed correlation properties (hardening A1).** The
+  `dc.platform.core.correlation.*` and `dc.platform.restclient.propagate-correlation` keys are
+  deprecated at level `error` with a reason only: the correlation ID is now the W3C trace ID and has no
+  successor property, so naming one would mislead.
diff --git a/docs/modules/core.md b/docs/modules/core.md
index ee622c9..559c919 100644
--- a/docs/modules/core.md
+++ b/docs/modules/core.md
@@ -85,11 +85,32 @@ RequestContext.correlationId()
 
 ## Replace / Disable
 
-- Define your own `CorrelationIdFilter` bean to replace the filter (the platform registration
-  wraps yours); define a bean named `platformCorrelationFilterRegistration` to take over registration
-  entirely — the platform then creates no filter bean at all (Boot would otherwise auto-register it).
-  Keep yours after the observation filter, or there is no span yet.
-- Define an `ApplicationRunner` named `platformBannerRunner` to replace the banner.
+Overrides are by type, under any bean name (D98), the way Boot backs off its own filters:
+
+- Define your own `CorrelationIdFilter` bean (a subclass, say) to replace the filter. The platform
+  registration backs off and Boot registers yours; `CorrelationIdFilter` is `Ordered` at
+  `CoreContextAutoConfiguration.FILTER_ORDER` and runs on REQUEST, ASYNC and ERROR dispatches, so it
+  keeps the platform's position.
+- Define a `FilterRegistrationBean<CorrelationIdFilter>` to control the registration yourself (order,
+  URL patterns, or `setEnabled(false)`). Keep it after the observation filter, or there is no span yet:
+
+  ```java
+  @Bean
+  FilterRegistrationBean<CorrelationIdFilter> correlationRegistration(Tracer tracer) {
+      var registration = new FilterRegistrationBean<>(new CorrelationIdFilter(tracer));
+      registration.setOrder(CoreContextAutoConfiguration.FILTER_ORDER);
+      registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
+      return registration;
+  }
+  ```
+
+  Unrelated filters and registrations never affect the platform's.
+- Define a `PlatformBannerRunner` bean (subclass it and override `describe`) to change the banner, or
+  set `dc.platform.core.banner-enabled=false` and add your own `ApplicationRunner`. Other
+  `ApplicationRunner` beans never suppress the banner.
+- Capability descriptors are one bean per capability, so they back off **by name**, like Boot's health
+  indicators (D97): a bean named `coreCapabilityDescriptor` replaces the core entry. A descriptor under
+  any other name is simply one more entry.
 - `dc.platform.core.enabled=false` switches the capability off wholesale (no correlation context, no
   platform executor default); tracing itself keeps working.
 
diff --git a/docs/modules/errors.md b/docs/modules/errors.md
index 8870165..2e3212a 100644
--- a/docs/modules/errors.md
+++ b/docs/modules/errors.md
@@ -62,7 +62,7 @@ throw new NotFoundException(new ErrorCode("DC-ORDER-0404"), "order 42 not found"
 
 ```json
 {
-  "type": "https://errors.dc.com/DC-ORDER-0404",
+  "type": "urn:dc:problem:DC-ORDER-0404",
   "title": "DC-ORDER-0404",
   "status": 404,
   "detail": "order 42 not found",
@@ -80,12 +80,14 @@ throw new NotFoundException(new ErrorCode("DC-ORDER-0404"), "order 42 not found"
 |---|---|---|
 | `dc.platform.errors.enabled` | `true` | Kill switch for the whole capability. |
 | `dc.platform.errors.include-stacktrace` | `false` | Add a `stacktrace` extension (debug only). |
-| `dc.platform.errors.type-base-uri` | `https://errors.dc.com/` | Base URI forming the problem `type`. |
+| `dc.platform.errors.type-base-uri` | `urn:dc:problem:` | Base URI forming the problem `type`. |
 | `dc.platform.errors.map-validation` | `true` | Map validation failures to 400 + `errors[]`. |
 | `dc.platform.errors.include-rejected-values` | `false` | Echo invalid values as `errors[].rejectedValue` (raw user input; credentials stay redacted). |
 
-The `type` base URI (`https://errors.dc.com/`) is a placeholder on a domain the platform does not own;
-set `type-base-uri` to your organisation's problem-type namespace (open issue G1-08).
+The default `type` prefix `urn:dc:problem:` is a neutral, non-dereferenceable identifier (RFC 9457
+allows a `type` that is not a locator): clients match on it, nothing tries to fetch it, and it points at
+no host the platform does not own. Once your organisation publishes error documentation, set
+`type-base-uri` to that `https://…/` base so `type` links to the page for each code (D99).
 
 (Hand-written until the generated reference lands in phase 14.)
 
@@ -135,5 +137,5 @@ without a full context. Open a `RequestContext` (try-with-resources) around the
 ## Local dev notes
 
 No Docker, no network. Quick smoke check: `curl -s localhost:8080/nonexistent | jq .type` — a
-`https://errors.dc.com/…` URI proves the advice is active; Spring's default body means the
+`urn:dc:problem:…` URI proves the advice is active; Spring's default body means the
 starter is missing or the kill switch is off.
diff --git a/docs/modules/security.md b/docs/modules/security.md
index 95c1323..c21da87 100644
--- a/docs/modules/security.md
+++ b/docs/modules/security.md
@@ -53,7 +53,7 @@ spring:
 Anonymous requests to anything not in `permit-paths` get:
 
 ```json
-{"type":"https://errors.dc.com/DC-SEC-0401","title":"Unauthorized","status":401,
+{"type":"urn:dc:problem:DC-SEC-0401","title":"Unauthorized","status":401,
  "detail":"Full authentication is required to access this resource","instance":"/orders",
  "code":"DC-SEC-0401","traceId":"0af7651916cd43dd8448eb211c80319c",
  "timestamp":"2026-01-01T00:00:00Z","retryable":false}
diff --git a/docs/samples/core-errors/README.md b/docs/samples/core-errors/README.md
index cdfaeb8..6c953b5 100644
--- a/docs/samples/core-errors/README.md
+++ b/docs/samples/core-errors/README.md
@@ -40,7 +40,7 @@ curl -s -H 'Authorization: Bearer <token>' \
 ```
 
 ```json
-{"type":"https://errors.dc.com/DC-ORDR-0404","title":"DC-ORDR-0404","status":404,
+{"type":"urn:dc:problem:DC-ORDR-0404","title":"DC-ORDR-0404","status":404,
  "detail":"order 42 not found","instance":"/orders/42","code":"DC-ORDR-0404",
  "traceId":"0af7651916cd43dd8448eb211c80319c","timestamp":"2026-10-26T04:00:00Z","retryable":false}
 ```
diff --git a/docs/samples/core-errors/operations.md b/docs/samples/core-errors/operations.md
index 5b4b805..e15b3bc 100644
--- a/docs/samples/core-errors/operations.md
+++ b/docs/samples/core-errors/operations.md
@@ -47,8 +47,10 @@ termination grace above the phase timeout (the harness uses 45 s).
 
 | Bean | Replace by |
 |---|---|
-| `CorrelationIdFilter` | your own `CorrelationIdFilter` bean (keep it after the observation filter) |
-| filter registration | bean named `platformCorrelationFilterRegistration` (the platform then creates no filter) |
+| `CorrelationIdFilter` | your own `CorrelationIdFilter` bean, any name (Boot registers it at `FILTER_ORDER`, every dispatch type) |
+| filter registration | a `FilterRegistrationBean<CorrelationIdFilter>`, any name (the platform then registers no filter) |
+| banner | a `PlatformBannerRunner` bean, any name; or `dc.platform.core.banner-enabled=false` |
+| capability descriptor | a bean named `<cap>CapabilityDescriptor` (by name: one bean per capability, D97) |
 | main advice | a `PlatformExceptionHandler` subclass bean, or any bean named `platformExceptionHandler` |
 | validation advice | a `PlatformConstraintViolationHandler` bean, or any bean named `platformValidationExceptionHandler` |
 | error page | any `ErrorController` bean |
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorsProperties.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorsProperties.java
index 0a711b0..a69ae88 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorsProperties.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorsProperties.java
@@ -28,7 +28,7 @@ public record ErrorsProperties(
         /** Add a stacktrace extension to problem bodies (debugging aid; keep off in production). */
         @DefaultValue("false") boolean includeStacktrace,
         /** Base URI prepended to the error code to form the problem type. */
-        @DefaultValue("https://errors.dc.com/") String typeBaseUri,
+        @DefaultValue("urn:dc:problem:") String typeBaseUri,
         /** Map Bean Validation failures to 400 problem responses with an errors[] extension. */
         @DefaultValue("true") boolean mapValidation,
         /** Echo invalid values as errors[].rejectedValue (raw user input; off by default). */
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorContractIntegrationTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorContractIntegrationTest.java
index 773541f..27303bb 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorContractIntegrationTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorContractIntegrationTest.java
@@ -108,7 +108,7 @@ class ErrorContractIntegrationTest {
             assertThat(body).containsEntry("status", 409).containsEntry("code", "DC-CORE-0499")
                     .containsEntry("instance", "/duplicate").containsEntry("detail", "Conflict")
                     .containsEntry("retryable", false).containsKey("timestamp")
-                    .containsEntry("type", "https://errors.dc.com/DC-CORE-0499");
+                    .containsEntry("type", "urn:dc:problem:DC-CORE-0499");
         }
     }
 
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandlerTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandlerTest.java
index 4f7fc7d..996fd0d 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandlerTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandlerTest.java
@@ -30,7 +30,7 @@ class PlatformConstraintViolationHandlerTest {
     }
 
     private ProblemDetail handle(Object invalidBean, boolean includeRejectedValues) {
-        ErrorsProperties properties = new ErrorsProperties(true, false, "https://errors.dc.com/", true,
+        ErrorsProperties properties = new ErrorsProperties(true, false, "urn:dc:problem:", true,
                 includeRejectedValues);
         PlatformConstraintViolationHandler handler = new PlatformConstraintViolationHandler(
                 new ProblemDetailFactory(properties, List.of(), FIXED_CLOCK));
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorHandlingAutoConfigurationTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorHandlingAutoConfigurationTest.java
index 1a5240b..86c6101 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorHandlingAutoConfigurationTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorHandlingAutoConfigurationTest.java
@@ -130,7 +130,7 @@ class PlatformErrorHandlingAutoConfigurationTest {
         // Plan §7.2: a changed default fails here until the snapshot is consciously updated and the
         // change is recorded in the phase changelog. include-rejected-values=false is new in phase 1.
         runner.run(context -> assertThat(context.getBean(ErrorsProperties.class))
-                .isEqualTo(new ErrorsProperties(true, false, "https://errors.dc.com/", true, false)));
+                .isEqualTo(new ErrorsProperties(true, false, "urn:dc:problem:", true, false)));
     }
 
     @Test
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
index 8db4e58..90648a4 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandlerTest.java
@@ -47,11 +47,11 @@ class PlatformExceptionHandlerTest {
     }
 
     private static ErrorsProperties defaults() {
-        return new ErrorsProperties(true, false, "https://errors.dc.com/", true, false);
+        return new ErrorsProperties(true, false, "urn:dc:problem:", true, false);
     }
 
     private static ErrorsProperties withRejectedValues() {
-        return new ErrorsProperties(true, false, "https://errors.dc.com/", true, true);
+        return new ErrorsProperties(true, false, "urn:dc:problem:", true, true);
     }
 
     @Test
@@ -65,7 +65,7 @@ class PlatformExceptionHandlerTest {
             // The one strict full-shape assertion (spec: assert full JSON shape once with JSONAssert).
             JSONAssert.assertEquals("""
                     {
-                      "type": "https://errors.dc.com/DC-TEST-0404",
+                      "type": "urn:dc:problem:DC-TEST-0404",
                       "title": "DC-TEST-0404",
                       "status": 404,
                       "detail": "order 42 not found",
@@ -161,7 +161,7 @@ class PlatformExceptionHandlerTest {
 
     @Test
     void mapValidationOffFallsBackToDefaultSpringBody() throws Exception {
-        ErrorsProperties off = new ErrorsProperties(true, false, "https://errors.dc.com/", false, false);
+        ErrorsProperties off = new ErrorsProperties(true, false, "urn:dc:problem:", false, false);
         mockMvc(off, List.of())
                 .perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                         .content("{\"name\":\"\",\"password\":\"hunter2!\"}"))
@@ -171,7 +171,7 @@ class PlatformExceptionHandlerTest {
 
     @Test
     void includeStacktraceAddsTheExtension() throws Exception {
-        ErrorsProperties withTrace = new ErrorsProperties(true, true, "https://errors.dc.com/", true, false);
+        ErrorsProperties withTrace = new ErrorsProperties(true, true, "urn:dc:problem:", true, false);
         mockMvc(withTrace, List.of()).perform(get("/boom"))
                 .andExpect(jsonPath("$.stacktrace").exists());
     }
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactoryTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactoryTest.java
index fdfcfa4..6ed47be 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactoryTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactoryTest.java
@@ -19,7 +19,7 @@ class ProblemDetailFactoryTest {
 
     private static ProblemDetailFactory factory(boolean includeRejectedValues) {
         return new ProblemDetailFactory(
-                new ErrorsProperties(true, false, "https://errors.dc.com/", true, includeRejectedValues),
+                new ErrorsProperties(true, false, "urn:dc:problem:", true, includeRejectedValues),
                 List.of(), Clock.systemUTC());
     }
 
diff --git a/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/PlatformSecurityAutoConfiguration.java b/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/PlatformSecurityAutoConfiguration.java
index 07439df..28c82db 100644
--- a/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/PlatformSecurityAutoConfiguration.java
+++ b/security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/PlatformSecurityAutoConfiguration.java
@@ -58,7 +58,7 @@ public class PlatformSecurityAutoConfiguration {
             ObjectProvider<SecurityCustomizer> customizers, Environment environment) throws Exception {
         // The errors capability owns the problem-type namespace; read its key (same default) so 401/403
         // types match every other platform error without depending on the errors module.
-        String typeBaseUri = environment.getProperty("dc.platform.errors.type-base-uri", "https://errors.dc.com/");
+        String typeBaseUri = environment.getProperty("dc.platform.errors.type-base-uri", "urn:dc:problem:");
         ProblemDetailAuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(typeBaseUri);
         ProblemDetailAccessDeniedHandler deniedHandler = new ProblemDetailAccessDeniedHandler(typeBaseUri);
         http.csrf(csrf -> csrf.disable()) // stateless bearer-token resource server, no cookie session
diff --git a/tooling/platform-skill/skill/resources/property-cheatsheet.md b/tooling/platform-skill/skill/resources/property-cheatsheet.md
index 22097b1..809f70d 100644
--- a/tooling/platform-skill/skill/resources/property-cheatsheet.md
+++ b/tooling/platform-skill/skill/resources/property-cheatsheet.md
@@ -17,7 +17,7 @@
 | `dc.platform.errors.enabled` | Boolean | `true` |
 | `dc.platform.errors.include-stacktrace` | Boolean | `false` |
 | `dc.platform.errors.map-validation` | Boolean | `true` |
-| `dc.platform.errors.type-base-uri` | String | `https://errors.dc.com/` |
+| `dc.platform.errors.type-base-uri` | String | `urn:dc:problem:` |
 | `dc.platform.events.enabled` | Boolean | `true` |
 | `dc.platform.events.relay.destination-prefix` | String | `dc.` |
 | `dc.platform.events.relay.enabled` | Boolean | `false` |
```
