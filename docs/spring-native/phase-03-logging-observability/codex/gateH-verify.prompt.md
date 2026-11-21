# Codex verification — Spring-native Phase 3, Gate H fix (HI-0166)

Claude's Gate H full verify found a defect that no review round caught. Hikari's INFO line "Added connection {}" made
the masker walk a JDBC `Connection` by its getters. `getMetaData()`, `getCatalogs()` and `getClientInfoProperties()`
ran database queries, left result sets open, and grew the line to 15 KB. The consumer's prod log showed it too.

The fix is at **`c4ddb44`**: `git diff 36ef8a7..c4ddb44 -- logging/`. Review read-only. Logging runs 179 tests, all
passing.

- **The rule.** A *resource* (`AutoCloseable`) or a *proxy* (`java.lang.reflect.Proxy`, or a class implementing
  `org.hibernate.proxy.HibernateProxy`) becomes its own `toString()`, masked by every pattern, as SLF4J would show it.
  None of its getters, nor its iterator, is called.
- **Where it runs.** The check comes before the container and getter branches: a stream or a resource that is also
  `Iterable` is not iterated.
- **Which way it narrows.** It turns objects into masked text; it is not an unmasked pass-through, unlike the package
  exemptions you rejected (HI-0151). Application beans and records are still walked by property (HI-0145).
- **Test.** `StructuredLogMaskingTest.aResourceOrAProxyIsItsOwnMaskedTextAndNoGetterRuns`.

Answer:
1. Is HI-0166 **resolved**, **partly resolved** or **not resolved**?
2. List **every** new issue this diff introduces, in one answer, with severity, file and line, a failure scenario and
   a fix. Say "none" if none.
3. Name any other walk path that still calls third-party code with I/O side effects, but only if you can give a concrete
   case. Hibernate's lazy collections are a known Phase 4 (data) item.
