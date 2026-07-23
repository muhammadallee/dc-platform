# Feature Flags

Evaluate boolean and typed feature flags, and gate code paths behind them with an annotation.

## What you get

- **`FeatureFlags`** — `enabled(flag)` for the on/off case; `value(flag, default)` for a typed value
  coerced to the same type as your default (`true`/`false`, numbers, strings). Unknown flags fall back
  to `false` / your default.
- **`@FeatureGate("flag")`** — gate a method: it runs only when the flag is on, otherwise it is skipped
  and a neutral value is returned (`false` for boolean, `Optional.empty()`, `null`/no-op for references
  and `void`, zero for other primitives).
- **Two providers, chosen by classpath:**
  - **In-memory (default)** — flags from `dc.platform.flags.static.*`, mutable at runtime through the
    `platformflags` actuator endpoint. Zero infrastructure — ideal for local demos and tests.
  - **OpenFeature** — an adapter over the OpenFeature SDK. Selected over in-memory when an OpenFeature
    `Client` bean is present, so enterprise providers (LaunchDarkly, Flagsmith, …) plug in downstream.
- **User/tenant targeting** — when the security capability is present, the current user (subject) and
  tenant are resolved into the evaluation context passed to the provider.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-flags</artifactId>
</dependency>
```

The starter bundles the in-memory provider. To use OpenFeature instead, add the OpenFeature SDK and
register a configured `Client` bean (with your provider) — the adapter takes over automatically.

## Usage

```java
if (featureFlags.enabled("new-clearance-flow")) { ... }
int batchSize = featureFlags.value("import.batch-size", 100);

@FeatureGate("new-clearance-flow")
public Receipt clearViaNewFlow(Declaration d) { ... }   // skipped (returns null) when the flag is off
```

Flip a flag at runtime (in-memory provider):

```
POST   /actuator/platformflags/new-clearance-flow   {"value":"true"}
DELETE /actuator/platformflags/new-clearance-flow
GET    /actuator/platformflags
```

The endpoint is a write operation — expose and secure it via your actuator configuration.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.flags.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.flags.static.<flag>` | — | Seed value for a flag in the in-memory provider. |

The provider is selected by the classpath (OpenFeature when a `Client` bean is present, otherwise
in-memory), not by a property.

## Replace / Disable

- Define your own `FeatureFlags` bean to replace the platform facade entirely (it backs off).
- Define your own `FlagProvider` bean to plug a different backend under the platform `FeatureFlags`.
- `dc.platform.flags.enabled=false` switches the capability off wholesale.

## Local dev notes

The in-memory provider needs no infrastructure and is the default; flip flags live via the
`platformflags` endpoint. The OpenFeature adapter is unit-tested against a mocked `Client`.
