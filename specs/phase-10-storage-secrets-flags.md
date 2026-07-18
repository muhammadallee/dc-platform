# Phase 10 — Storage, Secrets, Feature Flags (P2, size M; Docker only for s3/vault tagged tests)

## A. Storage (`storage/`): storage-api, storage-spi, storage-fs (default/local), storage-s3, autoconfigure, starter-storage-s3, starter-storage-fs
- storage-api:
```java
/** Object storage facade. Streaming-first; no byte[] convenience over 10MB (comment why). */
public interface ObjectStore {
    ObjectRef put(String bucket, String key, InputStream in, ObjectMetadata meta);   // returns etag/size
    Optional<StoredObject> get(String bucket, String key);   // StoredObject: metadata + InputStream (caller closes)
    boolean delete(String bucket, String key);
    Stream<ObjectSummary> list(String bucket, String prefix); // caller closes stream
}
public record ObjectMetadata(String contentType, long contentLength, Map<String,String> userTags) { … }
public class ObjectStoreException extends PlatformException { … }  // ACME-STO-00xx codes
```
- spi: `ObjectStoreProvider { String name(); ObjectStore create(StorageProperties p); }` — actually simpler:
  providers just contribute an `ObjectStore` bean; SPI holds shared `KeyValidator` + provider base helpers. Keep SPI minimal; comment the choice.
- storage-fs: rooted at `acme.platform.storage.fs.root` (default `${java.io.tmpdir}/acme-storage`),
  path traversal protection (KeyValidator, tested hard), metadata sidecar json. Fully local; reference impl.
- storage-s3: AWS SDK v2 (bom pin now), maps metadata/tags; unit tests with SdkHttpClient stub,
  `@Tag("docker")` LocalStack round-trip.
- autoconfigure: back-off; checksum (sha256) computed on put when `checksum.enabled=true` (default),
  stored as user tag; Observation around ops; CapabilityDescriptor with provider name.

## B. Secrets (`secrets/`): secrets-api, secrets-spi, secrets-env (default), secrets-vault, autoconfigure, starter-secrets-vault
- api: `SecretRef("path#key")` record + `Secrets { Optional<String> get(SecretRef ref); }`.
- Primary integration is a `PropertySource`: `acme-secrets:` prefix values resolved at bind time via
  provider (EnvironmentPostProcessor + lazy resolution; comment startup-ordering carefully).
- env provider: maps `path#key` → env var `PATH_KEY`; local default, zero infra.
- vault provider: KV v2 read via spring-vault-core (pin) or plain WebClient?? Use plain JDK HttpClient
  (fewer deps; comment decision), token auth from env; `@Tag("docker")` vault-dev tests.
- Never log secret values — LogSanitizer registration for keys matching secret patterns (ties to phase 4).

## C. Feature flags (`flags/`): flags-api, flags-spi, flags-inmemory (default), flags-openfeature, autoconfigure, starter-flags
- api: `FeatureFlags { boolean enabled(String flag); <T> T value(String flag, T defaultValue); }`
  + `@FeatureGate("flag")` method annotation (aspect: skip + return default/Optional.empty/false — rules documented in javadoc).
- spi: `FlagProvider { Optional<FlagValue> evaluate(String flag, EvaluationContext ctx); }`;
  context carries user/tenant from CurrentUserAccessor when present (guarded edge).
- inmemory provider: from properties `acme.platform.flags.static.<flag>=true|false|value`; supports
  runtime mutation via actuator endpoint `platformflags` (write op, secured) — great for local demos.
- openfeature provider: adapter to OpenFeature SDK (pin) so enterprise providers (LaunchDarkly etc.) plug in downstream.
- Tests: matrix; aspect behavior table; endpoint test.

Acceptance: root verify docker-free (fs/env/inmemory paths); `-Pdocker` localstack+vault suites green; docs ×3; BOM; CHANGELOG.
