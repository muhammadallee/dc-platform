# Chapter 8 — Data and Persistence

> **Capabilities covered:** `data`
>
> JPA conventions, migrations, and the defaults that keep a hundred schemas comparable.
>
> **Starter:** `platform-starter-data-jpa` · **Reference:** [modules/data.md](../../modules/data.md)

---

## 1. Introduction and Business Value

This capability comes in two halves, and the split is the most important thing about it.

- **`platform-data-api`** — `Money`, `EntityId<T>`, `PersistenceConventions`. Value types with **no
  datastore dependency at all**. Not JPA, not Hibernate, not JDBC.
- **`platform-data-jpa-autoconfigure`** — opinionated Spring Data JPA wiring: auditing, Hibernate
  defaults, attribute converters, and a Flyway guard.

You can use the first without the second. That is the point of the split, and §2.1 explains why it
matters more than it sounds.

### The problem it solves

Two problems, at different altitudes.

**At the type level: money and identifiers are modelled wrong almost everywhere.**

```java
BigDecimal price = order.getPrice();
BigDecimal shipping = quote.getCost();
BigDecimal total = price.add(shipping);        // AED + USD = a plausible wrong number
```

That compiles, runs, produces a number, and is wrong. Nothing fails. The error surfaces in a financial
report weeks later, and reconstructing which rows are affected is archaeology.

```java
void transfer(Long fromId, Long toId, BigDecimal amount) { ... }
transfer(customerId, orderId, amount);         // transposed. Compiles fine.
```

Same shape of failure: two identifiers of the same raw type, transposed at a call site, discovered by
data corruption.

**At the fleet level: a hundred schemas that cannot be compared.**

Each service picks its own column lengths, its own audit column names, its own timestamp handling.
Then someone needs a cross-service report, or an ETL job, or to join two services' data in a warehouse
— and discovers that `reference_code` is `VARCHAR(20)` here, `VARCHAR(50)` there, and `code` somewhere
else, and half the timestamps are in local time.

### What the platform does about each

| Problem | Answer |
|---|---|
| Mixed-currency arithmetic | `Money` — same-currency `add`/`subtract` that **throw** on mismatch |
| Transposed identifiers | `EntityId<T>` — a compile error instead of corruption |
| Divergent physical schemas | `PersistenceConventions` — shared length and column-name constants |
| Provenance missing when you need it | JPA auditing populated automatically, without coupling data to security |
| `open-in-view` latency traps | Hibernate defaults at lowest precedence |
| Timezone-dependent timestamps | UTC JDBC time zone, fleet-wide |
| Unmanaged schema | **Startup fails** when JPA is configured without Flyway |

That last row is the most opinionated thing in the capability, and §2.5 defends it.

### Impact of absence

