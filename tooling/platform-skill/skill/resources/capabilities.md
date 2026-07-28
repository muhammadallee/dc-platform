# Capabilities

| Capability | Starter(s) | Summary |
|------------|-----------|---------|
| audit | `platform-starter-audit` | An append-only audit trail: record who did what, to which resource, with what outcome. Auditing is |
| authz | `platform-starter-security-authz` | Declarative permission enforcement: annotate a method (or a whole type) with @RequiresPermission |
| cache | `platform-starter-cache-caffeine` `platform-starter-cache-redis` | Platform caching conventions on top of Spring's own @Cacheable: a key convention that keeps |
| core | `platform-starter-core` | The smallest useful chassis: correlation-id propagation, the platform exception model, and the |
| data | `platform-starter-data-jpa` | Tech-neutral persistence value objects (platform-data-api) plus opinionated Spring Data JPA |
| errors | `platform-starter-errors` | RFC-9457 problem responses with stable, machine-readable error codes: throw a |
| events | `platform-starter-events` | In-process domain events: publish with DomainEventPublisher, handle with @DomainEventHandler, |
| files | `platform-starter-files` | Handle uploaded and downloaded files safely: validate content by what the bytes actually are (not |
| flags | `platform-starter-flags` | Evaluate boolean and typed feature flags, and gate code paths behind them with an annotation. |
| idempotency | `platform-starter-idempotency` | Make a method (or an HTTP POST) take effect at most once per key within a time window, so a retried |
| locking | `platform-starter-locking-jdbc` `platform-starter-locking-redis` | Cluster-wide mutual exclusion: run a block of code on at most one instance at a time. Locks are |
| logging | `platform-starter-logging` | Structured JSON logs on stdout with service identity and correlation, configured before the |
| messaging | `platform-starter-messaging-inmemory` `platform-starter-messaging-kafka` `platform-starter-messaging-rabbit` | Publish and handle integration events without coupling application code to a broker: inject |
| observability | `platform-starter-observability` | Metrics with a consistent identity, health probes that work on any platform, correlation that |
| openapi | `platform-starter-openapi` | A springdoc-generated OpenAPI document that already knows how the platform reports errors, so no |
| ratelimit | `platform-starter-ratelimit` | Keep a caller within a permitted request rate. Limits apply per key (a user, tenant, or IP) over a |
| redis | `platform-starter-redis` | Direct Redis access with platform conventions — distinct from the cache(cache.md) capability, |
| resilience | `platform-starter-resilience` | Fault-tolerance for calls to unreliable dependencies, built on Resilience4j(https://resilience4j.readme.io/). |
| restclient | `platform-starter-restclient` | Platform-conventional outbound REST calls: inject PlatformRestClientFactory instead of |
| scheduling | `platform-starter-scheduling` | Spring's @Scheduled on a virtual-thread scheduler, plus @LockedSchedule — a marker that makes |
| security | `platform-starter-security` | Secure-by-default stateless JWT resource-server security: every request is authenticated unless |
| storage | `platform-starter-storage-fs` `platform-starter-storage-s3` | Streaming-first object storage: move opaque blobs to and from a backend addressed by |
| validation | `platform-starter-validation` | The Bean Validation constraints every DC service needs, plus a validator wired to the platform |
| testing | `platform-starter-test` | The platform ships a test kit so a service tests the same way the platform is built: one starter, |
