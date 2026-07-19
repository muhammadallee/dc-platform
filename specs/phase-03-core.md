# Phase 3 — Core (P0, size M, no Docker)

**Modules:** `core/platform-core-api`, `core/platform-core-autoconfigure`, `core/platform-starter-core`.
**Goal:** the smallest useful chassis: correlation, platform exception model, capability banner/report.
`core-api` is capped: **≤ 25 public types, forever.** Add here only what ≥3 capabilities need.

## 1. `platform-core-api` — public contracts (full signatures; implement exactly; full javadoc per coding-standards §2)

```java
package ae.gov.dubaicustoms.platform.core;

/** Marker: type is stable public API covered by SemVer guarantees. @since 0.1.0 */
public @interface PlatformApi {}

/** Marker: internal, no compatibility guarantees; do not use outside its module. @since 0.1.0 */
public @interface PlatformInternal {}

/**
 * Root of the platform exception hierarchy. Carries a stable, machine-readable {@link ErrorCode}.
 * Immutable and safe to serialize into ProblemDetail (phase 4). @since 0.1.0
 */
public abstract class PlatformException extends RuntimeException {
    protected PlatformException(ErrorCode code, String message) { … }
    protected PlatformException(ErrorCode code, String message, Throwable cause) { … }
    public ErrorCode code();
}

/**
 * Stable error identifier: UPPER_SNAKE, namespaced "DC-<CAP>-<NNNN>", e.g. DC-MSG-0001.
 * Uniqueness across the platform is checked at build time (phase 4 registry test). @since 0.1.0
 */
public record ErrorCode(String value) {
    public ErrorCode { /* validate ^DC-[A-Z]{2,8}-\d{4}$ */ }
}

package ae.gov.dubaicustoms.platform.core.context;

/**
 * Correlation identifier propagated across threads, HTTP, and messaging.
 * Value object; creation is cheap; format is 32 lowercase hex (UUID without dashes). @since 0.1.0
 */
public record CorrelationId(String value) {
    public static CorrelationId random();
    public CorrelationId { /* validate */ }
}

/**
 * Access to the current request context. Backed by MDC + (later) Micrometer context-propagation.
 * Static access is READ-ONLY convenience; population is done exclusively by platform filters/interceptors.
 * Thread-safe. @since 0.1.0
 */
public final class RequestContext {
    public static Optional<CorrelationId> correlationId();
    public static Map<String,String> asMap();     // snapshot for logging/messaging headers
    @PlatformInternal public static AutoCloseable open(CorrelationId id, Map<String,String> extras);
}
```
Also: `package-info.java` for each package with an overview javadoc; NO Spring imports in this module
except `org.springframework.lang.Nullable` if desired — prefer JSpecify `@Nullable` (add pin).

## 2. `platform-core-autoconfigure`
Properties record `CoreProperties` (`dc.platform.core`): `banner-enabled=true`,
`correlation.header-name="X-Correlation-Id"`, `correlation.generate-if-missing=true`.
Autoconfiguration classes (each from the canonical template, fully commented):
- `CoreContextAutoConfiguration` — registers `CorrelationIdFilter` (servlet, `@ConditionalOnWebApplication`):
  reads/generates the header, opens `RequestContext`, echoes header on the response, always closes.
  Highest filter precedence + comment explaining why (must wrap security & logging).
- `PlatformBannerAutoConfiguration` — `ApplicationRunner` logging one line per active capability:
  each capability later contributes a `CapabilityDescriptor` bean
  (`record CapabilityDescriptor(String name, String status, String detail)` — lives in core-api? NO:
  keep core-api Spring-free → put descriptor in `core-autoconfigure` public package
  `ae.gov.dubaicustoms.platform.core.report`, it is API-for-autoconfigure-modules only, mark `@PlatformApi`).
  Collect via `ObjectProvider<CapabilityDescriptor>`, log sorted: `platform: core[ACTIVE], …`.
- `AutoConfiguration.imports` lists both.
Tests: 5-case ContextRunner matrix ×2 configs; `MockMvc` test for filter (header echo, MDC populated
during request, cleared after); concurrency test that context never leaks across threads (executor + assertions).

## 3. `platform-starter-core` — POM only: core-autoconfigure + core-api + spring-boot-starter (base).
Comment in POM: "Included automatically by platform-service-parent? NO — apps declare it; the
archetype writes it. Rationale: explicit dependencies (ADR-007)." Then edit **platform-service-parent**:
do NOT auto-inject the starter; instead its README snippet shows it. (Keep parent inheritance-light.)

## Acceptance
```bash
mvn -T1C verify
# scratch app on service-parent + starter-core + starter-web:
curl -s -D- localhost:8080/anything | grep -i x-correlation-id      # header echoed
# log line shows: platform: core[ACTIVE]
```
DoD: core-api javadoc 100% on public types (checkstyle enforces); type count ≤25 (add a unit test
asserting it via classgraph — this is the cap made executable); BOM updated; docs/modules/core.md written.
