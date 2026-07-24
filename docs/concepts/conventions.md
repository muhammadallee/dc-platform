# Conventions

> Properties, packages, error codes, and profiles. Conventions are **API** (ADR-010): every
> capability works with zero configuration; configuration exists only to *deviate*, and changing a
> default is a breaking change.

## Properties

- Every platform property lives under `dc.platform.<capability>`.
- `@ConfigurationProperties` classes are **immutable records** with constructor binding; every
  property has a default in code **and** in config metadata (so IDEs autocomplete every key).
- Every capability has a kill switch: `dc.platform.<capability>.enabled` (default `true`,
  `matchIfMissing = true`).
- Renames require a deprecation window with `additional-spring-configuration-metadata.json`
  deprecation entries — property keys are a contract.

The full, generated list is the [configuration properties reference](../reference/properties.md).

## Packages

For a capability `messaging`:

```
ae.gov.dubaicustoms.platform.messaging                 → public API (stable)
ae.gov.dubaicustoms.platform.messaging.annotation      → public annotations
ae.gov.dubaicustoms.platform.messaging.spi             → SPI (stable-for-extenders)
ae.gov.dubaicustoms.platform.messaging.config          → @ConfigurationProperties (stable keys)
ae.gov.dubaicustoms.platform.messaging.autoconfigure   → @AutoConfiguration (names stable for exclude=)
ae.gov.dubaicustoms.platform.messaging.<provider>          → provider public surface (small)
ae.gov.dubaicustoms.platform.messaging.<provider>.internal → internals (no guarantees)
ae.gov.dubaicustoms.platform.messaging.internal        → internals (no guarantees)
ae.gov.dubaicustoms.platform.messaging.migration       → deprecated bridges during a deprecation window
```

`*.internal.*` is excluded from javadoc, excluded from japicmp, and flagged by an ArchUnit rule if
imported from application code.

## Error codes

- Every platform error carries a stable `DC-<CAP>-<NNNN>` code (`DC-CORE-0500`, `DC-MSG-0001`, …).
- Codes are **unique platform-wide**, enforced by the build-time error-code registry gate.
- Codes surface in RFC-9457 problem responses. The generated list is the
  [error-codes reference](../reference/error-codes.md); see also the [errors module](../modules/errors.md).

## Profiles

- `local` — console logs, in-memory/H2 providers; the default for laptops and the untagged test suite.
- `kafka`, `rabbit`, `redis`, `pg`, `s3`, `vault` — switch a capability to a real backing service
  (via `docker-compose.local.yml`); see the [local development runbook](../runbooks/local-dev.md).
- Docker-backed tests are tagged `@Tag("docker")` and only run under `-Pdocker`.

## Auto-configuration conditions (evaluation order)

`@ConditionalOnClass` (provider on classpath) → `@ConditionalOnProperty` (kill switch,
`matchIfMissing = true`) → `@ConditionalOnMissingBean` (user override). Cheapest-to-evaluate first.

## Next

- [Dependency constitution](constitution.md) · [Extension model](extension-model.md) · [ADR-010](../decisions/adr-010.md).
