# Chapter 8 — Data and Persistence

> **Capabilities covered:** `data`
>
> Spring Data JPA and Flyway, with waits that end, errors that leak nothing, and Oracle defaults that hold.
>
> **Starters:** `platform-starter-data-jpa`, `platform-starter-data-jpa-oracle` · **Reference:**
> [modules/data.md](../../modules/data.md) · **Lab:** [Data](../../labs/data/index.md)

---

## 1. Introduction and Business Value

You write a data service the way Spring teaches it: entities, `JpaRepository`, `@Transactional`, Flyway migrations.
Nothing in your code names the platform. What the platform adds is the part every team gets wrong once, in
production:

- defaults that bound every wait for the database;
- error responses that never carry SQL;
- a migration posture that cannot be loosened by accident;
- the Oracle driver settings that Spring Boot leaves to you.

### The problem it solves

A database that slows down does not usually take a service down by failing. It takes it down by making it **wait**.

- Hikari's default wait for a connection is 30 seconds. A slow database fills the pool, and every new request waits
  the full 30 seconds holding a thread, so the threads pile up behind the pool.
- A statement with no timeout and a database that stops answering will wait forever. The driver's socket has no
  read timeout by default.
- A unique-key violation becomes a generic 500, and its message (with table, constraint and column names) ends up in
  a log line or, worse, a response body.
- A migration edited after it ran, or a file named `V2_add_column.sql` with one underscore, is silently skipped or
  fails differently on each environment.

### What the platform does about each

| Problem | Answer |
|---|---|
| Request threads pile up behind an exhausted pool | 5 s connection wait, then a 503 `DC-DATA-0503` |
| A statement or a dead network waits forever | 30 s transaction and query timeouts; on Oracle a 60 s driver read timeout |
| Data errors leak SQL | 409 or 503 problems with a fixed detail; the full exception is logged once on the trace |
| Schema drift | Flyway runs at startup, its safe settings pinned; startup fails without it |
| `open-in-view` hides N+1 queries | Off by default |
| Timestamps move with the server | UTC JDBC time zone |
| Provenance | Auditing on, as `"system"` until a service supplies its own auditor |
| Logging an entity runs SQL | The log masker writes `[entity Order]` and never touches it |

### Impact of absence