| Without this capability | What actually happens |
|---|---|
| Bare `BigDecimal` for money | Currency mismatches produce plausible wrong numbers, found at reconciliation |
| Raw `Long`/`String` ids | Argument transposition found by data corruption, not by the compiler |
| No shared conventions | Cross-service joins and ETL hit truncation surprises |
| Hand-written audit columns | Wrong, null, or missing exactly when an investigation needs them |
| `open-in-view=true` (Boot's default) | N+1 queries during view rendering; connections held far too long |
| No UTC discipline | Timestamps that shift when a server moves region |
| No migration guard | Schema drifts between environments, or `ddl-auto` quietly mutates production |

---

## 2. Core Concepts and Underlying Principles

### 2.1 Why the API module has no datastore dependency

`platform-data-api` depends on nothing but `core-api`. No JPA, no Hibernate, no JDBC.

That is what lets a domain module use `Money` without depending on a persistence framework:

```
   domain module          ->  platform-data-api        (Money, EntityId)
   persistence module     ->  platform-data-jpa-*      (entities, repositories)
   a non-JPA service      ->  platform-data-api        (Money in a REST payload, no database at all)
```

The classic anti-pattern this avoids is the **persistence leak into the domain**: a domain type that
cannot be named without Hibernate on the classpath. Once that happens, your domain module is a
persistence module, unit tests need a persistence context, and moving to a different store means
touching the domain.

The mechanism that makes it work is worth noting because it recurs: `Money` defines its own **storage
format** — `"19.99 AED"` — as part of its API contract, via `toStorageString()` and `parse(String)`.
The JPA converter implements that contract from the other side. Keeping the format in the value type
rather than in the converter is precisely what lets the API module stay persistence-free.

### 2.2 Value objects that fail loudly

```java
Money price = Money.of("19.99", "AED");
Money shipping = Money.of("5.00", "USD");
price.add(shipping);        // IllegalArgumentException — different currencies
```

The design principle: **convert a silent-corruption bug class into a test failure.**

A mixed-currency addition with bare `BigDecimal` produces a number. Nothing anywhere knows it is
meaningless. With `Money`, the same mistake throws — in a unit test, on the developer's machine,
during the sprint that introduced it.

`EntityId<T>` does the same for identifiers, but at compile time rather than runtime:

```java
EntityId<Long> orderId = EntityId.of(42L);
Long raw = orderId.value();
```

!!! success "Best practice — define per-entity id aliases, not raw `EntityId<Long>` everywhere"
    `EntityId<Long>` still lets an order id be passed where a customer id is expected — both are
    `EntityId<Long>`. The type safety comes from declaring distinct types per entity. Whether you do
    that with small records or subtypes is your call; `EntityId` gives you the wrapper, not the
    discipline.

### 2.3 Auditing without coupling data to security

Every row should record who created it and who last changed it. The obvious implementation makes the
data capability depend on the security capability — and that would violate "a capability you don't add
costs you nothing". A batch job with a database and no HTTP surface should not carry Spring Security.

The platform's answer is **two mutually exclusive bean definitions**, keyed on classpath presence:

```
   CurrentUserAccessor on the classpath?
        |
        +-- YES:  AuditorAwareProvider  ->  the authenticated subject, else "system"
        |
        +-- NO:   a plain lambda        ->  always "system"
                  (and no security type is ever loaded)
```

`@ConditionalOnClass` / `@ConditionalOnMissingClass` on the two definitions. A data-only service audits
everything as `"system"` and never loads a security class; add the security starter and the same rows
start recording real subjects, with no code change.

!!! note "This is the general pattern for optional cross-capability enrichment"
    [Audit](12-audit.md), [flags](13-ratelimit-flags.md), and
    [restclient](06-restclient-resilience.md) all do the same thing with the same seam. The dependency
    constitution allows an autoconfigure module to reference another capability's **API** when guarded
    by `@ConditionalOnClass` — and this is what that allowance is for.

### 2.4 The Hibernate defaults, and why each one

Contributed as `platform-data-jpa-defaults`, at lowest precedence, visible in `/actuator/env`.

| Default | Value | Why |
|---|---|---|
| `spring.jpa.open-in-view` | **`false`** | Boot's default is `true`. See below |
| `hibernate.jdbc.batch_size` | `50` | Batches inserts and updates into fewer round trips |
| `hibernate.order_inserts` | `true` | Groups by table so batching actually applies |
| `hibernate.order_updates` | `true` | Same, for updates |
| `hibernate.jdbc.time_zone` | `UTC` | Timestamps do not shift with server locale |

**`open-in-view` deserves its own paragraph**, because it is the one that surprises people and the one
that causes production incidents.

With it `true` — Boot's default — the `EntityManager` stays open for the whole request, including view
rendering and JSON serialisation. That means:

- **A database connection is held for the entire request**, not just the transactional part. Under
  load the connection pool becomes the bottleneck long before the database does.
- **Lazy-loading works during serialisation**, so touching `order.getItems()` in a Jackson serialiser
  silently issues a query. That is the N+1 problem, in a place nobody looks for it — and it is
  invisible in the service layer where you would profile.

With it `false`, the same code throws `LazyInitializationException`. That is louder and much better:
the failure appears in development, and it points at the exact access that should have been a fetch
join or a projection.

!!! warning "`open-in-view=false` will surface lazy-loading bugs you already had"
    Adopting this capability on an existing service can produce `LazyInitializationException`s
    immediately. Those are not new bugs — they are existing N+1 queries becoming visible. Fix them with
    a fetch join, an `@EntityGraph`, or a DTO projection. Setting it back to `true` restores the
    hidden cost.

!!! note "snake_case naming is Boot's own default, deliberately left alone"
    `PersistenceConventions.PHYSICAL_NAMING` documents `snake_case`, but the platform contributes **no**
    naming strategy — Boot's default already does this. Adding a redundant override would be a
    platform-specific thing to explain, debug, and maintain for zero behaviour change.

### 2.5 Why a missing Flyway fails startup

The most opinionated decision in this capability. JPA configured, Flyway absent → the application does
not start.

The reasoning is about *when* you find out. Without the guard, the failure modes are:

- `ddl-auto=update` quietly mutates a production schema in ways nobody reviewed, and that no
  environment can reproduce.
- `ddl-auto=none` and the schema simply does not match, surfacing as a column-not-found error during
  the first request that touches the missing column — often in production, often days later.

Both are expensive and both are discovered late. A startup failure is discovered on the developer's
machine, the first time they run the service, with a *Description / Action* block naming the fix.

The escape hatch exists and is explicit: `dc.platform.data.jpa.require-migrations=false`.

!!! success "Best practice — treat the escape hatch as a documented exception"
    Legitimate cases exist: a read-only service against a schema someone else owns, or a test fixture.
    In those cases set the property with a comment explaining why. What you should not do is set it to
    make a startup error go away.

Detection is by class name only (`org.flywaydb.core.Flyway`), so the data module needs no compile
dependency on Flyway — the starter contributes it.

---

## 3. Feature Reference

### 3.1 `platform-data-api` — public API

Package `ae.gov.dubaicustoms.platform.data`. All STABLE, all `@PlatformApi`.

| Type | Kind | Purpose |
|---|---|---|
| `Money` | record `(BigDecimal amount, Currency currency)` | Money with the currency attached |
| `EntityId<T>` | record `(T value)` | A typed identifier wrapper |
| `PersistenceConventions` | final class | Shared constants — lengths, audit column names |

#### `Money`

| Member | Signature | Notes |
|---|---|---|
| `of` | `static Money of(BigDecimal, Currency)` | |
| `of` | `static Money of(String amount, String currencyCode)` | Throws `IllegalArgumentException` on an unknown ISO code |
| `add` | `Money add(Money)` | **Throws `IllegalArgumentException` on currency mismatch** |
| `subtract` | `Money subtract(Money)` | Same |
| `toStorageString` | `String toStorageString()` | `"19.99 AED"` — the converter contract |
| `parse` | `static Money parse(String)` | The inverse |

#### `PersistenceConventions`

| Constant | Value | Use for |
|---|---|---|
| `PHYSICAL_NAMING` | `"snake_case"` | Documentation; migrations must match |
| `CODE_LENGTH` | `32` | Short codes, enum-like tokens |
| `NAME_LENGTH` | `255` | Names, titles, single-line human text |
| `SHORT_TEXT_LENGTH` | `512` | Moderate free text |
| `DESCRIPTION_LENGTH` | `2000` | Long free text |
| `CORRELATION_ID_LENGTH` | `64` | A stored correlation id |
| `MONEY_LENGTH` | `40` | A `Money` storage column |
| `CREATED_AT` / `CREATED_BY` | `"created_at"` / `"created_by"` | Audit columns |
| `LAST_MODIFIED_AT` / `LAST_MODIFIED_BY` | `"last_modified_at"` / `"last_modified_by"` | Audit columns |
| `VERSION` | `"version"` | Optimistic locking |

!!! note "These are constants, not enforcement"
    Nothing validates that your `@Column(length = 40)` matches `MONEY_LENGTH`. Reference the constants
    from both your entities **and** your Flyway migrations, and the schema stays consistent by
    construction rather than by review.

### 3.2 What the JPA autoconfigure contributes

| Contribution | Detail |
|---|---|
| `platformAuditorAware` | `AuditorAware<String>` — the authenticated subject, or `"system"` |
| JPA auditing enabled | Only once a real JPA infrastructure bean exists |
| `MoneyConverter` | `Money` ↔ `VARCHAR`, **opt-in per field** |
| `CorrelationIdConverter` | `CorrelationId` ↔ `VARCHAR`, opt-in per field |
| `platformFlywayPresenceCheck` | Fails startup when Flyway is absent and migrations are required |
| Hibernate defaults | The five in §2.4, at lowest precedence |
| `dataJpaCapabilityDescriptor` | The startup banner line |

!!! warning "The converters are **not** auto-applied"
    `@Converter` without `autoApply = true`, deliberately. A converter shipped in a library jar is not
    part of your application's scanned persistence unit, so auto-apply would not fire reliably anyway —
    and explicit `@Convert` is unambiguous at the field, which is where someone reading the entity
    needs to see it. You must write `@Convert(converter = MoneyConverter.class)` on each field.

!!! warning "The converters live in `.internal`, and that is a known rough edge"
    Their fully-qualified name is
    `ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.MoneyConverter`. By the platform's own
    [three-audience model](../overview.md), `.internal` means **no compatibility guarantee** — excluded
    from javadoc and from the japicmp binary-compatibility gate.

    So the documented way to persist `Money` requires naming a type the platform does not promise to
    keep. Nothing stops you (no conformance rule bans application imports of platform internals today),
    and in practice the converters are stable — but you are taking on that risk knowingly. §6.2 covers
    what to do about it.

### 3.3 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.data.jpa.enabled` | Boolean | `true` | Kill switch | Essentially never |
| `dc.platform.data.jpa.require-migrations` | Boolean | `true` | Fail startup when Flyway is absent | A read-only service against someone else's schema — with a comment |

Everything else is Boot's own `spring.jpa.*` and `spring.datasource.*`, which the platform seeds at
lowest precedence and never fights:

```yaml
spring:
  jpa:
    open-in-view: true          # your value wins over the platform's false
  datasource:
    url: jdbc:postgresql://db:5432/orders
```

### 3.4 Auto-configuration

| Class | Activates when | Contributes | Backs off when |
|---|---|---|---|
| `PlatformDataJpaEnvironmentPostProcessor` | `data.jpa.enabled != false` | The five Hibernate defaults as `platform-data-jpa-defaults` | Any `spring.jpa.*` key you set wins on precedence |
| `PlatformDataJpaAutoConfiguration` | `EntityManagerFactory` + `AuditorAware` on classpath, `data.jpa.enabled != false` | `platformAuditorAware`, `platformFlywayPresenceCheck`, capability descriptor, nested auditing config | You define a same-named bean, or already enable JPA auditing yourself |

Ordered `after` `HibernateJpaAutoConfiguration`, because the `EntityManagerFactory` must be registered
before the auditing configuration can decide whether to activate. The nested auditing configuration is
itself `@ConditionalOnBean` on real JPA infrastructure — so a context **without** JPA still starts
cleanly rather than failing on a dangling `@EnableJpaAuditing`.

`MissingFlywayFailureAnalyzer` turns the guard's exception into a *Description / Action* block.

### 3.5 Extension points

| Extension | How | Effect |
|---|---|---|
| Change who the auditor is | An `AuditorAware<String>` bean | Platform's backs off |
| Add your own converter | Standard `AttributeConverter` + `@Convert` | Nothing platform-specific |
| Override a Hibernate default | The standard `spring.jpa.*` key | Yours wins on precedence |
| Run without migrations | `require-migrations: false` | The guard stands down |

---

## 4. How-to Guide

### 4.1 Add the capability

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-data-jpa</artifactId>
</dependency>
```

**Add Flyway before you start**, or startup will fail — which is the point:

```xml
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>
```

### 4.2 Use the value types

```java snippet:book-08-value-types
record OrderTotal(EntityId<Long> orderId, Money net, Money tax) {

    Money gross() {
        // Throws IllegalArgumentException if net and tax are in different currencies —
        // which is exactly what you want to happen, loudly, in a test.
        return net.add(tax);
    }
}

class PricingExample {

    OrderTotal build() {
        return new OrderTotal(
                EntityId.of(42L),
                Money.of("19.99", "AED"),
                Money.of("1.00", "AED"));
    }
}
```

Note this snippet needs no persistence framework at all — that is §2.1 in practice.

### 4.3 Write an entity that follows the conventions

```java snippet:book-08-entity
@Entity
@Table(name = "orders")
class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference_code", length = PersistenceConventions.CODE_LENGTH, nullable = false)
    private String referenceCode;

    @Column(name = "customer_name", length = PersistenceConventions.NAME_LENGTH)
    private String customerName;

    // Converters are opt-in per field — they are never auto-applied. Note the package: the
    // converter lives in .internal, which carries no compatibility guarantee. See §6.2.
    @Convert(converter = ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.MoneyConverter.class)
    @Column(name = "total", length = PersistenceConventions.MONEY_LENGTH)
    private Money total;

    @CreatedDate
    @Column(name = PersistenceConventions.CREATED_AT, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = PersistenceConventions.CREATED_BY, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = PersistenceConventions.LAST_MODIFIED_AT)
    private Instant lastModifiedAt;

    @LastModifiedBy
    @Column(name = PersistenceConventions.LAST_MODIFIED_BY)
    private String lastModifiedBy;

    @Version
    @Column(name = PersistenceConventions.VERSION)
    private Long version;
}
```

!!! warning "`@EntityListeners(AuditingEntityListener.class)` is still required on the entity"
    The platform enables JPA auditing and supplies the `AuditorAware`. It cannot annotate your entity
    for you. Either add the listener per entity or — better — put it on a shared
    `@MappedSuperclass` base class carrying the four audit fields, and extend that.

The matching migration, using the same constants as documentation:

```sql
-- V1__create_orders.sql
CREATE TABLE orders (
    id                BIGSERIAL PRIMARY KEY,
    reference_code    VARCHAR(32)  NOT NULL,   -- PersistenceConventions.CODE_LENGTH
    customer_name     VARCHAR(255),            -- NAME_LENGTH
    total             VARCHAR(40),             -- MONEY_LENGTH
    created_at        TIMESTAMP    NOT NULL,
    created_by        VARCHAR(255),
    last_modified_at  TIMESTAMP,
    last_modified_by  VARCHAR(255),
    version           BIGINT       NOT NULL DEFAULT 0
);
```

### 4.4 Get auditing populated

Nothing to configure. Add [security](05-security-authz.md) and `created_by` becomes the authenticated
subject; without it, `"system"`.

!!! tip "Verify auditing before you rely on it"
    Insert a row through an authenticated request and read the audit columns. Silent nulls mean
    `@EntityListeners` is missing on the entity — the most common cause by a wide margin.

### 4.5 Override a Hibernate default

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 100
```

