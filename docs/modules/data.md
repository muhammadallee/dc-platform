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
- **Flyway guard** — startup fails fast if JPA is configured but migrations cannot run, with a message
  telling you to add the starter or set `dc.platform.data.jpa.require-migrations=false`. Spring Boot 4
  ships `FlywayAutoConfiguration` in `spring-boot-flyway`, so `flyway-core` alone is an engine nobody
  starts: the starter bundles both, and the guard checks both.
- **Bounded waits (D110)** — in the same `platform-data-jpa-defaults` source:
  - `spring.datasource.hikari.connection-timeout=5000`: a request behind an exhausted pool fails in 5 s
    (Hikari's own 30 s lets request threads pile up) with a 503 `DC-DATA-0503`;
  - `spring.transaction.default-timeout=30s`: every transaction, Spring Data repository methods
    included; once it is spent the driver cancels the running statement;
  - on a pool whose URL is `jdbc:oracle:`, the driver's `oracle.jdbc.ReadTimeout=60000` (the backstop
    for what a cancel cannot stop: a PL/SQL or lock wait, a dead network) and
    `oracle.net.CONNECT_TIMEOUT=10000` are added to the pool's `data-source-properties`;
  - `leak-detection-threshold=20000` (a stack trace for a connection held over 20 s) only under the
    `local` profile.
  Hikari's keepalive (2 min) and max lifetime (30 min) are its own defaults. Set any key yourself to
  change it — a value you set always wins (for the driver timeouts: a `data-source-properties` entry or
  a JVM system property).
- **Data errors as problems (D111)** — handled by the errors capability: a unique, foreign-key, NOT
  NULL or check violation is a 409 `DC-DATA-0409`, a concurrent update or lock a 409 `DC-DATA-0410`, an
  unavailable database or a timeout a retryable 503 `DC-DATA-0503`. The body never names SQL, tables or
  constraints; the full exception is logged at WARN on the request's trace. This also covers raw
  Hibernate/JPA exceptions from code that uses the `EntityManager` directly (Spring translates only
  behind a repository).

## Migrations

`platform-starter-data-jpa` runs Flyway at startup from `classpath:db/migration`; Hibernate DDL stays off
once Flyway is present (never set `spring.jpa.hibernate.ddl-auto=create/update` in production). The
service archetype's `data` feature ships `db/migration/V1__create_note.sql` for its `Note` sample and a
`NoteRepositoryTest` that proves the migrated schema with CRUD across transactions, rollback, and a
NOT NULL violation on H2. H2 covers local runs and tests only — the generated `prod` profile disables
the embedded fallback.

Flyway's safe settings are pinned (D112), so an upgrade cannot loosen them: `clean-disabled=true`,
`validate-on-migrate=true`, `out-of-order=false`, `validate-migration-naming=true`. In practice:
- name every file `V<version>__<description>.sql` (two underscores) — a misnamed file fails startup
  instead of being skipped;
- never edit an applied migration — the checksum mismatch blocks startup; add `V<next>__…` instead;
- out-of-order migrations are refused outside the `local` profile, with a startup diagnostic that
  names the fix, however they were switched on (`spring.flyway.*`, `flyway.conf`, a customizer).

## Oracle

Production runs Oracle 19c; Oracle is the only certified database (PostgreSQL is out for now). Use the
Oracle starter — it brings the JDBC driver **and** `flyway-database-oracle` (Flyway 10+ refuses a
database without its module: `Unsupported Database`):

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-data-jpa-oracle</artifactId>
</dependency>
```

Generate a new service with `-Dfeatures=data -Ddatabase=oracle`; H2 still backs its local runs and tests.

Behaviour verified against Oracle with Hibernate in 19c mode (`@Tag("docker")` tests in
`platform-data-jpa-autoconfigure`):

| Topic | What to do |
|---|---|
| Names | Unquoted snake_case names are stored upper-case; identifiers are limited to 128 characters (ORA-00972 beyond). |
| Keys | Sequences: `allocationSize` must equal the sequence's `INCREMENT BY`. Identity columns (`generated by default as identity`) work too. |
| `boolean` | Stored as `NUMBER(1)`; 19c has no SQL `BOOLEAN` — never use it in a migration. |
| `Instant` | Map to `TIMESTAMP WITH TIME ZONE` (D114). Hibernate reads `Instant` as an offset date-time; a plain `TIMESTAMP` column fails on read with ORA-18716. Values are unchanged across JVM and session time zones. |
| Paging | `Pageable` becomes `OFFSET … FETCH`; always sort for stable pages. |
| Empty strings | Oracle stores `''` as `NULL`: a `NOT NULL` column rejects an empty string (409 `DC-DATA-0409`). |
| Migrations | Write them 19c-safe: no `BOOLEAN`, no `IF [NOT] EXISTS`, `FROM DUAL` in selects, end PL/SQL blocks with a `/` line. |

The certification container is Oracle 23ai Free (no 19c image exists): Hibernate is pinned to 19 there,
but hand-written SQL runs against 23ai, which accepts 19c-invalid syntax — review migrations for 19c.
RAC failover (FCF/ONS, Application Continuity) is not covered.

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

`platform-data-api` has no datastore dependency at all. The JPA slice tests against H2; no
Docker or network is required for the default build. The Oracle tests run under `mvn -Pdocker verify`;
a cold Oracle container copies about 3 GB of datafiles (minutes on a laptop disk), so set
`testcontainers.reuse.enable=true` in `~/.testcontainers.properties` to keep the instance warm between
runs (each run still gets a fresh schema user).
