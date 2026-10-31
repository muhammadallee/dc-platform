# Core

The smallest useful chassis: request correlation by W3C trace ID, context propagation, the platform
exception model, and the startup capability banner.

## What you get

- **Correlation = the W3C trace ID** — the only correlation identifier is the trace ID of the
  current span. A request carrying a valid `traceparent` continues that trace; a missing or invalid
  one starts a new trace (Boot's observation filter and the OpenTelemetry W3C propagator do this — the
  platform never parses the header). The platform filter then opens `RequestContext` with the trace
  ID, so `RequestContext.correlationId()`, the SLF4J `MDC` (`traceId`, `spanId`) and every error body
  (`traceId`) carry it. No custom correlation header is read, generated, echoed or sent: a caller's
  `X-Correlation-Id` is ignored. A `traceparent` the propagator did not continue is logged only as a fixed DEBUG marker
  with the header's length — no header content ever reaches a log line.
- **Works when unsampled** — unsampled spans still carry a valid trace ID, so correlation does not
  depend on `management.tracing.sampling.probability`.
- **Every dispatch** — the context is re-established on ASYNC redispatches (a `Callable`/`CompletableFuture`
  handler that completes or fails later) and on the servlet ERROR dispatch (`sendError`, exceptions
  escaping the filter chain), so those error bodies carry the request's `traceId` too.
- **Context propagation** — the platform turns on Boot's own
  `spring.task.execution.propagate-context`, and registers a Micrometer `ThreadLocalAccessor` for
  `RequestContext`: `@Async` methods and Boot-built executors see the caller's trace in
  `RequestContext` and the MDC, and pool threads are clean after each task. Executors you build
  yourself need `ContextPropagatingTaskDecorator` (or `ContextSnapshot.wrap`) to get the same.
- **Exception model** — subclass `PlatformException` with a stable `ErrorCode`
  (`DC-<CAP>-<NNNN>`); the errors capability maps them to RFC-9457 problem responses.
- **Capability banner** — one INFO line at startup listing every active platform capability:
  `platform: core[ACTIVE], messaging[ACTIVE] (kafka)`.
- **API markers** — `@PlatformApi` (SemVer-guaranteed) and `@PlatformInternal` (no guarantees).

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-core</artifactId>
</dependency>
```

The core starter brings the tracer correlation needs: the Micrometer Tracing OpenTelemetry bridge and
Boot's tracing/observation auto-configuration (no exporter — spans are only shipped when the
observability starter adds one). A service with just core + errors + logging therefore still continues
an inbound `traceparent` into its logs and error bodies. If tracing is removed or disabled, requests
run with no correlation context and error bodies omit `traceId` — the platform never invents an id.

## Zero-config behavior

On a servlet web app: the correlation filter runs at order `HIGHEST_PRECEDENCE + 10` — after Boot's
`ServerHttpObservationFilter` (`HIGHEST_PRECEDENCE + 1`, which continues or starts the trace) and
before Spring Security (`-100`), so 401/403 bodies and security log lines already carry the trace ID.
In any app: Boot's task executors propagate context, and the banner logs once the context is ready.
Non-web apps get no filter; everything else is unchanged.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.core.enabled` | `true` | Kill switch for the whole capability (tier `DEFAULT_ON`). |
| `dc.platform.core.banner-enabled` | `true` | Log the startup capability banner. |
| `spring.task.execution.propagate-context` | `true` (platform default; Boot's is `false`) | Boot's own context propagation for its executors. |

Removed (deprecated, level `error` — setting them has no effect): `dc.platform.core.correlation.header-name`,
`dc.platform.core.correlation.generate-if-missing`. There is no header to name and nothing to generate:
a request without a valid `traceparent` always starts a new trace.

## Customize

- Contribute a capability line to the banner from your own auto-configuration:

```java
@Bean
CapabilityDescriptor myCapabilityDescriptor() {
    return new CapabilityDescriptor("mycap", "ACTIVE", "provider-x");
}
```

- Read the correlation id anywhere on a request (or `@Async`) thread:

```java
RequestContext.correlationId()
        .map(CorrelationId::value)          // the W3C trace ID
        .ifPresent(traceId -> receipt.setSupportReference(traceId));
```

## Replace / Disable

Overrides are by type, under any bean name (D98), the way Boot backs off its own filters:

- Define your own `CorrelationIdFilter` bean (a subclass, say) to replace the filter. The platform
  registration backs off and Boot registers yours; `CorrelationIdFilter` is `Ordered` at
  `CoreContextAutoConfiguration.FILTER_ORDER` and runs on REQUEST, ASYNC and ERROR dispatches, so it
  keeps the platform's position.
- Define a `FilterRegistrationBean<CorrelationIdFilter>` to control the registration yourself (order,
  URL patterns, or `setEnabled(false)`). Keep it after the observation filter, or there is no span yet:

  ```java
  @Bean
  FilterRegistrationBean<CorrelationIdFilter> correlationRegistration(Tracer tracer) {
      var registration = new FilterRegistrationBean<>(new CorrelationIdFilter(tracer));
      registration.setOrder(CoreContextAutoConfiguration.FILTER_ORDER);
      registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
      return registration;
  }
  ```

  Unrelated filters and registrations never affect the platform's.
- Define a `PlatformBannerRunner` bean (subclass it and override `describe`) to change the banner, or
  set `dc.platform.core.banner-enabled=false` and add your own `ApplicationRunner`. Other
  `ApplicationRunner` beans never suppress the banner.
- Capability descriptors are one bean per capability, so they back off **by name**, like Boot's health
  indicators (D97): a bean named `coreCapabilityDescriptor` replaces the core entry. A descriptor under
  any other name is simply one more entry.
- `dc.platform.core.enabled=false` switches the capability off wholesale (no correlation context, no
  platform executor default); tracing itself keeps working.

## Error codes

None — core defines the model (`PlatformException`, `ErrorCode`); see the errors capability for the
codes the platform maps.

## Testing

`RequestContext.open(id, extras)` is `@PlatformInternal` but usable from tests to establish a
context around code under test (try-with-resources). For a real request, send a `traceparent`
(`00-<32 hex trace id>-<16 hex span id>-01`) and assert the trace ID in the response's ProblemDetail or
the JSON log's `traceId`; the archetype's `HttpIntegrationTestSupport` does exactly that.

## Upgrading from the `X-Correlation-Id` model

Callers that sent `X-Correlation-Id` should send `traceparent` instead (any OpenTelemetry/W3C client
does this automatically). Log queries on `correlationId` become queries on `traceId`; ProblemDetail
consumers read `traceId`. `CorrelationId` and `RequestContext.correlationId()` keep their API.

## Local dev notes

No Docker, no network: everything in this capability is in-process. The banner is the quickest
smoke check that platform auto-configuration ran at all — if it is missing, check that the
starter is on the classpath and `dc.platform.core.enabled` is not `false`.
