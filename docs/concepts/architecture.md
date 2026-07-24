# Architecture

> Condensed from the platform architecture design (§1–2, §14). The full reasoning, candidate
> evaluation, and decision matrix live in the architecture document.

## What the platform is

An internal Spring Boot **chassis**: a set of defaults, not a cage. It gives every service the same
cross-cutting behaviour — correlation IDs, JSON logs, RFC-9457 errors, validation, metrics/tracing,
OpenAPI, security, messaging, persistence conventions — without a framework to learn. You add
business logic; the platform does the plumbing.

## The chosen shape: Hybrid Starter + SPI

Seven architectures were evaluated. Three survived to scoring; the **Hybrid Starter + SPI Platform**
won by a clear margin (weighted 8.48 vs 7.42 for pure starters and 6.58 for feature-modular).

- **Pure Starter-Based** (mirror Spring Boot): best simplicity and learning curve, but no contractual
  extension point — enterprises patch or fork, internals leak, compatibility is hard to promise.
- **Feature Modular** (independently versioned mini-products): maximal autonomy, but a version matrix
  at consumer scale is a support nightmare.
- **Hybrid Starter + SPI** (chosen): feature-sliced capabilities delivered as starters over
  auto-configuration, each multi-provider capability split into `api` / `spi` / `impl` /
  `autoconfigure` / `starter`. One BOM, one release train. Keeps the DX of pure starters, adds a
  contractual extension model, and avoids the version-matrix cost.

Hexagonal architecture was **rejected as the global style but adopted as an internal tactic**: the
platform has no business domain, so forcing every capability through a technology-free core produces
abstraction for its own sake. Where multiple providers genuinely exist (messaging, storage, cache,
locking) the ports-and-adapters idea is exactly right — and survives as the API/SPI/provider split.

## Guiding philosophy

1. **The platform is a set of defaults, not a cage.** Every bean is `@ConditionalOnMissingBean`;
   every feature has a kill switch (`dc.platform.<cap>.enabled=false`); every default is overridable
   in `application.yml`.
2. **Consumers see APIs, extenders see SPIs, nobody sees internals.** Three audiences, three package
   families, three compatibility promises.
3. **Spring Boot mechanisms only.** Auto-configuration, conditions, `ConfigurationProperties`,
   `ObjectProvider`, ordering annotations. No custom lifecycle, registry, or DI.
4. **A capability you don't add costs you nothing.** No transitive reach into Kafka from the logging
   starter. Optional/provided scopes and `@ConditionalOnClass` everywhere.
5. **One train, one BOM, one truth.** All platform modules share a version; consumers import one BOM;
   upgrades are one property change.

## Module taxonomy

A capability `X` is built from up to five module kinds:

| Module | Category | Contains |
|---|---|---|
| `platform-X-api` | API | Consumer-facing interfaces, annotations, value types, exceptions |
| `platform-X-spi` | SPI | Provider/extension contracts (`XProvider`, `XCustomizer`) |
| `platform-X-<provider>` | Implementation | One provider (e.g. `platform-messaging-kafka`) |
| `platform-X-autoconfigure` | Auto Configuration | `@AutoConfiguration`, `@ConfigurationProperties`, conditions |
| `platform-starter-X[-provider]` | Starter | **No code** — a `pom.xml` aggregating dependencies |

Simple capabilities (one plausible implementation) collapse to `api + autoconfigure + starter`. The
SPI module exists **only** where a second provider is plausible — never speculatively.

## Where this may fail (and the mitigations)

- **Module sprawl** without scaffolding → the archetype and module generator write the boilerplate.
- **API/SPI discipline decay** if gates are relaxed → the gates are merge-blocking; exceptions need
  an ADR.
- **Release-train hostage situations** if one capability is chronically unstable → incubator track.
- **Over-abstraction temptation** → the "SPI only at 2 plausible providers" rule.

## Next

- [Dependency constitution](constitution.md) — the rules and how the build enforces them.
- [Conventions](conventions.md) — properties, packages, error codes, profiles.
- [Extension model](extension-model.md) — customizer tier vs provider tier, and TCK certification.
- [Decision records](../decisions/adr-001.md) — the ten ADRs behind these choices.
