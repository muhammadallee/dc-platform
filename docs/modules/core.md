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
  `X-Correlation-Id` is ignored. An invalid `traceparent` is logged only as a sanitized,
  length-capped DEBUG marker — never verbatim.
- **Works when unsampled** — unsampled spans still carry a valid trace ID, so correlation does not
  depend on `management.tracing.sampling.probability`.
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

Correlation needs a tracer: `platform-starter-observability` brings the Micrometer Tracing
OpenTelemetry bridge (the archetype includes it). Without tracing on the classpath, requests run with
no correlation context and error bodies omit `traceId` — the platform never invents an id.

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

- Define your own `CorrelationIdFilter` bean to replace the filter (the platform registration
  wraps yours); define a `FilterRegistrationBean` named `platformCorrelationFilterRegistration`
  to take over registration entirely. Keep it after the observation filter, or there is no span yet.
- Define an `ApplicationRunner` named `platformBannerRunner` to replace the banner.
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