Your value wins. Confirm which source supplied it:

```bash
curl -s localhost:8080/actuator/env/spring.jpa.properties.hibernate.jdbc.batch_size | jq
```

### 4.6 Change the auditor

```java snippet:book-08-auditor
@Configuration
class AuditorConfiguration {

    @Bean
    AuditorAware<String> platformAuditorAware(CurrentUserAccessor accessor) {
        // Record tenant/subject rather than subject alone.
        return () -> accessor.currentUser()
                .map(user -> user.tenant() == null
                        ? user.subject()
                        : user.tenant() + "/" + user.subject());
    }
}
```

!!! warning "Watch the column length"
    `created_by` is `NAME_LENGTH` (255) by convention. A composite auditor value must fit, or inserts
    fail at runtime with a truncation error — in production, on the first write by a user with a long
    subject.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local and test | H2 or Testcontainers | The platform's guarantee is a Docker-free default build |
| All | Flyway present, `require-migrations: true` | The default. Leave it |
| All | `ddl-auto` **unset** (Boot defaults to `none` with Flyway) | Never `update` or `create-drop` outside a test |
| Production | Connection pool sized against expected concurrency | §5.4 |
| Production | `open-in-view: false` (the default) | Never override it back |

!!! warning "`spring.jpa.hibernate.ddl-auto=update` in production"
    It will alter your schema at startup, in ways nobody reviewed, differently on each environment
    depending on drift. Flyway exists so schema changes are versioned, reviewed, and repeatable. This
    is worth an explicit deployment-gate check.