| Without this capability | What actually happens |
|---|---|
| Hikari's 30 s wait | A slow database turns into a thread pile-up and an outage of the whole service |
| No statement or read timeout | One hung statement holds a connection until someone restarts the pod |
| Raw data exceptions | Table and constraint names in responses; a generic 500 the client cannot act on |
| Flyway on its defaults | An upgrade or a stray property can loosen validation or allow `clean` |
| `open-in-view=true` (Boot's default) | Lazy loading during JSON serialisation; connections held for the whole request |

---

## 2. Core Concepts and Underlying Principles

### 2.1 The platform adds defaults, not APIs

The data capability has no Java API. Everything it does reaches your service as one of:

- a **property default**, in a property source named `platform-data-jpa-defaults` at the lowest precedence, so any
  value you set wins;
- a **bean of a Spring type**, the auditor (`AuditorAware<String>`), which you can replace;
- a **startup check**: Flyway present, out-of-order migrations refused outside `local`;
- a **bean post-processor** that adds the Oracle driver's timeouts to a pool whose URL is `jdbc:oracle:`.

So a service is portable: remove the starter and it is a plain Spring Boot JPA service with Boot's defaults.

### 2.2 Bounded waits

Every way a request can wait on the database has a bound:

| Wait | Bound | Key |
|---|---|---|
| For a pooled connection | 5 s | `spring.datasource.hikari.connection-timeout` |
| A transaction, repository methods included | 30 s | `spring.transaction.default-timeout` |
| A query outside a transaction | 30 s | `spring.jpa.properties.jakarta.persistence.query.timeout`, `spring.jdbc.template.query-timeout` |
| The database's answer, on Oracle | 60 s; 10 min on Flyway's own connection | `dc.platform.data.jpa.oracle.read-timeout`, `…flyway-read-timeout` |
| A TCP connect, on Oracle | 10 s | `oracle.net.CONNECT_TIMEOUT` (driver property) |

The layers matter. A transaction's remaining time is given to each statement, and the driver cancels the statement
when it runs out. A cancel cannot stop everything: a lock wait inside PL/SQL, or a network that has gone silent, does
not answer a cancel. The read timeout is the backstop for those. It sits above the transaction timeout, so a slow
statement meets the transaction timeout first. Raise the read timeout for a service whose transactions are longer.

!!! warning "Two query paths ignore the transaction's remaining time"
    Hibernate's `Session.createSelectionQuery(…)` and a query on the raw `getDelegate()` take the 30 s query default
    instead. Inside a shorter transaction they can outlive it; inside a longer one they are cut short. Set
    `.setTimeout(seconds)` on those queries yourself. The [module page](../../modules/data.md) lists every path.

### 2.3 Data errors are problems

The errors capability maps Spring's data exceptions to RFC 9457 problems with a **fixed** detail:

| What happened | Status | Code | Retryable |
|---|---|---|---|
| Unique, foreign-key, NOT NULL or check violation | 409 | `DC-DATA-0409` | no |
| Concurrent update or a lock held elsewhere | 409 | `DC-DATA-0410` | no: retry the whole read-modify-write |
| Database unavailable, a timeout, an exhausted pool | 503 | `DC-DATA-0503` | yes |
| Connection lost while **committing** | 500 | `DC-CORE-0500` | no: the outcome is unknown |

The body never names SQL, tables or constraints. The platform logs the full exception once, at WARN, on the request's
trace. Hibernate writes two WARN lines of its own just before (logger `org.hibernate.orm.jdbc.error`); they stay on
because they are the only record when your code catches the exception.

### 2.4 The Hibernate defaults, and why each one

| Default | Value | Why |
|---|---|---|
| `spring.jpa.open-in-view` | **`false`** | Boot's default is `true`. See below |
| `hibernate.jdbc.batch_size` | `50` | Batches inserts and updates into fewer round trips |
| `hibernate.order_inserts`, `order_updates` | `true` | Groups by table so batching actually applies |
| `hibernate.jdbc.time_zone` | `UTC` | Timestamps do not shift with the server's zone |

**`open-in-view` deserves its own paragraph.** With it `true`, the `EntityManager` stays open for the whole request,
including JSON serialisation. A database connection is held for the entire request, not just the transactional part,
and lazy loading works during serialisation, so touching `order.getItems()` in a serialiser silently issues a query.
That is the N+1 problem, in a place nobody profiles.

With it `false`, the same code throws `LazyInitializationException`. That is louder and much better: the failure
appears in development and points at the access that should have been a fetch join or a projection.

!!! warning "`open-in-view=false` will surface lazy-loading bugs you already had"
    They are existing N+1 queries becoming visible. Fix them with a fetch join, an `@EntityGraph` or a DTO projection.

snake_case physical naming is Boot's own default; the platform leaves it alone.

### 2.5 Migrations: Flyway, required and pinned

JPA configured, Flyway absent: the application does not start. The alternative is finding out late, when
`ddl-auto=update` has mutated a production schema nobody reviewed, or when a column is missing on the first request
that needs it. A startup failure is found on a developer's machine, with a *Description / Action* block naming the fix.
The escape hatch is explicit: `dc.platform.data.jpa.require-migrations=false`, for a read-only service against a schema
someone else owns.

Flyway's safe settings are pinned rather than inherited, so an upgrade cannot loosen them:

| Setting | Value | Effect |
|---|---|---|
| `clean-disabled` | `true` | No migration run can wipe a database |
| `validate-on-migrate` | `true` | An edited, applied migration blocks startup (checksum) |
| `out-of-order` | `false` | Outside `local`, turning it on by any route fails startup |
| `validate-migration-naming` | `true` | `V2_add_column.sql` (one underscore) fails instead of being skipped |

The starter bundles `flyway-core` **and** `spring-boot-flyway`. Boot 4 runs Flyway from the second; the engine alone is
something nobody starts.

### 2.6 Auditing

`@CreatedDate`, `@CreatedBy`, `@LastModifiedDate` and `@LastModifiedBy` are filled in automatically. In 1.0.0 the
platform's auditor records `"system"`: there is no web security to name a caller. Declare your own `AuditorAware`
bean, under any name, and it replaces the platform's (§6.3 explains why "any name" matters). With several, mark the
one to use `@Primary`.

### 2.7 Logging an entity

A log line that names an entity is a database call waiting to happen: its proxy loads on `toString()`, its lazy
collections load when iterated, and a computed getter can load anything. The platform's log masker therefore never
touches a JPA value:

| Logged value | Written as |
|---|---|
| An `@Entity` instance or a Hibernate proxy | `[entity Order]` |
| A Hibernate collection that is not loaded | `[not loaded]` |
| A Spring Data projection (a JDK proxy) | `[proxy OrderView]` |

Log the identifier or the values you need instead: `log.atInfo().addKeyValue("orderId", order.getId())`. As a message
argument (`log.info("saved {}", order)`) an entity can still load, because Logback renders arguments itself before the
platform sees them. The [logging page](../../modules/logging.md) has the full rule.

---

## 3. Feature Reference

### 3.1 What the auto-configuration contributes

| Bean | Type | Replace it by |
|---|---|---|
| `platformAuditorAware` | `AuditorAware<String>`, records `"system"` | any `AuditorAware` bean of yours |
| `platformFlywayPresenceCheck` | startup check | a bean of the same type |
| `platformFlywayOutOfOrderGuard` | startup check | a bean of the same name |
| `platformOracleConnectionDefaults` | bean post-processor | a bean of the same name |
| `platformOracleFlywayConnectionDefaults` | bean post-processor | a bean of the same name |
| `dataJpaCapabilityDescriptor` | the startup report line | a bean of the same name |

JPA auditing is enabled once a real `EntityManagerFactory` exists, and not at all when your service enables auditing
itself.

### 3.2 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.data.jpa.enabled` | `true` | Kill switch for the whole capability |
| `dc.platform.data.jpa.require-migrations` | `true` | Fail startup when Flyway is absent |
| `dc.platform.data.jpa.oracle.read-timeout` | `60s` | The Oracle driver's read timeout on the pool |
| `dc.platform.data.jpa.oracle.flyway-read-timeout` | `10m` | The same on Flyway's own connection |

Everything else is Boot's own `spring.jpa.*`, `spring.datasource.*`, `spring.transaction.*` and `spring.flyway.*`,
which the platform seeds at lowest precedence and never fights.

### 3.3 Auto-configuration

| Class | Activates when | Backs off when |
|---|---|---|
| `PlatformDataJpaEnvironmentPostProcessor` | `dc.platform.data.jpa.enabled` is not `false` | any key you set wins on precedence |
| `PlatformDataJpaAutoConfiguration` | `EntityManagerFactory` and `AuditorAware` on the classpath, capability enabled | you define the bean (§3.1), or enable JPA auditing yourself |

It is ordered after Boot's `HibernateJpaAutoConfiguration`, so the auditing configuration can see whether a real
`EntityManagerFactory` was created.

---

## 4. How-to Guide

### 4.1 Add the capability

On Oracle, which is what production runs:

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-data-jpa-oracle</artifactId>
</dependency>
```

It brings the JDBC driver, Flyway and `flyway-database-oracle` (Flyway 10+ refuses a database without its module).
Without Oracle, `platform-starter-data-jpa` is the same minus the driver. A new service gets either from the archetype:
`-Dfeatures=data -Ddatabase=oracle`. H2 backs local runs and tests in both cases.

### 4.2 Write an entity

```java snippet:book-08-entity
@Entity
@Table(name = "orders")
class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference_code", length = 64, nullable = false)
    private String referenceCode;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "total_amount", precision = 19, scale = 4)
    private java.math.BigDecimal totalAmount;

    @Column(name = "total_currency", length = 3)
    private String totalCurrency;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "last_modified_at")
    private Instant lastModifiedAt;

    @LastModifiedBy
    @Column(name = "last_modified_by")
    private String lastModifiedBy;

    @Version
    @Column(name = "version")
    private Long version;
}
```

An amount and its currency are two columns: a `BigDecimal` and an ISO code. Keep them together in a small record of
your own if your domain needs arithmetic that refuses mixed currencies.

!!! warning "`@EntityListeners(AuditingEntityListener.class)` is still required"
    The platform enables auditing and supplies the auditor. It cannot annotate your entity. Put the listener on each
    audited entity, or on a shared `@MappedSuperclass` carrying the four audit fields.

The matching migration, written for Oracle 19c (`src/main/resources/db/migration/V1__create_orders.sql`):

```sql
CREATE TABLE orders (
    id                NUMBER(19) GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    reference_code    VARCHAR2(64)  NOT NULL,
    customer_name     VARCHAR2(200),
    total_amount      NUMBER(19, 4),
    total_currency    VARCHAR2(3),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by        VARCHAR2(255),
    last_modified_at  TIMESTAMP WITH TIME ZONE,
    last_modified_by  VARCHAR2(255),
    version           NUMBER(19) DEFAULT 0 NOT NULL
);
```

`Instant` maps to `TIMESTAMP WITH TIME ZONE`: on a plain `TIMESTAMP` column Hibernate's read fails with ORA-18716.
19c has no SQL `BOOLEAN`, so a `boolean` field is a `NUMBER(1)`.

### 4.3 Record who changed a row

```java snippet:book-08-auditor
@Configuration
class AuditorConfiguration {

