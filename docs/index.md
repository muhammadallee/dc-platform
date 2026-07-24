# DC Platform

> **Version:** `0.2.0-SNAPSHOT` (release train) · **Spring Boot:** 4.x · **Java:** 25

An internal Spring Boot **chassis**. It gives every service the same cross-cutting behaviour —
correlation IDs, JSON logs, RFC-9457 errors, validation, metrics/tracing, OpenAPI, an
authenticated-by-default security chain, messaging, and persistence conventions — with **zero
configuration**. You write business logic; the platform does the plumbing.

## Why it exists

Hundreds of services should not each re-decide how to log, how to shape errors, how to propagate a
correlation id, or how to lock a scheduled job. The platform makes those decisions **once**, by
experts, and ships them as defaults you can override. Consistency across services is itself a feature:
operability, security posture, and staffing mobility all improve. See
[why convention over configuration](decisions/adr-010.md).

## 3-minute tour

1. **Generate a service** from the archetype — a running, wired service in under 10 minutes. See the
   [quickstart](quickstart.md).
2. **Add capabilities à la carte.** Each is one starter; a capability you don't add costs you nothing.
   Browse the [capability guides](modules/core.md) and the [platform BOM](reference/bom.md).
3. **Deviate when you must.** Every default is overridable under `dc.platform.<capability>`; every
   capability has a kill switch. The full list is the
   [configuration properties reference](reference/properties.md).
4. **Extend without forking.** Implement an SPI, contribute a bean, and the platform default backs
   off — see the [extension model](concepts/extension-model.md).

## Start here

- **New to the platform?** → [Quickstart](quickstart.md)
- **Why is it shaped this way?** → [Architecture](concepts/architecture.md) ·
  [Dependency constitution](concepts/constitution.md) · [Conventions](concepts/conventions.md)
- **Building a service?** → [Capability guides](modules/core.md) · [Configuration properties](reference/properties.md) ·
  [Error codes](reference/error-codes.md)
- **Operating or upgrading?** → [Runbooks](runbooks/local-dev.md) · [Upgrade notes](upgrade/0.2.0.md) ·
  [Compatibility](reference/compatibility.md)
- **Extending the platform?** → [Extension model](concepts/extension-model.md) · [Testing](testing.md) ·
  [Decision records](decisions/adr-001.md)

## Guarantees

The platform builds and tests on a laptop with **no Docker, no network beyond Maven Central, and no
credentials**. Docker-backed tests are opt-in (`-Pdocker`). The dependency constitution, ArchUnit
rules, coverage, and binary-compatibility checks are all enforced by a green `mvn -T1C verify`.
