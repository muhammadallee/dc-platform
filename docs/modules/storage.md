# Storage

Streaming-first object storage: move opaque blobs to and from a backend addressed by
`(bucket, key)`, without ever buffering a large object into heap.

## What you get

- **`ObjectStore`** — `put(bucket, key, InputStream, ObjectMetadata)` → `ObjectRef` (etag + stored
  size); `get(bucket, key)` → `Optional<StoredObject>` (metadata + a caller-closed content stream);
  `delete(bucket, key)` → whether it existed; `list(bucket, prefix)` → a caller-closed
  `Stream<ObjectSummary>`. There is intentionally **no** `byte[]` convenience overload — wrap small
  in-memory content in a `ByteArrayInputStream` yourself so the buffering is explicit.
- **Two providers, chosen by classpath:**
  - **Filesystem (default)** — objects are files under `dc.platform.storage.fs.root`, each with a JSON
    metadata sidecar. Fully local, no external service.
  - **S3** — the AWS SDK v2 `S3Client`. Selected over the filesystem provider when an `S3Client` bean
    is present.
- **Checksum on put** — a SHA-256 is computed and stored as the `sha256` user tag by default
  (`dc.platform.storage.checksum.enabled`).
- **Observation** — every operation is wrapped in a `dc.platform.storage` observation
  (`operation`, `provider` tags) when a micrometer `ObservationRegistry` is present.
- **Path-traversal safety** — every bucket/key is vetted by `KeyValidator` (`.`/`..` segments,
  absolute keys, `\`/NUL rejected) and the filesystem provider additionally verifies the resolved path
  stays inside its root.

## Starter coordinates

Pick one provider:

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-storage-fs</artifactId>
</dependency>
```

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-storage-s3</artifactId>
</dependency>
```

The S3 starter brings the AWS SDK and the provider; **you** supply the configured `S3Client` bean
(region, credentials, endpoint) — the S3 provider activates once that bean is present.

## Usage

```java
ObjectRef ref = store.put("invoices", "2026/07/inv-42.pdf",
        Files.newInputStream(pdf), new ObjectMetadata("application/pdf", size, Map.of()));

try (StoredObject obj = store.get("invoices", "2026/07/inv-42.pdf").orElseThrow()) {
    obj.content().transferTo(outputStream);
}

try (Stream<ObjectSummary> listing = store.list("invoices", "2026/07/")) {
    listing.forEach(summary -> ...);
}
```

`StoredObject` is `AutoCloseable` and the `list` stream holds backend resources — use both in
try-with-resources.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.storage.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.storage.checksum.enabled` | `true` | Compute a SHA-256 on put and store it as the `sha256` user tag. |
| `dc.platform.storage.fs.root` | `${java.io.tmpdir}/dc-storage` | Root directory for the filesystem provider. |

The provider is selected by the classpath (S3 when an `S3Client` bean is present, otherwise
filesystem), not by a property.

## Replace / Disable

- Define your own `ObjectStore` bean to replace the platform store entirely (both providers back off).
- `dc.platform.storage.enabled=false` switches the capability off wholesale.

## Local dev notes

The filesystem provider is fully tested against a temp directory — no Docker. The S3 provider's
command mapping is unit-tested with a mocked `S3Client`; its wire behavior is verified against a real
S3 API (LocalStack) in a `@Tag("docker")` integration test run under `-Pdocker`.