    // Any bean name: the platform's "system" auditor steps back for it.
    @Bean
    AuditorAware<String> callerAuditor() {
        // Until the REST API capability (iteration 2) brings a principal, read your own identity source here,
        // for example a header your gateway sets.
        return () -> Optional.of("orders-service");
    }
}
```

!!! warning "Watch the column length"
    A composite auditor value must fit `created_by`, or inserts fail on the first write by a caller with a long name.

### 4.4 Override a default

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 100
  transaction:
    default-timeout: 60s
```

Your value wins. To see which source supplied a value, expose `env` on the management port where you investigate
(`management.endpoints.web.exposure.include=health,info,prometheus,env`), then:

```bash
curl -s localhost:8081/actuator/env/spring.transaction.default-timeout | jq
```

### 4.5 Separate the schema owner from the runtime user

In production, Flyway connects as the user that owns the tables and the application as a user that can only read and
write rows:

```yaml
spring:
  flyway:
    user: APP_OWNER
    password: ${APP_OWNER_PASSWORD}
  datasource:
    username: APP_RUNTIME
    password: ${APP_RUNTIME_PASSWORD}
    hikari:
      connection-init-sql: ALTER SESSION SET CURRENT_SCHEMA=APP_OWNER
```

The runtime user holds `create session` and DML grants only. Grants for a table go in the migration that creates it,
so a deploy never needs a manual step. The [module page](../../modules/data.md) has the grants.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local and test | H2, or Oracle Free with `-Pdocker` | The default build needs no Docker |
| All | Flyway present, `require-migrations: true` | The default. Leave it |
| All | `ddl-auto` unset | Boot uses `none` with Flyway. Never `update` outside a test |
| Production | Separate schema owner and runtime user | §4.5 |
| Production | Pool sized against expected concurrency | §5.4 |
| Long batch work | `read-timeout` above the longest statement | §2.2 |

