# Compatibility

The platform ships as a single release train (ADR-005): every `platform-*` artifact shares one
version, and SemVer applies to the train as a whole.

## Binary compatibility guarantee

> Within major version **N**, any application or extension compiled against platform **N.x** public
> API/SPI runs unmodified on every **N.y** (y ≥ x). Internals carry no guarantee. Majors may break
> with a published migration guide and automated OpenRewrite recipes.

| Surface | Package | Promise |
|---|---|---|
| Public API | `…​.<cap>`, `…​.annotation`, config keys | binary-compatible within a major; checked by japicmp against the last released minor |
| SPI | `…​.spi` | binary-compatible within a major; **new default methods allowed** in minors |
| Auto-configuration class names | `…​.autoconfigure` | semi-public (used in `exclude=`); renamed only at majors, old name kept as a deprecated forwarder for one minor |
| Internal | `…​.internal` | **no guarantee**; may change in patches; excluded from japicmp and javadoc |

## japicmp reports

Binary-compatibility is enforced by the japicmp gate over `-api`/`-spi` packages. The gate is armed
once the first release is tagged (`0.1.0`); before that it runs in report-only mode.

Per-release japicmp reports are published alongside the docs site under `apidocs/` and linked from
each [upgrade note](../upgrade/0.2.0.md) as trains are cut. No comparison baseline exists yet for the
current `0.2.0-SNAPSHOT` development train.

## Related

- [ADR-005 — release-train versioning](../decisions/adr-005.md)
- [Upgrade runbook](../runbooks/upgrade.md) · [Release runbook](../runbooks/release.md)