### 5.2 What to monitor

| Signal | Meter | Alert when |
|---|---|---|
| Connection pool exhaustion | `hikaricp_connections_pending` | Sustained non-zero — requests are queuing for a connection |
| Connection acquisition time | `hikaricp_connections_acquire_seconds` | p99 climbing |
| Active connections | `hikaricp_connections_active` | Near the pool maximum |
| Query volume per request | Hibernate statistics, or your APM | A step change after a release usually means a new N+1 |
| Flyway migration state | Startup logs, `flyway_schema_history` | A migration fails, or checksums mismatch |
| Transaction duration | Tracing spans | Long transactions hold connections and locks |

!!! tip "`hikaricp_connections_pending` is the highest-value database alert"
    It means requests are *waiting for a connection*, which is a different and earlier signal than slow
    queries. It usually indicates either a pool sized too small, or connections held too long — often
    by a remote call inside a transaction (§6.4).

### 5.3 Troubleshooting

**Startup fails: Flyway missing.** Working as designed. Add `flyway-core`, or set
`require-migrations: false` with a comment saying why (§2.5).

**`LazyInitializationException` after adopting the platform.** `open-in-view` is now `false` and an
existing N+1 became visible. Fix it with a fetch join, `@EntityGraph`, or a projection. §2.4.