### 5.2 What to monitor

| Signal | Meter | Alert when |
|---|---|---|
| Requests waiting for a connection | `hikaricp_connections_pending` | Sustained non-zero |
| Connection timeouts | `hikaricp_connections_timeout_total` | Rising: requests are getting 503s |
| Connection acquisition time | `hikaricp_connections_acquire_seconds` | p99 climbing |
| Database errors | problems with `code` `DC-DATA-0503` | Any sustained rate |
| Shared database health | `/actuator/health/dependencies` on 8081 | `DOWN` |

`hikaricp_connections_pending` is the earliest database alert: requests are waiting for a connection before any query
is slow. Readiness never includes the database, so one database outage does not mark every replica unready at once;
the `dependencies` group shows it instead.

### 5.3 Troubleshooting

**Startup fails: Flyway missing.** Working as designed. Use the starter, which brings Flyway, or set
`require-migrations: false` with a comment saying why.

**Startup fails: a migration was edited, misnamed or out of order.** Never edit an applied migration; add
`V<next>__…`. Name files `V<version>__<description>.sql`, two underscores.

**`LazyInitializationException` after adopting the platform.** `open-in-view` is now `false` and an existing N+1
became visible (§2.4).

**503 `DC-DATA-0503` under load, database idle.** The pool is exhausted: connections are held too long, often by a
remote call inside a transaction (§6.1).

