# platform-service-parent

The ONE parent application teams use. It supplies Boot defaults, the platform BOM, and build
conveniences — and deliberately injects **no** platform capabilities (ADR-007: explicit
dependencies). Add the starters your service actually uses:

```xml
<parent>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-service-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</parent>

<dependencies>
  <!-- The smallest useful chassis: correlation ids, error model, capability banner. -->
  <dependency>
    <groupId>ae.gov.dubaicustoms.platform</groupId>
    <artifactId>platform-starter-core</artifactId>
  </dependency>
  <!-- Plus whatever the service needs, e.g. -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
</dependencies>
```

No `<version>` tags on platform or Boot dependencies — the parent's BOM import manages them all.