**Audit columns are null.**

| Cause | Check |
|---|---|
| `@EntityListeners(AuditingEntityListener.class)` missing | The most common cause |
| Auditing never activated | It is `@ConditionalOnBean` on real JPA infrastructure |
| A user-defined `AuditorAware` returning empty | Yours backs the platform's off |

**`created_by` is `"system"` when you expected a user.** Either the security capability is absent (the
no-security branch of §2.3), or the write happened on a background thread where there is no
authenticated request. Both are correct behaviour.

**Money round-trips wrong, or the column is too short.** `@Convert(converter = MoneyConverter.class)`
missing on the field, or the column is shorter than `MONEY_LENGTH`.

**Timestamps are off by hours.** Something overrode `hibernate.jdbc.time_zone`, or the column type is
`TIMESTAMP WITH TIME ZONE` and the driver is converting. Check `/actuator/env`.

**Batching is not happening.** Batch size is set but `order_inserts`/`order_updates` were overridden,
or you are using `GenerationType.IDENTITY` — which **disables insert batching in Hibernate**, because
each insert must round-trip to fetch the generated key. Use a sequence generator if insert batching
matters.

### 5.4 Scaling and performance

- **The connection pool is almost always the first bottleneck**, not the database. Default HikariCP
  maximum is 10. A request that holds a connection for 200 ms caps you at ~50 requests/second per
  instance, regardless of how fast the database is.
- **`open-in-view=false` shortens connection hold time**, which is a throughput improvement as well as
  a correctness one.
- **Batching helps writes, not reads.** Size 50 with ordered inserts is a good default; raising it has
  diminishing returns and increases memory per flush.
- **Virtual threads do not enlarge the pool.** A thousand virtual threads still contend for ten
  connections. See [Chapter 6](06-restclient-resilience.md) §2.4.
- **`@Version` optimistic locking costs one column and no locks.** Under contention it converts silent
  lost updates into `OptimisticLockingFailureException`, which you then have to handle — usually by
  retrying the read-modify-write.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| Audit columns are evidence | `created_by` is only as trustworthy as the authentication that produced it. See [Chapter 5](05-security-authz.md) |
| Returning entities from controllers | Serialises every column, including internal ones, and publishes them in [OpenAPI](04-openapi.md). Use a response record |
| SQL injection | Spring Data derived queries and JPQL parameters are safe. **Native queries built by string concatenation are not** |
| PII in audit columns | A subject identifier may itself be personal data under your regime |
| Migrations run as a privileged user | Flyway needs DDL rights. Consider a separate migration credential from the runtime one |
| Connection credentials | `spring.datasource.password` from a Vault-populated placeholder, never an environment variable ([Chapter 14](14-testing-dx.md)) |

