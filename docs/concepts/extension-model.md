# Extension model

> From the architecture design §6. One pattern for all extension (ADR-008): **implement the SPI, ship
> your own auto-configuration, register it, order it before the platform's default.** Platform
> defaults are all `@ConditionalOnMissingBean`, so your bean wins. **No platform module is ever
> modified.**

## Two tiers

**Customizer tier — tweak the default.** Contribute `*Customizer` beans (`RestClientCustomizer`,
`ProblemDetailCustomizer`, `KafkaTemplateCustomizer`, …). The platform collects them via
`ObjectProvider<List<…Customizer>>.orderedStream()` and applies them in `@Order`. You add behaviour
without replacing beans.

**Provider tier — replace the engine.** Implement a capability SPI and contribute the bean; the
platform default backs off. Certify your provider against the capability's TCK.

## Provider examples

| Add a new… | Implement | Register | Platform behaviour |
|---|---|---|---|
| Messaging provider (e.g. Pulsar) | `EventTransport` from `messaging-spi` | your autoconfigure + starter | façade binds to any `EventTransport`; default backs off via `@ConditionalOnMissingBean` |
| Storage provider (e.g. GCS) | `ObjectStore` from `storage-spi` | bean `ObjectStore` | same back-off pattern |
| Authentication provider | `TokenIntrospector`/`IssuerResolver` customizers, or a full SPI | bean replaces default | security composes customizers via `ObjectProvider` |
| Cache provider | Spring's `CacheManager` + `CacheKeyConvention` SPI | bean `CacheManager` | platform decorates any manager with metrics/conventions |
| Tracing provider | Micrometer `Tracer` + `ObservationCustomizer` | bean | Micrometer *is* the SPI; platform never wraps tracing itself |

## TCK certification

Each multi-provider capability ships a `platform-<cap>-tck` test-jar with an abstract JUnit class.
A provider proves conformance by extending it and pointing it at their implementation — the same
contract the platform's own providers pass. See [testing](../testing.md) and the module pages for
[messaging](../modules/messaging.md), [storage](../modules/storage.md), [locking](../modules/locking.md),
[flags](../modules/flags.md), and [ratelimit](../modules/ratelimit.md).

## Next

- [Conventions](conventions.md) · [Dependency constitution](constitution.md) · [ADR-008](../decisions/adr-008.md) · [ADR-003](../decisions/adr-003.md).
