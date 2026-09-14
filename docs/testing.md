# Testing platform services

The platform ships a test kit so a service tests the same way the platform is built: one starter,
composed slice annotations, ready fixtures, and Technology Compatibility Kits (TCKs) that certify
provider implementations. Everything here runs **docker-free** by default; real-infrastructure tests
are opt-in behind `@Tag("docker")`.

## One dependency

Add the test starter in **test** scope — it brings the platform test kit, `spring-boot-starter-test`
(JUnit 5, AssertJ, Mockito, spring-test), the recording messaging transport, and `json-path`:

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-test</artifactId>
  <scope>test</scope>
</dependency>
```

## Slice annotations

Compose a test context with one annotation (all activate the `test` profile and in-memory providers,
so no Docker/network/credentials are needed; logs keep the deployment JSON format — only the `local`
profile switches to console — so tests can assert structured log events):

| Annotation | Boots | Use for |
|---|---|---|
| `@PlatformTest` | the whole platform with test defaults | service/wiring tests |
| `@PlatformMessagingTest` | messaging autoconfig + a `@Primary` `TestEventTransport` | publish/handler assertions without a broker |
| `@PlatformDataTest` | Spring Boot's JPA slice on H2 + platform JPA conventions | repository/entity tests |
| `@PlatformWebTest` | mock web environment + `MockMvc` + the full error/validation/security stack | controller tests |

```java
@PlatformMessagingTest
class OrdersEventsTest {
    @Autowired TestEventTransport transport;
    @Autowired OrderService service;

    @Test void publishesOrderPlaced() {
        service.place("order-1");
        assertThatEvents(transport).sentTo("dc.orders").withType("OrderPlaced");
    }
}
```

> `@PlatformDataTest` needs the data capability on the test classpath (Hibernate); it is declared
> optional on `platform-test-api` so a non-JPA service is not forced to pull it in.

## Fixtures

- **`TestTokens`** — JWT identities for `MockMvc` (the platform is an OAuth2 resource server):

  ```java
  mvc.perform(get("/orders/42").with(TestTokens.user("alice").roles("VIEWER").jwt()))
     .andExpect(status().isOk());
  ```

  `TestTokens` bypasses the JWT decoder, so it cannot prove token *validation*. For that, use
  **`TestJwtIssuer`** — a loopback JWKS endpoint plus RS256 tokens (valid, expired, or signed by an
  untrusted key), no IdP or network:

  ```java
  static final TestJwtIssuer ISSUER = TestJwtIssuer.start();

  @DynamicPropertySource
  static void issuer(DynamicPropertyRegistry registry) {
      registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwkSetUri);
  }
  // real HTTP: "Authorization: Bearer " + ISSUER.token("alice")  → 200; ISSUER.expiredToken("alice") → 401
  ```

- **`assertThatProblem(...)`** — AssertJ assertions over RFC-9457 `ProblemDetail` bodies, including
  the platform `code`/`correlationId` extensions:

  ```java
  assertThatProblem(response).hasStatus(404).hasCode("DC-ORD-0001");
  ```

- **`Containers` + `DockerAvailable`** — shared, lazily-started singleton Testcontainers for
  `@Tag("docker")` tests, guarded so they skip when no Docker is present:

  ```java
  @PlatformTest
  @Tag("docker")
  class OrdersKafkaIT {
      @BeforeAll static void docker() { assumeTrue(DockerAvailable.check()); }

      @TestConfiguration
      static class Infra {
          @Bean @ServiceConnection KafkaContainer kafka() { return Containers.kafka(); }
      }
  }
  ```

## TCKs — certifying a provider

Each multi-provider capability ships an abstract **TCK**: extend it, supply your provider, and
inherit the whole contract suite. A provider is *platform-certified* iff the TCK passes against it.

| Capability | TCK | Certified docker-free | Under `@Tag("docker")` |
|---|---|---|---|
| messaging | `EventTransportTck` | in-memory transport | Kafka, RabbitMQ |
| storage | `ObjectStoreTck` | filesystem store | S3 / LocalStack |
| locking | `LockProviderTck` | JDBC on H2 | Redis, PostgreSQL |
| feature flags | `FlagProviderTck` | in-memory provider | — |
| rate limiting | `RateLimiterProviderTck` | in-memory sliding window | Redis |

```java
class MyTransportTckTest extends EventTransportTck {
    @Override protected EventTransport transport() { return new MyTransport(...); }
}
```

Some invariants are **relaxable** for backends that genuinely cannot meet them, via a protected
`boolean` hook the subclass overrides (documented on each TCK). For example the JDBC lock provider
relaxes strict *concurrent* exclusion on H2 — whose in-memory engine does not reliably serialize the
reclaim-then-insert acquisition across connections — while the deterministic sequential-exclusion and
fencing invariants still prove mutual exclusion docker-free; strict concurrent exclusion is certified
against PostgreSQL under `@Tag("docker")`.

The TCK jars are published (Test Support category), so enterprise providers built on the platform SPIs
self-certify by depending on the TCK in test scope.

## Running

- Default (docker-free): `mvn -T1C verify` — every in-memory/fs/JDBC provider passes its TCK.
- Full matrix (Docker running): `mvn -Pdocker verify` — adds the `@Tag("docker")` certifications.