!!! warning "Never build a native query by string concatenation"
    ```java
    entityManager.createNativeQuery("SELECT * FROM orders WHERE code = '" + code + "'");   // injection
    entityManager.createNativeQuery("SELECT * FROM orders WHERE code = ?1").setParameter(1, code);
    ```
    The platform cannot prevent this. It is the one classic vulnerability in this chapter that is
    entirely on you.

---

## 6. Deep Dive

### 6.1 Why the storage format lives in `Money`, not the converter

```java
public String toStorageString();      // "19.99 AED"
public static Money parse(String);
```

These are on `Money`, in the persistence-free API module, and the converter merely calls them.

Put the format in the converter instead, and two things break. The API module can no longer describe
its own persistence contract, so anyone reading `Money` cannot tell how it is stored. And a *second*
persistence mechanism — a document store, a message payload, a CSV export — would have to reinvent the
format, and the two would drift.

Defining the canonical string form on the value type means there is exactly one answer to "how is this
serialised", reusable everywhere, with no persistence dependency. It is a small decision that is
load-bearing for the module split.

### 6.2 Why converters are not auto-applied

`@Converter` supports `autoApply = true`, which would apply `MoneyConverter` to every `Money` field
automatically. The platform deliberately does not use it, for two reasons.

**It would not work reliably.** Auto-apply requires the converter to be part of the persistence unit,
which is determined by scanning. A converter in a library jar is generally not scanned by the
application's persistence unit, so auto-apply would fire in some configurations and not others — the
worst possible outcome, because it works in development and silently does not in production.

**Explicit is better here.** `@Convert(converter = MoneyConverter.class)` on the field tells the next
person reading the entity exactly how that column is stored. With auto-apply, that knowledge lives in a
jar they would have to go looking for.

The cost is one annotation per field. That is a good trade.

**But there is a rough edge here**, and it is worth naming plainly rather than leaving you to discover
it. The converters live in `…data.jpa.autoconfigure.internal`. The platform's three-audience model says
`.internal` carries no compatibility guarantee — it is excluded from javadoc and from the japicmp gate
— yet the only documented way to persist `Money` is to reference one by class literal.

The tension is structural rather than careless: the converter *must* depend on JPA, so it cannot live
in the persistence-free API module, and a public `…data.jpa` package would be a fourth home for two
classes. But the consequence is real: an application that writes `@Convert(converter =
MoneyConverter.class)` has coupled itself to a type behind the platform's internal marker.

Two pragmatic responses:

- **Write the fully-qualified name**, as §4.3 does. It compiles identically and makes the `.internal`
  segment visible to the next reader, so the coupling is a deliberate, reviewable choice rather than an
  invisible import.
- **Or declare your own converter.** It is six lines over `Money.toStorageString()` and
  `Money.parse(String)` — both STABLE public API — and it removes the coupling entirely:

```java
@Converter
public class MoneyColumnConverter implements AttributeConverter<Money, String> {
    @Override public String convertToDatabaseColumn(Money value) {
        return value == null ? null : value.toStorageString();
    }
    @Override public Money convertToEntityAttribute(String column) {
        return column == null ? null : Money.parse(column);
    }
}
```

That the second option is *this* cheap is not an accident — it is §6.1's decision paying off. The
storage format is public API precisely so anyone can implement the converter from the stable side.

!!! note "If you are on the platform team"
    The clean fix is to promote the converters to a public `…data.jpa` package, or to document
    `Money.toStorageString()`/`parse` as the intended integration point and stop pointing consumers at
    the internal type. Either resolves the contradiction between the module page's guidance and the
    compatibility promise.

### 6.3 How the two auditor definitions coexist

```java
@ConditionalOnClass(CurrentUserAccessor.class)
AuditorAware<String> platformAuditorAware(ObjectProvider<CurrentUserAccessor> accessor) { ... }

@ConditionalOnMissingClass("...CurrentUserAccessor")
AuditorAware<String> platformAuditorAware() { return () -> Optional.of("system"); }
```

Two bean methods, the same name, mutually exclusive conditions. Exactly one is ever registered.

The subtle part is *why the class-level condition is necessary rather than just checking for the bean*.
`AuditorAwareProvider`'s constructor signature references `CurrentUserAccessor`. Loading that class at
all — even to evaluate a condition — would fail with `NoClassDefFoundError` if the security API is
absent. `@ConditionalOnClass` is evaluated from the bytecode without loading the method's parameter
types, which is what makes the guard safe.

This is the general reason `@ConditionalOnClass` exists and why `@ConditionalOnBean` is not a
substitute for it.

### 6.4 The transaction-holding-a-connection trap

The most common cause of connection-pool exhaustion in a service like this:

```java
@Transactional
void process(String orderId) {
    Order order = repository.findById(orderId).orElseThrow();
    String status = paymentClient.check(orderId);      // remote call, up to 10s
    order.setStatus(status);
}
```