**A long statement fails after 60 s on Oracle.** The driver's read timeout. Raise
`dc.platform.data.jpa.oracle.read-timeout` above that statement.

**Audit columns are null.** `@EntityListeners(AuditingEntityListener.class)` is missing on the entity, by a wide
margin the most common cause.

**`created_by` is `"system"`.** Your auditor is not a bean, or two auditors exist and neither is `@Primary` (startup
then fails, naming both).

**A log line shows `[entity Order]`.** That is the masker refusing to load it (§2.7). Log the identifier.

**Batching is not happening.** `GenerationType.IDENTITY` disables insert batching in Hibernate, because each insert
must fetch its key. Use a sequence where insert batching matters; its `allocationSize` must equal the sequence's
`INCREMENT BY`.

### 5.4 Scaling and performance

- **The connection pool is almost always the first bottleneck.** Hikari's default maximum is 10. A request that holds a
  connection for 200 ms caps an instance at about 50 requests a second, however fast the database is.
- **`open-in-view=false` shortens connection hold time**, a throughput improvement as well as a correctness one.
- **Batching helps writes, not reads.** Size 50 with ordered inserts is a good default.
- **Virtual threads do not enlarge the pool.** A thousand virtual threads still contend for ten connections.
- **`@Version` costs one column and no locks.** Under contention it turns a silent lost update into a 409
  `DC-DATA-0410`, which the client retries with a fresh read.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| Error bodies | Fixed details only; SQL and names stay in the WARN log line |
| Returning entities from controllers | Publishes every column through [OpenAPI](04-openapi.md). Return a record |
| SQL injection | Derived queries and JPQL parameters are safe. **A native query built by concatenation is not** |
| Migrations | Run as the schema owner; the runtime user cannot `ALTER` or `DROP` |
| Credentials | `spring.datasource.password` and `spring.flyway.password` from the deployment's secret store, never from a file in the repository |
| Logs | An entity is never rendered by the masker (§2.7) |

!!! warning "Never build a native query by string concatenation"
    ```java
    entityManager.createNativeQuery("SELECT * FROM orders WHERE code = '" + code + "'");   // injection
    entityManager.createNativeQuery("SELECT * FROM orders WHERE code = ?1").setParameter(1, code);
    ```
    The platform cannot prevent this one.

---

## 6. Deep Dive

### 6.1 The transaction-holding-a-connection trap

