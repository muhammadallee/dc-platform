1. **HI-0166: partly resolved.** The Hikari/JDBC defect is fixed: `AutoCloseable` and JDK proxies become masked text before container/getter traversal. Streams and resources implementing `Iterable` are covered. Ordinary beans and records remain property-walked. Hibernate detection has one gap.

2. **One finding in the new implementation**—an incomplete fix, rather than a regression in previously working behavior:

   **Medium — [PlatformValueMasker.java:638](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3gh-1708/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:638): inherited Hibernate interfaces are missed.**  
   **Scenario:** `interface ExtendedProxy extends HibernateProxy`; a concrete class implements `ExtendedProxy`, without directly declaring `HibernateProxy` anywhere in its superclass chain. It satisfies the stated proxy rule, but `resource()` returns false. Its getters—or iterator if also `Iterable`—are invoked.  
   **Fix:** reuse the existing recursive `implementsAny(type, Set.of("org.hibernate.proxy.HibernateProxy"))` helper. Add a regression case using an indirect interface and assert zero getter/iterator calls.

   No other new issues found.

3. **Concrete remaining path:** logging a Spring `UrlResource` pointing to an HTTP endpoint reaches the bean getter loop at [PlatformValueMasker.java:495](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3gh-1708/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:495). Spring’s resource descriptor is not `AutoCloseable`; its public `getInputStream()` opens the URL connection and obtains a stream. The returned stream then becomes masked text, but is never closed by this walk. Thus logging can make a network request and leave a stream open. This is pre-existing, outside the stated `AutoCloseable` rule. A targeted Spring `Resource` → masked-text rule would address it. See [Spring’s implementation](https://raw.githubusercontent.com/spring-projects/spring-framework/main/spring-core/src/main/java/org/springframework/core/io/UrlResource.java).

Read-only review of `36ef8a7..c4ddb44`; I did not rerun the reported 179 tests.