The transaction — and its connection — is held for the entire remote call. Ten concurrent requests
exhaust a default pool of ten, and the eleventh queues. The database is idle throughout.

```java
// Read in a transaction, call outside it, write in a second transaction.
Order order = orderService.load(orderId);              // @Transactional(readOnly = true)
String status = paymentClient.check(orderId);          // no transaction held
orderService.updateStatus(orderId, status);            // @Transactional
```

!!! warning "Never make a remote call inside a transaction"
    This is the rule from [Chapter 6](06-restclient-resilience.md) §2.4, restated here because this is
    where the resource is actually held. It also applies to publishing a message
    ([Chapter 7](07-messaging-events.md)), acquiring a distributed lock
    ([Chapter 10](10-coordination.md)), and writing to object storage
    ([Chapter 11](11-storage-files.md)) — anything that can block for an unbounded time.

### 6.5 Why the nested auditing configuration is conditional on a bean

`@EnableJpaAuditing` on a context with no JPA infrastructure fails at startup. But the data
autoconfigure activates on `@ConditionalOnClass(EntityManagerFactory.class)` — and the class being
present does not mean an `EntityManagerFactory` was actually *created*. A test slice, or a
misconfigured datasource, gives you the class without the bean.

So auditing lives in a nested configuration that is `@ConditionalOnBean` on real JPA infrastructure,
and the whole autoconfigure is ordered `after` `HibernateJpaAutoConfiguration` so that bean exists by
the time the condition is evaluated. The result is that a context without JPA starts cleanly instead of
failing on a dangling annotation.

This is the same `@ConditionalOnBean`-between-auto-configurations fragility as
[Chapter 5](05-security-authz.md) §6.3, solved the same way: explicit ordering.

### 6.6 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Remote call inside `@Transactional` | It reads naturally | Holds a connection for the call's duration. §6.4 |
| Forgetting `@EntityListeners` | The platform enables auditing, so it feels done | Audit columns stay null. Use a `@MappedSuperclass` base |
| Setting `open-in-view: true` to fix an exception | It makes the error go away | It hides an N+1, it does not fix it |
| `ddl-auto: update` in production | It is convenient in development | Unreviewed schema mutation |
| Bare `BigDecimal` for money | Fewer types | Currency mismatch produces a plausible wrong number |
| `EntityId<Long>` everywhere | It looks type-safe | Two `EntityId<Long>` are still interchangeable |
| Forgetting `@Convert` on a `Money` field | Converters look automatic | They are opt-in, deliberately |
| Importing `MoneyConverter` without noticing `.internal` | An IDE import hides the package | Fully-qualify it, or write your own over `toStorageString`/`parse`. §6.2 |
| Column shorter than the convention | Hand-written DDL | Reference the constants in migrations too |
| Returning entities from controllers | Less mapping code | Publishes every column, couples the API to the schema |
| Native query by concatenation | Quick | SQL injection. The one thing the platform cannot guard |
| `IDENTITY` generation with batching expectations | It is the common default | Disables insert batching in Hibernate. Use a sequence |
| Composite auditor exceeding the column | It worked in testing with short subjects | Truncation error in production |

---

## 7. Exercises and Hands-on Labs

Starting point: [`examples/example-golden-path`](../../examples.md), which has JPA over H2 with
auditing and Flyway wired.

### Lab 1 — Basic: value types and auditing

**Goal.** Make the value types fail on purpose, and prove auditing works.

**Steps.**

1. Write a test that adds `Money.of("10.00", "AED")` to `Money.of("5.00", "USD")`. What happens?
2. Do the same with two bare `BigDecimal`s. What happens?
3. Build an entity with the four audit fields and `@EntityListeners(AuditingEntityListener.class)`.
   Save a row through an **authenticated** request. Read `created_by`.
4. Save a row from a plain `@SpringBootTest` with no authentication. Read `created_by` again.
5. Remove `@EntityListeners` and repeat step 3.

**Expected outcome.** Step 1 throws; step 2 produces `15.00`, which is meaningless and silent — put the
two outcomes side by side, because that contrast is the entire argument for the type. Step 3 gives the
subject, step 4 gives `"system"`, step 5 gives `null`.

**Hints.**

- For step 3, `.with(jwt())` from `spring-security-test` — see [Chapter 5](05-security-authz.md) §4.6.
- Step 5's null is the most common real-world bug in this chapter.

**How to verify.** A test asserting `assertThatThrownBy(() -> aed.add(usd))` and one asserting
`created_by` equals the JWT subject.

### Lab 2 — Intermediate: make an N+1 visible, then fix it

**Goal.** Understand what `open-in-view=false` is protecting you from.

**Steps.**

1. Build `Order` with a `@OneToMany` lazy collection of items. Save a few orders with items.
2. Write a controller returning the orders **directly as entities**. Call it. What happens, and where
   does the stack trace point?