```java
@Transactional
void process(String orderId) {
    Order order = repository.findById(orderId).orElseThrow();
    String status = paymentClient.check(orderId);      // remote call, up to 10 s
    order.setStatus(status);
}
```

The transaction, and its connection, is held for the entire remote call. Ten concurrent requests exhaust a pool of
ten; the eleventh waits 5 s and gets a 503. The database is idle throughout.

```java
// Read in a transaction, call outside it, write in a second transaction.
Order order = orderService.load(orderId);              // @Transactional(readOnly = true)
String status = paymentClient.check(orderId);          // no transaction held
orderService.updateStatus(orderId, status);            // @Transactional
```

!!! warning "Never make a remote call inside a transaction"
    It applies to anything that can block: an HTTP call, publishing a message ([Chapter 7](07-messaging-events.md)),
    waiting on a lock.

### 6.2 Why the driver timeouts go on the pool, not the environment

The Oracle read and connect timeouts are driver properties, and the obvious place for them is a property default. It
does not work. The datasource URL can arrive from a property source added after the platform's post-processor ran: a
test's `@DynamicPropertySource`, or a config server. A default keyed on the environment misses those. The platform
adds the timeouts to the pool bean itself, keyed on the pool's own URL, just before the pool starts. A pool you start
yourself (`new HikariDataSource(config)`) is sealed, so set both properties on its config.

### 6.3 Why the auditor is taken by type

Spring Data can be told which auditor to use by bean name (`@EnableJpaAuditing(auditorAwareRef = "…")`), or left to
find it by type. Naming it looks precise, and it is a trap. A service that declares `AuditorAware<String> callerAuditor`
ends up with two auditors: the platform's, still named, and its own, ignored. Every write is audited as `"system"`,
silently.

Taken by type, a service's auditor of any name replaces the platform's, which steps back, and Spring's usual rules
apply: two auditors need a `@Primary`, or startup fails naming both.

### 6.4 Why the nested auditing configuration is conditional on a bean

`@EnableJpaAuditing` on a context with no JPA infrastructure fails at startup. The capability activates when
`EntityManagerFactory` is on the **classpath**, and that does not mean one was created: a test slice or a
misconfigured datasource gives you the class without the bean. So auditing lives in a nested configuration that is
`@ConditionalOnBean` on a real `EntityManagerFactory`, and the auto-configuration is ordered after Boot's Hibernate
one, so the bean exists by the time the condition is evaluated.

### 6.5 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Remote call inside `@Transactional` | It reads naturally | Holds a connection for the call (§6.1) |
| Forgetting `@EntityListeners` | Auditing feels automatic | Use a `@MappedSuperclass` base |
| `open-in-view: true` to make an exception go away | It works | It hides an N+1; it does not fix it |
| `ddl-auto: update` in production | Convenient in development | Unreviewed schema changes |
| Editing an applied migration | A quick fix | Startup fails on the checksum; add a new migration |
| `Instant` on a plain `TIMESTAMP` column on Oracle | It works on H2 | ORA-18716 on read; use `TIMESTAMP WITH TIME ZONE` |
| `BOOLEAN` in a migration | 23ai accepts it | 19c does not; use `NUMBER(1)` |
| Logging an entity as a message argument | `log.info("saved {}", order)` reads well | Logback may load it; log the identifier |
| Returning entities from controllers | Less mapping code | Publishes every column, couples the API to the schema |
| A raised transaction timeout without a raised read timeout | The transaction is the obvious knob | The 60 s read timeout still ends each statement |

---

## 7. Exercises and Hands-on Labs

The guided lab [Data](../../labs/data/index.md) is run end to end by a script. It builds an entity, a repository and a
migration on H2, switches the same project to Oracle Free, and stops the database mid-request to read the problem and
the health group. The two exercises below go further on your own.

### Exercise 1 — Make an N+1 visible, then fix it

**Goal.** Understand what `open-in-view=false` protects you from.

