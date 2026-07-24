# Platform BOM

> Generated from `build/platform-bom/pom.xml` at build time. Do not edit by hand.

Import one BOM to align every platform artifact to a single release-train version (ADR-005). Then add only the starters you need — a capability you don't add costs you nothing.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>ae.gov.dubaicustoms.platform</groupId>
      <artifactId>platform-bom</artifactId>
      <version>${platform.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Managed artifacts

| Artifact | Version |
|----------|---------|
| `ae.gov.dubaicustoms.platform:platform-dependencies` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-build-tools` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-core-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-core-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-core` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-errors-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-errors-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-errors` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-logging-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-logging-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-logging` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-validation-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-validation-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-validation` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-observability-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-observability` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-openapi-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-openapi` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-restclient-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-restclient-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-restclient` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-security-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-security-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-security` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-security-authz-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-security-authz-spi` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-security-authz-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-security-authz` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-spi` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-inmemory` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-kafka` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-rabbit` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-messaging-inmemory` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-messaging-kafka` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-messaging-rabbit` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-test` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-events-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-events-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-events` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-data-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-data-jpa-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-data-jpa` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-cache-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-cache-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-cache-caffeine` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-cache-redis` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-redis-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-redis` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-resilience-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-resilience-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-resilience` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-locking-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-locking-spi` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-locking-jdbc` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-locking-redis` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-locking-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-locking-jdbc` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-locking-redis` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-scheduling-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-scheduling` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-idempotency-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-idempotency-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-idempotency` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-storage-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-storage-spi` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-storage-fs` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-storage-s3` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-storage-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-storage-fs` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-storage-s3` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-flags-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-flags-spi` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-flags-inmemory` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-flags-openfeature` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-flags-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-flags` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-audit-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-audit-spi` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-audit-log` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-audit-jdbc` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-audit-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-audit-messaging-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-audit` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-ratelimit-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-ratelimit-spi` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-ratelimit-inmemory` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-ratelimit-redis` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-ratelimit-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-ratelimit` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-files-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-files-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-files` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-test-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-test` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-tck-messaging` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-tck-storage` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-tck-locking` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-tck-flags` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-tck-ratelimit` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-docs` | `${revision}` |