3. Set `spring.jpa.open-in-view: true` and call again. Does it work now?
4. Enable Hibernate statistics or SQL logging. Count the queries for 10 orders.
5. Set `open-in-view` back to `false`. Fix it properly three ways: a fetch join, `@EntityGraph`, and a
   DTO projection. Count queries for each.
6. Write down which fix you would want in this codebase and why.

**Expected outcome.** Step 2 throws `LazyInitializationException` during serialisation. Step 3 "works"
and issues **11 queries for 10 orders** — the N+1, now invisible. Step 5's fixes each give 1–2 queries.
The lesson is that step 3 did not fix anything; it hid something.

**Hints.**

- `logging.level.org.hibernate.SQL=DEBUG` to see the queries.
- The DTO projection is usually the right answer, because it also stops you publishing internal columns
  through [OpenAPI](04-openapi.md).

**How to verify.** A test asserting the query count for the fixed endpoint is bounded and does not grow
with the number of orders.

### Lab 3 — Advanced: exhaust a connection pool, then design it away

**Goal.** Reproduce §6.4 and understand what the pool metric is telling you.

**Steps.**

1. Set `spring.datasource.hikari.maximum-pool-size: 5`.
2. Write a `@Transactional` method that loads an entity, calls a stub sleeping 2 seconds, then writes.
3. Fire 10 concurrent requests. Time them. Watch `hikaricp_connections_pending` and
   `hikaricp_connections_active`.
4. Note that the database was idle the whole time. Why did requests queue?
5. Restructure: read in a `readOnly` transaction, call outside any transaction, write in a second
   transaction. Repeat step 3.
6. Compare total time and peak `pending`. Explain the difference in one sentence.
7. Now add `@Version` to the entity and run two concurrent updates to the same row. What exception, and
   what would you do about it in production?
8. Add a Flyway migration that fails. Observe startup. Then fix it and confirm `flyway_schema_history`.

**Expected outcome.** Step 3 serialises into two waves of five with `pending` climbing. Step 5 keeps
`pending` at zero and completes in roughly the sleep duration. Step 7 gives
`OptimisticLockingFailureException` — and the production answer is retry the whole read-modify-write,
not swallow it.

**Hints.**

- `curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending | jq`
- Step 4's answer is the one-sentence version of §6.4, and worth writing down in your own words.

**How to verify.** A test asserting the restructured version completes within roughly one sleep
duration under concurrency, while the original takes at least two.

---

## 8. Checklist / Quick Reference

**Add it — with Flyway**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-data-jpa</artifactId>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>
```

**Value types**

```java
Money.of("19.99", "AED").add(other);     // throws on currency mismatch
Money.parse("19.99 AED");                // the storage form
EntityId.of(42L).value();
PersistenceConventions.CODE_LENGTH;      // 32 · NAME_LENGTH 255 · MONEY_LENGTH 40
```

**Entity checklist**

- `@EntityListeners(AuditingEntityListener.class)` — **or auditing silently does nothing**
- `@CreatedDate` `@CreatedBy` `@LastModifiedDate` `@LastModifiedBy`, named per `PersistenceConventions`
- `@Convert(...)` on every `Money` field — never automatic, and the shipped converter is `.internal` (§6.2)
- `@Version` for optimistic locking
- Column lengths from the constants, matched in the migration

**Properties**

| Key | Default |
|---|---|
| `dc.platform.data.jpa.enabled` | `true` |
| `dc.platform.data.jpa.require-migrations` | `true` |

**Platform Hibernate defaults** (lowest precedence, all overridable)

| Key | Value |
|---|---|
| `spring.jpa.open-in-view` | **`false`** (Boot's default is `true`) |
| `hibernate.jdbc.batch_size` | `50` |
| `hibernate.order_inserts` / `order_updates` | `true` |
| `hibernate.jdbc.time_zone` | `UTC` |

**Diagnose it**

```bash
curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending | jq
curl -s localhost:8080/actuator/env/spring.jpa.open-in-view | jq
curl -s localhost:8080/actuator/health/readiness | jq          # db is a member
# logging.level.org.hibernate.SQL=DEBUG   to count queries
```

**Rules of thumb**

- **Never make a remote call inside a transaction.** Connections are the scarce resource.
- `open-in-view: false` is protection, not an obstacle. A `LazyInitializationException` is a found bug.
- `@EntityListeners` on the entity, or a `@MappedSuperclass` base. Auditing cannot do it for you.
- Converters are opt-in per field, deliberately.
- Use the length constants in entities **and** migrations.
- Never `ddl-auto: update` outside a test. That is what the Flyway guard is defending.
- Never build a native query by concatenation.
- Do not return entities from controllers.
- `IDENTITY` generation disables insert batching.
- `hikaricp_connections_pending` is your earliest database alert.

---

**Next:** [Chapter 9 — Caching and Redis](09-cache-redis.md), which is what you reach for when the
queries in this chapter are the bottleneck.

**Reference:** [modules/data.md](../../modules/data.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