**Steps.**

1. Build `Order` with a lazy `@OneToMany` collection of items. Save a few orders with items.
2. Return the orders **directly as entities** from a controller. Call it. Where does the stack trace point?
3. Set `spring.jpa.open-in-view: true` and call again.
4. Turn on SQL logging. Count the queries for 10 orders.
5. Set `open-in-view` back to `false`. Fix it three ways: a fetch join, `@EntityGraph` and a DTO projection. Count the
   queries for each.

**Expected outcome.** Step 2 throws `LazyInitializationException` during serialisation. Step 3 "works" and issues
**11 queries for 10 orders**: the N+1, now invisible. Each fix in step 5 gives one or two queries.

**Hints.** `logging.level.org.hibernate.SQL=DEBUG` shows the queries. The DTO projection is usually right: it also stops
you publishing internal columns.

### Exercise 2 — Exhaust a pool, then design it away

**Goal.** Reproduce §6.1 and read what the pool metric tells you.

**Steps.**

1. Set `spring.datasource.hikari.maximum-pool-size: 5`.
2. Write a `@Transactional` method that loads an entity, calls a stub sleeping 8 seconds, then writes.
3. Fire 10 concurrent requests. Read the status codes and `hikaricp_connections_pending`.
4. Restructure: read in a read-only transaction, call outside any transaction, write in a second transaction. Repeat.
5. Add `@Version` and run two concurrent updates of the same row. What does the client get?

**Expected outcome.** In step 3, five requests succeed and five get a 503 `DC-DATA-0503` after the 5 s connection wait.
After step 4 all ten succeed in about the sleep time. Step 5 gives a 409 `DC-DATA-0410`: the answer in production is to
retry the whole read-modify-write.

**Hints.** `curl -s localhost:8081/actuator/prometheus | grep hikaricp_connections_pending`.

---

## 8. Checklist / Quick Reference

**Add it**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-data-jpa-oracle</artifactId>
</dependency>
```

**Entity checklist**

- `@EntityListeners(AuditingEntityListener.class)`, or auditing silently does nothing
- `@CreatedDate` `@CreatedBy` `@LastModifiedDate` `@LastModifiedBy`
- `@Version` for optimistic locking
- `Instant` on `TIMESTAMP WITH TIME ZONE`; `boolean` on `NUMBER(1)`

**Platform defaults** (lowest precedence, all overridable)

| Key | Value |
|---|---|
| `spring.jpa.open-in-view` | **`false`** |
| `hibernate.jdbc.batch_size` | `50`, inserts and updates ordered |
| `hibernate.jdbc.time_zone` | `UTC` |
| `spring.datasource.hikari.connection-timeout` | `5000` |
| `spring.transaction.default-timeout` | `30s` |
| query timeouts (JPA, `JdbcTemplate`) | `30s` |
| Oracle read timeout | `60s`; `10m` on Flyway's own connection |
| Flyway | `clean-disabled`, `validate-on-migrate`, no out-of-order, naming validated |

**Diagnose it** (management port 8081; expose `env` or `metrics` where you investigate)

```bash
curl -s localhost:8081/actuator/health/dependencies | jq       # the database lives here, not in readiness
curl -s localhost:8081/actuator/prometheus | grep hikaricp_connections
curl -s localhost:8081/actuator/env/spring.jpa.open-in-view | jq
```

**Rules of thumb**

- Never make a remote call inside a transaction.
- A `LazyInitializationException` is a found bug, not an obstacle.
- Never edit an applied migration; never `ddl-auto: update` outside a test.
- Log an entity's identifier, not the entity.
- Never build a native query by concatenation, and never return entities from controllers.
- `hikaricp_connections_pending` is your earliest database alert.

---

**Next:** [Chapter 14 — Testing & Developer Experience](14-testing-dx.md).

**Reference:** [modules/data.md](../../modules/data.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
