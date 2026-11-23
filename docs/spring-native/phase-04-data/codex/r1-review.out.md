**HI-0167 is not fully closed.** I found four High findings and three Medium findings. This was a source and local-jar review; I did not run builds or Docker.

1. **High — Complex map keys can stop masking credential values.**  
   File: `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:442,491–496`.

   Consider:
   ```java
   record Key(String name) {
       @Override public String toString() { return name; }
   }
   Map.of(new Key("password"), "opaque-credential")
   ```
   Before this change, the key rendered as `password`, so its entire value was masked. Now it renders as `{name=password}`. That string is not recognised as a credential name, and `opaque-credential` survives the pattern masks.

   This is a redaction regression, including when the map is nested or used as a message argument.

   **Fix:** Separate key display from credential classification. When an arbitrary key cannot be classified safely without invoking application code, mask its associated value conservatively. Add a regression test comparing this case with an ordinary `"password"` key.

2. **High — Spring Data projections still invoke the underlying entity’s `toString()`.**  
   File: `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:408–411,695–698`.

   An interface projection backed by an entity is a JDK proxy, rather than an annotated entity or `HibernateProxy`. It therefore reaches `resource(value)` and `render(value)`.

   Spring Data Commons 4.1.2’s `PropertyAccessingMethodInterceptor` delegates Object methods through `proceed()`. Consequently, logging an entity-backed projection whose entity’s `toString()` accesses a lazy association can execute SQL inside the masker. A projection wrapping a Hibernate reference can likewise initialise that reference.

   This affects key-value pairs, nested values, complex map keys and the formatter’s own argument processing. It is separate from HI-0157.

   **Fix:** Give projections/JDK proxies a non-invoking placeholder, or implement explicitly safe handling that cannot resolve a lazy target. Test an actual Spring Data projection over a real Hibernate entity/reference.

3. **High — JDK aggregate wrappers bypass the entity guard.**  
   File: `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:500–505`.

   For example:
   ```java
   log.atInfo()
       .addKeyValue("result", Optional.of(entityManager.getReference(Customer.class, id)))
       .log("lookup");
   ```
   `Optional` is neither an entity nor a persistent collection. The blanket `java.*` branch renders it with `toString()`, which renders its contained proxy and loads it. Nesting the `Optional` inside a map, list, array, record or bean does not prevent this.

   **Fix:** Walk known aggregate wrappers such as `Optional` through the same sanitization logic. Restrict opaque rendering to explicitly safe scalar types instead of treating an entire package as safe. Add SQL-count and initialization assertions for wrapped JPA values.

4. **High — Scalar fast paths run before JPA recognition.**  
   File: `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:183–188,225–229,491–494`.

   A valid field-access entity can implement `CharSequence`. Its Hibernate proxy also implements that interface. Both `sanitizePair()` and Boot’s `mask()` call its `toString()` before reaching the new entity guard, thereby initializing the proxy. The map-key shortcut has the same problem for `CharSequence`, and also treats every `Number` subclass as safe.

   Thus, the same value can be safe through `sanitize()` but unsafe through a top-level key-value pair or Boot member processor.

   **Fix:** Apply JPA recognition before every rendering shortcut, including key processing. Alternatively, restrict shortcuts to exact trusted scalar classes. Extend the real-Hibernate test matrix with a `CharSequence` entity/reference.

5. **Medium — AC17 accepts forbidden static imports and scanner errors.**  
   File: `tooling/hardening/scenarios/phase-03-data-access.sh:84–86`.

   This compiles but escapes the check:
   ```java
   import static ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.PlatformValueMasker.MASK;
   ```
   The expression only matches lines starting with `import ae...`; static imports and indented imports pass. A `grep` error also takes the success branch.

   **Fix:** Handle whitespace and optional `static`, inspect the generated consumer sources, and distinguish “no matches” from a scanner failure. A dependency-level ArchUnit check would additionally catch fully qualified references.

6. **Medium — AC18’s consumer proof omits its required nested cases.**  
   File: `tooling/hardening/scenarios/phase-03/probe/DataProbe.java:67–83`; `tooling/hardening/scenarios/phase-03-data-access.sh:203–211`.

   The consumer logs only three direct key-value values. Scope AC18 also requires map, record and bean nesting at consumer level. The unit test exercises those through masker methods, but does not establish that the actual consumer encoder preserves that handling.

   A defect confined to nested values can therefore leave the entire AC18 consumer check green. The wrapper failures above illustrate why direct placeholders alone are insufficient.

   **Fix:** Log each required nested shape through the actual encoder, using fresh unloaded values. Assert both the resulting placeholders and unchanged SQL/initialization state. Keep the existing positive loading control.

