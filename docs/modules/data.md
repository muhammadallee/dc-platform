# Data / JPA

Tech-neutral persistence value objects (`platform-data-api`) plus opinionated Spring Data JPA
wiring (`platform-data-jpa-autoconfigure`): auditing, snake_case naming, sane Hibernate defaults,
a `Money` attribute converter, and a Flyway-presence guard — all on by default, all overridable.

## What you get

### `platform-data-api` (tech-neutral, no JPA on the classpath)

- **`Money`** — an immutable amount + ISO-4217 `Currency`. Same-currency `add`/`subtract` that fail
  loudly on a currency mismatch. Persisted to one column via the storage form
  `"<amount> <currencyCode>"` (`toStorageString()` / `parse(String)`) — the contract the JPA
  attribute converter implements.
- **`EntityId<T>`** — a typed wrapper around a raw id so an `OrderId` cannot be passed where a
  `CustomerId` is expected.
- **`PersistenceConventions`** — shared constants: `PHYSICAL_NAMING` (`snake_case`), standard column
  lengths (`CODE_LENGTH`, `NAME_LENGTH`, `DESCRIPTION_LENGTH`, `MONEY_LENGTH`, …), and the audit
  column names (`created_at`, `created_by`, `last_modified_at`, `last_modified_by`, `version`).

### `platform-data-jpa-autoconfigure` (opinionated Spring Data JPA)

- **Auditing** — `@CreatedDate`/`@CreatedBy`/`@LastModifiedDate`/`@LastModifiedBy` are populated
  automatically. The auditor is the authenticated user's subject when the security capability is on
  the classpath and the request is authenticated, otherwise `"system"`. A data-only service (no
  security starter) audits everything as `"system"` and never pulls in a security type.
- **Money / CorrelationId converters** — `MoneyConverter` and `CorrelationIdConverter` persist those
  value types to a single column. Opt in per field with `@Convert` (see below); they are not
  auto-applied (a library converter is not part of your scanned persistence unit, decision D34).
- **Hibernate defaults** — contributed as standard `spring.jpa.*` values at lowest precedence
  (source `platform-data-jpa-defaults`, visible in `/actuator/env`): `open-in-view=false`, batch
  size 50 with ordered inserts/updates, UTC jdbc time zone. snake_case physical naming is Boot's own
  default and is left in place (decision D35). Override any of these with the normal Boot keys.
- **Flyway guard** — startup fails fast if JPA is configured but Flyway is missing, with a message
  telling you to add the starter or set `dc.platform.data.jpa.require-migrations=false`.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-data-jpa</artifactId>
</dependency>
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.data.jpa.enabled` | `true` | Kill switch for the JPA capability. |
| `dc.platform.data.jpa.require-migrations` | `true` | Fail startup when JPA is present but Flyway is absent. |

## Persisting Money / CorrelationId

```java
@Convert(converter = MoneyConverter.class)
private Money price;                 // stored as "19.99 AED"

@Convert(converter = CorrelationIdConverter.class)
private CorrelationId correlationId; // stored as 32 hex chars
```

## Using the value types

```java
@Column(name = "reference_code", length = PersistenceConventions.CODE_LENGTH)
private String referenceCode;

Money price = Money.of("19.99", "AED");
Money total = price.add(Money.of("1.00", "AED"));   // 20.99 AED
```

## Local dev notes

`platform-data-api` has no datastore dependency at all. The JPA slice (below) tests against H2; no
Docker or network is required for the default build.
