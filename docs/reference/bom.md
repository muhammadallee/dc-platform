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
| `ae.gov.dubaicustoms.platform:platform-observability-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-observability` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-spi` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-inmemory` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-rabbit` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-outbox` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-outbox-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-messaging-inmemory` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-messaging-rabbit` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-messaging-outbox` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-messaging-test` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-data-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-data-jpa-autoconfigure` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-data-jpa` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-data-jpa-oracle` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-test-api` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-starter-test` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-tck-messaging` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-mcp-server` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-skill` | `${revision}` |
| `ae.gov.dubaicustoms.platform:platform-migrations` | `${revision}` |
