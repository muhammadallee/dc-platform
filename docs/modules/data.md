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

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-data-jpa</artifactId>
</dependency>
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
