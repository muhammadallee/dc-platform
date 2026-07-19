# Core

The smallest useful chassis: correlation-id propagation, the platform exception model, and the
startup capability banner.

## What you get

- **Correlation ids** — every servlet request gets a `CorrelationId` (32 lowercase hex): read from
  the `X-Correlation-Id` header or freshly generated, published to the SLF4J `MDC`
  (`%X{correlationId}`), readable via `RequestContext`, and echoed on the response.
- **Exception model** — subclass `PlatformException` with a stable `ErrorCode`
  (`DC-<CAP>-<NNNN>`); phase 4 maps them to RFC-9457 problem responses.
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

## Zero-config behavior

On a servlet web app: the correlation filter runs at highest filter precedence (it wraps security
and logging), generating ids when the header is absent or malformed. In any app: the banner logs
once the context is ready. Non-web apps get no filter; everything else is unchanged.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.core.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.core.banner-enabled` | `true` | Log the startup capability banner. |
| `dc.platform.core.correlation.header-name` | `X-Correlation-Id` | HTTP header carrying the id. |
| `dc.platform.core.correlation.generate-if-missing` | `true` | Mint a fresh id when the request has none. |

(Hand-written until the generated reference lands in phase 14.)

## Customize

- Change the header name or generation policy via the properties above.
- Contribute a capability line to the banner from your own auto-configuration:

```java
@Bean
CapabilityDescriptor myCapabilityDescriptor() {
    return new CapabilityDescriptor("mycap", "ACTIVE", "provider-x");
}
```

## Replace / Disable

- Define your own `CorrelationIdFilter` bean to replace the filter (the platform registration
  wraps yours); define a `FilterRegistrationBean` named `platformCorrelationFilterRegistration`
  to take over registration entirely.
- Define an `ApplicationRunner` named `platformBannerRunner` to replace the banner.
- `dc.platform.core.enabled=false` switches the capability off wholesale.

## Error codes

None yet — core defines the model (`PlatformException`, `ErrorCode`); capabilities start
registering codes in phase 4.

## Testing

`RequestContext.open(id, extras)` is `@PlatformInternal` but usable from tests to establish a
context around code under test (try-with-resources). The filter is a plain
`OncePerRequestFilter`: `MockMvcBuilders.standaloneSetup(controller).addFilters(new
CorrelationIdFilter("X-Correlation-Id", true))` exercises it without a full context.

## Local dev notes

No Docker, no network: everything in this capability is in-process. The banner is the quickest
smoke check that platform auto-configuration ran at all — if it is missing, check that the
starter is on the classpath and `dc.platform.core.enabled` is not `false`.