7. **Medium — AC20 does not test the starter’s actual classpath.**  
   File: `data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/zeroconfig/ZeroConfigAcceptanceTest.java:62–65`.

   The classloader inherits the autoconfigure module’s complete test classpath. That module supplies Flyway directly and includes additional test dependencies; neither starter is the dependency being exercised.

   Removing `spring-boot-flyway` from a starter could break migration startup for consumers while this acceptance test still passes. The assertions establish default behavior on the test classpath, but not contract 14’s “only the starter” requirement.

   **Fix:** Run a minimal consumer whose application dependencies are the actual starter and database driver, with migrations as resources. Keep test tooling outside its application runtime classpath.

**Question 1 — Carry-over guarantees**

I found no implementation regression in the twenty `CARRIED` rows. The named proof sources remain relevant, with the distinctions below. This is an assessment of their assertions, not a fresh certification run.

| Carried guarantee | Assessment of named proof |
|---|---|
| Oracle driver and Flyway module | `OracleSmokeIT` exercises Oracle migration/persistence; AC1 checks the consumer dependency tree and startup. |
| Bounded pool, transaction and query waits | `TimeoutDefaultsSliceTest` checks effective settings; `OracleTimeoutDefaultsIT` exercises deadlines; AC4/AC5 exercise exhaustion and slowness. |
| Flyway posture and out-of-order guard | Both named posture suites exercise rejected configuration/migrations and disabled cleaning. |
| 409/503 responses without SQL | `OracleExceptionPathsIT` establishes provider exception chains; AC3 establishes the response mapping and forbidden-content checks. |
| Real Oracle degradation | Oracle tests and AC4–AC6 retain the faults, bounds and recovery checks. |
| Separate schema owner/runtime user | AC9 checks identities, rejects runtime DDL and verifies continued operation. |
| RAC remains unclaimed | Still explicitly excluded/blocked. |
| Descriptor back-off by name | The named test checks that the user descriptor wins. |
| Timestamp behavior across zones | `OracleDialectIT` changes JVM/session zones and checks the preserved instant. |
| Oversized identifier fails migration | The named test checks both the migration filename and ORA-00972. |
| Driver defaults follow the pool URL | The named smoke/unit tests check the effective driver properties; the processor reads the pool’s URL. |
| Started/suspended pools remain untouched | The three named tests retain the sealed-pool and suspended-pool checks. |
| Longer timeout on Flyway’s connection | The named unit test checks the separate datasource properties; AC14 exercises bounded startup failure. |
| Two startup paths lack default driver bounds | Accepted limitation remains. The named test proves the JVM-property workaround, rather than exercising both startup paths. |
| Two query paths use the query default | The named Oracle test explicitly exercises selection queries and raw-delegate queries. |
| PL/SQL cancellation is best-effort | The named test demonstrates ineffective cancellation; the consumer silent-peer scenario checks the read-timeout backstop. |
| Lost connection; commit never retryable | The named test exercises Spring translation and commit-phase classification. HI-0071 remains parked; this is not full commit-outcome certification. |
| One platform WARN; Hibernate lines remain | AC3’s `warn-on-trace` checks exactly one handler WARN and permits Hibernate’s statement logger. |
| Transaction boundaries | `OracleTransactionRulesIT` checks runtime rollback, checked-exception commit and successful commit through a Spring proxy. |
| Bounded pool/repository metric tags | The named slice checks tag keys and values; AC11 checks the consumer scrape. |

The `RETIRED` converter row is genuinely retired: the platform value types/converters are absent, and the remaining round-trip tests use ordinary strings. No additional finding there.

**Question 2 — Remaining boundaries**

The four High findings above prevent an unconditional no-loading guarantee.

I found no additional defect in recognition of ordinary annotated bytecode-enhanced entities, inherited Hibernate proxy interfaces, or standard `PersistentCollection` implementations. Hibernate’s `wasInitialized()` is a non-loading state read. Recognized values inside ordinary maps, lists, arrays, records and beans reach the guard.

XML-only concrete entities remain the explicitly documented limitation: without `@Entity` or `HibernateProxy`, they are walked as beans and computed getters can load associations. XML-backed Hibernate proxies are recognized.

MDC accepts strings; converting an entity to a string before placing it in MDC occurs outside the masker. HI-0157 likewise remains distinct from the formatter-side failures reported above.

**Question 3 — HI-0168**

No established implementation finding.

- Two auditors require a primary candidate; unresolved ambiguity correctly prevents selection.
- The service-auditor slice checks an `EntityManager` dependency. I found no inevitable JPA startup cycle in the listener/handler wiring.
- A service’s existing `jpaAuditingHandler` suppresses platform auditing enablement.
- The kill switch suppresses platform configuration, while leaving service-owned auditing under service control.
- Non-`String` auditors are accepted: Spring’s handler takes `AuditorAware<?>`; audited field types must match the returned auditor.

The last three combinations are not all covered by dedicated new slice tests, so this conclusion is source-based.

**Questions 4 and 5**

AC17 and AC18 can pass despite their respective guarantees failing, as described above. I found no additional defect in the management-port recovery correction, and no existing consumer assertion was removed by this diff.

No service-facing data operation needs a platform Java type. Rule 12/contract 12 has no established implementation violation; AC17’s enforcement is the finding.
