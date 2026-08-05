# Chapter 11 — Storage and Files

> **Capabilities covered:** `storage`, `files`
>
> Object storage behind one contract, and safe handling of what users upload.
>
> **Starters:** `platform-starter-storage-{fs,s3}`, `platform-starter-files` ·
> **Reference:** [modules/storage.md](../../modules/storage.md) ·
> [modules/files.md](../../modules/files.md)

---

## 1. Introduction and Business Value

Two capabilities that pair naturally:

- **Storage** — a backend-neutral `ObjectStore` addressed by `(bucket, key)`. Filesystem locally, S3
  in production, one API.
- **Files** — the *validation and delivery* layer for user-supplied content: magic-byte content
  sniffing, filename sanitising, size policy, and constant-memory downloads.

Storage is about *where bytes live*. Files is about *whether you should have accepted them*, and this
chapter's centre of gravity is the second.

### The problem storage solves

Without an abstraction, AWS SDK types spread through your code:

```java
PutObjectRequest request = PutObjectRequest.builder().bucket("invoices").key(key).build();
s3Client.putObject(request, RequestBody.fromBytes(bytes));
```

That is in a service class, which now depends on the AWS SDK. Then it is in a controller. Then in a
test, which needs LocalStack. Moving to a different backend — or running locally without one — becomes
a rewrite rather than a configuration change.

`ObjectStore` keeps the SDK behind the boundary. The same code runs against a directory tree on a
laptop and S3 in production.

### The problem files solves — and why it matters more

**File upload is one of the highest-risk features a service can expose.** The threats are concrete:

| Threat | The naive check that fails | Why it fails |
|---|---|---|
| Malware delivery | Reject anything not ending `.pdf` | Rename `evil.exe` to `invoice.pdf`. Extensions are client-supplied |
| Malware delivery | Trust the `Content-Type` header | Also client-supplied. `curl -F "file=@evil.exe;type=application/pdf"` |
| Path traversal on write | Use the filename as a storage key | `../../etc/cron.d/x` escapes your directory |
| Header injection | Echo the filename in `Content-Disposition` | A newline in the filename injects response headers |
| Denial of service | Validate size in the controller | The bytes were already buffered to reach your controller |

The platform's answers, in order: **sniff the magic bytes**, **sanitise the filename**, **enforce
limits at the servlet layer before buffering**, and **sanitise again on the way out**.

!!! success "Best practice — never trust anything the client tells you about a file"
    Not the extension, not the `Content-Type`, not the name, not the declared size. All four are
    attacker-controlled. The only trustworthy thing is the bytes, and only after you have bounded how
    many of them you will accept.

### Impact of absence

| Without these capabilities | What actually happens |
|---|---|
| No storage abstraction | SDK types in domain code; local development needs LocalStack |
| A `byte[]` convenience overload | A 2 GB upload OOMs the instance |
| Unclosed streams | S3 connection-pool exhaustion, surfacing hours after the offending request |
| No checksum | Silent corruption is undetectable; no integrity evidence for an audit |
| No key validation | `../../etc/passwd` escapes the storage root — arbitrary file read/write |
| Extension-based type checks | A renamed executable passes validation: a direct malware-delivery path |
| Unsanitised filenames | Path traversal on write, header injection on read |
| Application-level size checks only | Oversized uploads buffered before rejection — a trivial DoS |
| Buffered downloads | A large file OOMs the instance under concurrency |

---

## 2. Core Concepts and Underlying Principles

### 2.1 Streaming-first, and the overload that is deliberately missing

```java
ObjectRef put(String bucket, String key, InputStream in, ObjectMetadata meta);
Optional<StoredObject> get(String bucket, String key);
boolean delete(String bucket, String key);
Stream<ObjectSummary> list(String bucket, String prefix);
```

Notice what is **not** there: there is no `put(bucket, key, byte[] content)`.

That absence is the design. A `byte[]` overload is the path of least resistance, and the path of least
resistance becomes the default — so a service that mostly handles 50 KB PDFs quietly OOMs the day
someone uploads a 2 GB archive.

Forcing `new ByteArrayInputStream(bytes)` makes heap buffering **an explicit, reviewable decision**. It
is one extra line, and it appears in the diff.

!!! success "Best practice — the general principle behind this"
    When an API has a convenient form and a correct form, and the convenient one is dangerous at scale,
    *do not ship the convenient one*. Developers optimise for the shortest call that compiles. This is
    the same instinct behind `Money` failing loudly ([Chapter 8](08-data.md) §2.2) — make the wrong
    thing hard to express.

### 2.2 `AutoCloseable`, and the failure that surfaces hours later

`StoredObject` and the `list` stream both hold backend resources. Both are `AutoCloseable`.

```java
try (StoredObject object = store.get("invoices", key).orElseThrow()) {
    object.content().transferTo(out);
}
```

Skip the try-with-resources and you leak an S3 connection. One leak is invisible. Under load the
connection pool exhausts and the service degrades — **hours after** the offending code path ran,
because the pool drains gradually. That temporal disconnect is what makes it hard to trace: the symptom
appears long after the cause, on a completely different request.

!!! warning "The `list` stream needs closing too"
    ```java
    try (Stream<ObjectSummary> objects = store.list("invoices", "2026/")) {
        objects.forEach(this::process);
    }
    ```
    A `Stream` that is not obviously a resource is easy to forget — especially since most `Stream`s in
    Java are not. This one is backed by a paginated backend call.

### 2.3 Defence in depth on keys

Object keys are frequently derived from user input, which makes them an injection surface. The platform
validates at **two independent layers**:

```
   caller supplies a key
        |
        v
   KeyValidator (SPI, applied by every provider)
        rejects: blank, absolute, backslash, NUL,
                 any "." or ".." path segment
        |
        v
   FsObjectStore additionally verifies the RESOLVED path
        stays inside the configured root
        |
        v
   bytes are written
```

The first layer is provider-independent, so the same guarantee holds whether the backend is a directory
tree or S3. On S3, `..` is a prefix-confusion vector rather than a traversal one — different mechanism,
same rejection.

The second layer exists because the first could, in principle, be bypassed by an encoding trick or a
future provider that forgets to call it. Resolving the path and checking containment is the belt to the
validator's braces.

!!! note "Why two layers rather than one good one"
    Because the consequence of a miss is arbitrary file write. Defence in depth is proportionate to
    blast radius, and this is the only place in the platform where the same check is deliberately
    duplicated.

### 2.4 Magic-byte sniffing

A file's real type is determined by its leading bytes — its *magic number*:

```
   %PDF-              ->  application/pdf
   \x89PNG\r\n\x1a\n  ->  image/png
   \xFF\xD8\xFF       ->  image/jpeg
   PK\x03\x04         ->  application/zip
```

The platform ships a validator for a small v1 set — PDF, PNG, JPEG, ZIP, CSV, plain text — checked
against a **strict allow-list**. Anything unrecognised is rejected rather than permitted.

!!! warning "Allow-list, never deny-list"
    A deny-list of dangerous types is unbounded and always incomplete — there is always another
    executable format. An allow-list is finite and reviewable: you enumerate what your business
    actually accepts, and everything else is refused by default. This is deny-by-default applied to
    content, and it is the same argument as [security](05-security-authz.md) §1.

**Apache Tika is deliberately not a dependency.** Tika detects hundreds of formats and brings a large
transitive tree with its own CVE cadence. The platform ships a small validator covering the types a
government service actually accepts, and makes it replaceable — if you need Tika's breadth, supply a
`ContentTypeValidator` bean.

!!! warning "Magic bytes are necessary, not sufficient"
    A polyglot file — valid PDF *and* valid ZIP — passes a PDF check while carrying a payload. A macro
    document is a legitimate format doing illegitimate things. Sniffing raises the bar substantially;
    it is not antivirus. For genuinely untrusted uploads, scan the content as well.

### 2.5 Filename sanitising, in both directions

`SafeFilename.sanitize` strips directory components, removes null bytes and control characters,
replaces anything outside `[A-Za-z0-9._-]` with `_`, drops leading dots, and bounds the length.

```
   "../../etc/passwd"      ->  "passwd"
   "my report (v2).pdf"    ->  "my_report__v2_.pdf"
   null                    ->  "unnamed"
```

It never returns null or blank — a name that sanitises to nothing becomes `"unnamed"`, so downstream
code never has to handle an empty filename.

The **on the way out** direction matters as much as the way in. A filename echoed into
`Content-Disposition`:

```
Content-Disposition: attachment; filename="report.pdf"
```

If the filename contains a newline or a quote, the attacker controls the rest of your response headers.
`StreamingDownloads` sanitises before writing that header, so a name stored before you adopted the
platform is still safe to serve.

### 2.6 Where the size limit is enforced

Three places you *could* check, and only one is early enough:

```
   bytes arrive
        |
        v
   [ servlet container multipart limits ]   <-- the platform sets these
        |                                        rejected HERE: nothing buffered
        v
   [ Spring binds MultipartFile ]           <-- already buffered to disk or heap
        |
        v
   [ your controller checks size ]          <-- far too late
```

The platform drives Spring's `spring.servlet.multipart.max-file-size` and `max-request-size` from
`dc.platform.files.*`, so the container refuses an oversized request **before the bytes are buffered
anywhere**. Checking in the controller means the DoS already happened; you are just declining to
process what you already paid to receive.

### 2.7 Provider selection, and why S3 credentials are yours

```
   An S3Client bean present?
        |
        +-- YES:  S3ObjectStore
        |
        +-- NO:   FsObjectStore (files plus a JSON metadata sidecar)
```

The filesystem provider is the default, so local development and the default build need no
infrastructure at all — no LocalStack, no credentials.

The S3 provider activates on **your** `S3Client` bean. The platform does not construct one, which means
region, credentials, and endpoint stay under your service's control. That is deliberate: baking
credential configuration into a platform module would make the platform a party to your secret
management, and every service would inherit whatever assumptions it made.

### 2.8 Decorators — where checksums and metrics come from

The store you inject is not the raw provider. The platform wraps it:

```
   your code
      |
      v
   ObservedObjectStore     (Micrometer observation: operation, provider)
      |
      v
   ChecksumObjectStore     (SHA-256 on put, stored as the "sha256" user tag)
      |
      v
   FsObjectStore / S3ObjectStore
```

Both decorators are transparent — same interface, no API change — which is why storage gets latency
metrics and integrity checksums without the providers knowing about either. It is the decorator pattern
doing exactly what it is for.

Checksums matter for documents with regulatory retention: without one, silent corruption is
undetectable and there is no integrity evidence for an audit.

---

## 3. Feature Reference

### 3.1 Storage — public API

Package `ae.gov.dubaicustoms.platform.storage`. All STABLE.

| Type | Kind | Purpose |
|---|---|---|
| `ObjectStore` | interface | The four operations |
| `ObjectRef` | record `(bucket, key, etag, size)` | Returned by `put` |
| `ObjectMetadata` | record `(contentType, contentLength, userTags)` | Supplied to `put`, returned in `get` |
| `StoredObject` | record `(metadata, content)`, **`AutoCloseable`** | Returned by `get` |
| `ObjectSummary` | record `(key, size, lastModified)` | Elements of `list` |
| `ObjectStoreException` | exception | Backend failures, including `INVALID_KEY` |

| Operation | Signature | Notes |
|---|---|---|
| `put` | `ObjectRef put(String, String, InputStream, ObjectMetadata)` | **No `byte[]` overload** — §2.1 |
| `get` | `Optional<StoredObject> get(String, String)` | **Close it** — §2.2 |
| `delete` | `boolean delete(String, String)` | `true` if it existed |
| `list` | `Stream<ObjectSummary> list(String, String)` | **Close it** — it holds backend resources |

`ObjectMetadata.userTags` is defensively copied and immutable. `ObjectRef.size` and
`ObjectMetadata.contentLength` reject negatives at construction.

### 3.2 Storage — SPI

| Type | Status | Purpose |
|---|---|---|
| `KeyValidator` | **EXPERIMENTAL** | `requireValidBucket(String)`, `requireValidKey(String)` |

Rejects a key that is blank, absolute, contains a backslash or NUL, or contains a `.` or `..` path
segment. Throws `ObjectStoreException` with `INVALID_KEY`.

!!! note "There is no `ObjectStore` SPI type — the API interface *is* the provider contract"
    Providers implement `ObjectStore` directly. `KeyValidator` is in the SPI because it is the
    security-critical helper every provider must apply, not because the store itself needs a separate
    contract.

### 3.3 Files — public API

Package `ae.gov.dubaicustoms.platform.files`. All STABLE.

| Type | Kind | Purpose |
|---|---|---|
| `ContentTypeValidator` | functional interface | `Optional<String> sniff(byte[])`, plus `default boolean permits(byte[], FileUploadPolicy)` |
| `FileUploadPolicy` | record `(maxSizeBytes, allowedTypes)` | `allowsSize(long)`, `allowsType(String)` |
| `SafeFilename` | final class | `static String sanitize(String)` |

`permits` is a `default` method composing `sniff` with the policy's allow-list — so a custom validator
only implements `sniff` and inherits the correct allow-list semantics.

`FileUploadPolicy` rejects a non-positive `maxSizeBytes` at construction and copies `allowedTypes` into
an immutable set.

### 3.4 Files — autoconfigure

| Type | Purpose |
|---|---|
| `StreamingDownloads` | `static ResponseEntity<StreamingResponseBody> write(ObjectStore, bucket, key)` |

!!! note "Why `StreamingDownloads` is in autoconfigure, not the API module"
    It bridges the storage capability's `ObjectStore` and Spring MVC's `StreamingResponseBody` — two
    third-party-ish types the dependency constitution forbids in an `api` signature. Rather than bend
    the rule, the helper lives one layer out where both dependencies are allowed. A small example of
    the constitution shaping code placement rather than being worked around.

### 3.5 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

**Storage**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.storage.enabled` | Boolean | `true` | Kill switch | Essentially never |
| `dc.platform.storage.fs.root` | String | `${java.io.tmpdir}/dc-storage` | Filesystem provider root | **Always, outside local** — see below |
| `dc.platform.storage.checksum.enabled` | Boolean | `true` | SHA-256 on put, stored as `sha256` | Only if the CPU cost is measured and unacceptable |

!!! warning "The default filesystem root is a temp directory"
    `${java.io.tmpdir}/dc-storage` is correct for local development and **wrong everywhere else** — it
    is not durable, not backed up, and on many systems is cleared on reboot. If you run the filesystem
    provider outside a laptop, set `fs.root` to a real, durable, writable path. The platform ships a
    `FailureAnalyzer` for an unwritable root, but it cannot tell you that a writable temp directory was
    a bad choice.

**Files**

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.files.enabled` | Boolean | `true` | Kill switch | Essentially never |
| `dc.platform.files.max-file-size` | DataSize | `10MB` | Largest single file — **also applied to servlet multipart** | To match what your business accepts |
| `dc.platform.files.max-request-size` | DataSize | `10MB` | Largest total multipart request | Raise when accepting several files at once |
| `dc.platform.files.allowed-types` | Set&lt;String&gt; | pdf, png, jpeg, zip, csv, plain text | The default policy's allow-list | **Narrow it** to what you actually accept |

!!! warning "`allowed-types` replaces the default set"
    Like every list-valued property ([Chapter 5](05-security-authz.md) §6.4), setting it discards the
    default. That is usually what you want here — a service accepting only PDFs should list only
    `application/pdf` — but be deliberate rather than surprised.

### 3.6 Auto-configuration

| Class | Activates when | Contributes | Backs off when |
|---|---|---|---|
| `PlatformStorageAutoConfiguration` | Storage API on classpath, `storage.enabled != false` | Decorators, `KeyValidator`, capability descriptor | Standard back-off |
| `FsObjectStoreAutoConfiguration` | No `S3Client` bean | `FsObjectStore` | You define an `ObjectStore` bean |
| `S3ObjectStoreAutoConfiguration` | An `S3Client` bean is present | `S3ObjectStore` | You define an `ObjectStore` bean |
| `PlatformFilesAutoConfiguration` | Files API on classpath, `files.enabled != false` | `ContentTypeValidator`, `FileUploadPolicy`, multipart limits | You define your own bean of either type |

`StorageRootUnwritableFailureAnalyzer` turns an unwritable `fs.root` into a *Description / Action*
block at startup rather than an IO error on the first upload.

### 3.7 Extension points

| Extension | How | Effect |
|---|---|---|
| A backend the platform does not ship | An `ObjectStore` bean | Decorators still wrap it. Certify against the storage TCK |
| Broader type detection | A `ContentTypeValidator` bean | Platform's magic-byte validator backs off. Tika goes here |
| A different upload policy | A `FileUploadPolicy` bean | Platform's property-derived one backs off |
| S3 | An `S3Client` bean | Selects the S3 provider; you own credentials |

---

## 4. How-to Guide

### 4.1 Add the capabilities

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-storage-fs</artifactId>   <!-- or -storage-s3 -->
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-files</artifactId>
</dependency>
```

### 4.2 Store and retrieve

```java snippet:book-11-object-store
@Service
class InvoiceArchive {

    private final ObjectStore store;

    InvoiceArchive(ObjectStore store) {
        this.store = store;
    }

    ObjectRef archive(String invoiceId, InputStream content, long length) {
        ObjectMetadata metadata = new ObjectMetadata(
                "application/pdf", length, Map.of("invoiceId", invoiceId));
        return store.put("invoices", "2026/" + invoiceId + ".pdf", content, metadata);
    }

    Optional<byte[]> read(String key) throws IOException {
        // StoredObject is AutoCloseable — not closing it leaks a backend connection.
        Optional<StoredObject> found = store.get("invoices", key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        try (StoredObject object = found.get()) {
            return Optional.of(object.content().readAllBytes());
        }
    }
}
```

!!! warning "`readAllBytes()` in that example is the thing §2.1 warns about"
    It is fine for a known-small object and an OOM for a large one. It appears here to keep the snippet
    short; in real code, stream to wherever the bytes are going. §4.5 shows the streaming path.

### 4.3 List objects

```java snippet:book-11-list
@Service
class ArchiveIndex {

    private final ObjectStore store;

    ArchiveIndex(ObjectStore store) {
        this.store = store;
    }

    long countUnder(String prefix) {
        // The stream holds backend resources — close it.
        try (Stream<ObjectSummary> objects = store.list("invoices", prefix)) {
            return objects.count();
        }
    }
}
```

### 4.4 Validate an upload

```java snippet:book-11-upload-validation
@Service
class UploadValidator {

    private final ContentTypeValidator contentTypes;
    private final FileUploadPolicy policy;

    UploadValidator(ContentTypeValidator contentTypes, FileUploadPolicy policy) {
        this.contentTypes = contentTypes;
        this.policy = policy;
    }

    String accept(String clientFilename, byte[] content) {
        if (!policy.allowsSize(content.length)) {
            throw new BusinessException(new ErrorCode("DC-FILES-0001"), "file too large");
        }
        // Sniffs the BYTES. The extension and the Content-Type header are not consulted.
        if (!contentTypes.permits(content, policy)) {
            throw new BusinessException(new ErrorCode("DC-FILES-0002"), "content type not allowed");
        }
        return SafeFilename.sanitize(clientFilename);
    }
}
```

!!! success "Best practice — sanitise the filename, then use it as a *component*, not the whole key"
    ```java
    String key = "2026/" + UUID.randomUUID() + "/" + SafeFilename.sanitize(clientFilename);
    ```
    A sanitised filename is safe, but it is still attacker-*chosen* — two users uploading `invoice.pdf`
    would collide. A generated path component makes keys unique and unguessable; keep the sanitised
    name for display and for `Content-Disposition`.

### 4.5 Stream a download

```java
@GetMapping("/files/{key}")
ResponseEntity<StreamingResponseBody> download(@PathVariable String key) {
    return StreamingDownloads.write(objectStore, "invoices", key);
}
```

Constant memory regardless of object size, 404 when absent, and a `Content-Disposition` filename
sanitised on the way out.

### 4.6 Switch to S3

Supply an `S3Client` bean; the filesystem provider backs off:

```java
@Bean
S3Client s3Client() {
    return S3Client.builder()
            .region(Region.ME_CENTRAL_1)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
}
```

No application code changes. The same `ObjectStore` injection, the same calls.

### 4.7 Narrow the allow-list

```yaml
dc:
  platform:
    files:
      allowed-types:
        - application/pdf
      max-file-size: 5MB
      max-request-size: 5MB
```

!!! success "Best practice — narrow this to exactly what the business accepts"
    The default set is a reasonable starting point, not a target. A service that only ever receives
    customs declarations as PDFs should accept only `application/pdf`. Every additional type is
    additional attack surface you are not using.

### 4.8 Supply a broader validator

```java snippet:book-11-content-validator
@Configuration
class ContentDetection {

    @Bean
    ContentTypeValidator contentTypeValidator(TypeDetector detector) {
        // Only sniff() must be implemented — permits() is a default method
        // that composes it with the policy's allow-list.
        return content -> Optional.ofNullable(detector.detect(content));
    }
}

interface TypeDetector {
    String detect(byte[] content);
}
```

Wrap Tika here if you need its breadth — and accept its dependency tree and CVE cadence deliberately.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local and test | `storage-fs`, default root | No infrastructure, no credentials |
| **Any deployed environment on `fs`** | `fs.root` to a real durable path | The default is a temp directory |
| Production | `storage-s3` with your `S3Client` | Durability, replication, lifecycle policy |
| All | `allowed-types` narrowed | §4.7 |
| All | `max-file-size` matched to the business need | It is also the servlet limit |
| Production | Bucket lifecycle and retention policy | The platform stores; it does not expire |

!!! warning "The platform never deletes anything on your behalf"
    There is no retention, no expiry, no lifecycle. Objects live until you delete them. For S3 that is
    a bucket lifecycle policy; for the filesystem provider it is a job you write — with
    [`@LockedSchedule`](10-coordination.md) on it, since it will run on every replica otherwise.

### 5.2 What to monitor

Every operation is wrapped in a `dc.platform.storage` observation tagged `operation` and `provider`.

| Signal | Where | Alert when |
|---|---|---|
| Storage latency | `dc.platform.storage` timer, by `operation` | p99 climbing — storage is a common hidden contributor to endpoint p99 |
| Storage errors | The same observation's error outcome | Any sustained rate |
| Rejected uploads | Your own counter on the validation path | A spike means a client changed, or someone is probing |
| Disk usage (`fs`) | Host metrics on `fs.root` | Approaching capacity. **Nothing expires automatically** |
| S3 connection pool | AWS SDK metrics | Exhaustion — usually unclosed `StoredObject`s (§2.2) |
| `INVALID_KEY` rejections | Log the exception code | Non-zero is worth investigating — legitimate clients do not send traversal sequences |

!!! tip "Storage latency is the hidden p99 contributor nobody checks"
    An endpoint that writes a document does a network round trip most people forget is there. Because
    the platform wraps every operation in an observation tagged by `operation` and `provider`, you can
    see it directly rather than inferring it from the endpoint total.

### 5.3 Troubleshooting

**Startup fails: storage root unwritable.** The `FailureAnalyzer` names the path and the fix. Usually a
container filesystem that is read-only, or a volume not mounted.

**`ObjectStoreException` with `INVALID_KEY`.** The key contained a traversal segment, a backslash, a
NUL, or was absolute or blank. **This is the security control working.** If a legitimate client is
hitting it, they are building keys from raw filenames — sanitise first (§4.4).

**Uploads rejected with a valid-looking file.** The magic-byte sniff did not recognise it. Check the
actual leading bytes (`xxd file | head -1`) and confirm the type is on `allowed-types`. A `.csv` saved
by Excel with a BOM, or a PDF with leading whitespace, are common surprises.

**Oversized upload gives a container error, not your error.** Correct — the servlet layer rejected it
before your controller ran (§2.6). If you want a friendly RFC-9457 body, handle
`MaxUploadSizeExceededException` in an advice ([Chapter 2](02-errors-validation.md) §4.6).

**S3 connection pool exhausted.** Unclosed `StoredObject` or `list` streams. Search for `store.get(` and
`store.list(` without try-with-resources. The symptom appears long after the cause.

**Objects are missing after a restart.** The filesystem provider is pointed at `java.io.tmpdir`. §3.5.

**Downloads OOM under load.** Something is buffering — `readAllBytes()`, or building a `byte[]` response
— rather than using `StreamingDownloads`.

### 5.4 Scaling and performance

- **Checksums cost CPU proportional to size.** SHA-256 runs over every byte on put. For large objects
  that is measurable; it is on by default because integrity usually wins. Measure before disabling.
- **The filesystem provider does not scale horizontally.** Unless `fs.root` is a shared network volume,
  each instance has its own store — an object written by instance 1 is invisible to instance 2. This is
  the single most important operational difference between the providers.
- **S3 latency is network latency.** Tens of milliseconds per operation. Never do it inside a database
  transaction ([Chapter 8](08-data.md) §6.4).
- **`list` is paginated** at the backend. `count()` on a large prefix walks every page.
- **Streaming downloads use constant memory**, so concurrency is bounded by connections rather than
  heap.

!!! warning "`storage-fs` with more than one replica is almost always wrong"
    It works in development because there is one instance. Deploy three and uploads land on whichever
    instance served the request, and downloads fail two times out of three. Either use S3, or mount a
    genuinely shared filesystem — and know that a shared filesystem brings its own consistency
    behaviour.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| **Magic bytes are not antivirus** | A polyglot or a macro document passes. Scan genuinely untrusted content |
| Filenames are attacker-controlled | Sanitise on write **and** on read. §2.5 |
| Keys derived from user input | `KeyValidator` plus the provider's containment check. Do not bypass them |
| Stored objects inherit the backend's access control | An S3 bucket is only as private as its policy. The platform does not set one |
| Checksums detect corruption, not tampering | An attacker who can rewrite an object can rewrite its tag. Use object lock or versioning if you need tamper evidence |
| Uploaded content served back | Never serve user content from your API's origin without `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff` |
| Size limits are a DoS control | Enforced at the servlet layer, before buffering. §2.6 |
| Metadata `userTags` | Stored with the object and visible to anyone with read access on the backend |

!!! warning "Serving user-uploaded content from your own origin is a stored-XSS vector"
    An uploaded HTML or SVG file served with a permissive content type executes in your origin, with
    your cookies. `StreamingDownloads` sets a sanitised `Content-Disposition`, and
    [security](05-security-authz.md)'s default headers include `X-Content-Type-Options: nosniff` — but
    the strongest control is serving user content from a **separate domain** entirely. Decide this
    deliberately if you serve anything renderable.

---

## 6. Deep Dive

### 6.1 The decorator chain, and why it is not in the providers

```
   ObservedObjectStore  ->  ChecksumObjectStore  ->  FsObjectStore | S3ObjectStore
```

Every provider — including yours — gets observations and checksums for free, because they are applied
by decorators wrapping the `ObjectStore` interface rather than implemented inside each provider.

Put them in the providers instead and: the S3 provider and the filesystem provider each implement
checksumming, probably slightly differently; a third-party provider gets neither; and turning checksums
off means a condition inside every provider.

`DelegatingObjectStore` is the base that makes each decorator a few lines. This is the decorator pattern
used for exactly what it is for — and it is worth recognising, because the same shape appears wherever
the platform adds cross-cutting behaviour to a provider SPI.

### 6.2 Why `permits` is a `default` method

```java
default boolean permits(byte[] content, FileUploadPolicy policy) {
    return sniff(content).map(policy::allowsType).orElse(false);
}
```

A custom validator implements only `sniff`. It inherits three things it would otherwise have to get
right:

- **Unrecognised content is rejected**, because `orElse(false)`. A custom implementation might have
  defaulted to permitting, which inverts the allow-list.
- The allow-list check is applied consistently.
- The composition order is fixed — sniff first, then check.

`@FunctionalInterface` on the interface enforces that only one abstract method exists, so the lambda
form in §4.8 keeps working as `default` methods are added.

### 6.3 Why the filesystem provider writes a metadata sidecar

A filesystem stores bytes and a name. It does not store a content type, a content length, or arbitrary
user tags — but `ObjectMetadata` carries all three.

So `FsObjectStore` writes a JSON sidecar next to each object. Two consequences worth knowing
operationally:

- **The store is not just your files.** A directory listing shows sidecars too. Anything treating
  `fs.root` as a plain file tree — a backup script, a sync job — must handle them.
- **Writes are not atomic across the pair.** A crash between writing the object and its sidecar leaves
  an object whose metadata is missing. S3 stores metadata with the object and does not have this
  window.

This is one of several reasons the filesystem provider is a development convenience rather than a
production choice.

### 6.4 Why `StreamingDownloads` could not live in the API module

The dependency constitution forbids third-party types in an `api` signature. `StreamingDownloads.write`
returns `ResponseEntity<StreamingResponseBody>` — Spring MVC — and takes an `ObjectStore` — another
capability's API.

Options were: bend the rule; invent a platform-owned return type that the caller unwraps (pure
ceremony); or put the helper one layer out, in `files-autoconfigure`, where both dependencies are
allowed.

The platform chose the third. The cost is that the helper is in a module you would not immediately
guess. The benefit is that the constitution stayed true without an exception — and an architecture with
no exceptions is one you can still reason about in three years.

!!! note "This is a recurring pattern worth recognising"
    [Audit's messaging sink](12-audit.md) exists as its own module for the same kind of reason. When
    the platform's module graph looks slightly odd, the constitution is usually why — and the
    alternative was an exception that would have eroded it.

### 6.5 Why there is no `ObjectStore` SPI module

Most multi-provider capabilities split `api` and `spi`: [messaging](07-messaging-events.md) has
`EventTransport`, [locking](10-coordination.md) has `LockProvider`. Storage does not — providers
implement `ObjectStore`, the consumer-facing interface, directly.

The reason is that the provider contract and the consumer contract are genuinely **the same shape
here**. `put`/`get`/`delete`/`list` is what a caller wants and exactly what a backend does. Inventing a
parallel `StorageProvider` interface with the same four methods would be abstraction for its own sake —
which is precisely what the platform's "SPI only where a second provider is plausible *and the
contracts differ*" rule exists to prevent.

`KeyValidator` is in the SPI module because it is genuinely provider-facing: applications never call it,
and every provider must.

### 6.6 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| Not closing `StoredObject` | It does not look like a resource | Connection-pool exhaustion, hours later |
| Not closing the `list` stream | Most `Stream`s are not resources | Same |
| `readAllBytes()` on an unbounded object | It is the shortest code | OOM on the first large file |
| Using the client filename as the key | It is right there | Traversal, collisions, and unguessable-key loss |
| Trusting the `Content-Type` header | It is what HTTP is for | Client-supplied. Sniff the bytes |
| Deny-listing dangerous types | It feels targeted | Always incomplete. Allow-list |
| Leaving `fs.root` at the default | It works locally | A temp directory, cleared on reboot |
| `storage-fs` with N replicas | It works with one | Each instance has its own store |
| Expecting objects to expire | Caches expire, so storage feels like it should | Nothing is ever deleted for you |
| Checking size in the controller | It is where validation lives | The bytes are already buffered |
| Serving user content from your origin | It is the obvious place | Stored XSS. Separate domain, or `attachment` + `nosniff` |
| Treating a checksum as tamper evidence | It sounds like integrity | It detects corruption, not a determined rewrite |

---

## 7. Exercises and Hands-on Labs

Starting point: [`examples/example-extension-provider`](../../examples.md), which implements a custom
encrypting `ObjectStore` and certifies it against the storage TCK — the best worked example of §3.7.

### Lab 1 — Basic: round-trip an object and prove the resource contract

**Goal.** Use the four operations, and see what happens when you skip `close()`.

**Steps.**

1. `put` a small file. Inspect the returned `ObjectRef` — what is the `etag`?
2. `get` it back inside a try-with-resources and compare the bytes.
3. Read the `sha256` user tag. Where did it come from? (§2.8)
4. `list` with a prefix and count the results — inside a try-with-resources.
5. Now deliberately `get` **without** closing, in a loop of 100. Watch the connection pool metric (S3)
   or open file handles (filesystem).
6. `delete` and confirm the return value distinguishes existed from did-not-exist.

**Expected outcome.** Steps 1–4 work cleanly. The `sha256` tag is there without you computing it. Step
5 shows resources climbing and not being released — the failure mode of §2.2, made visible in a loop
rather than over hours.

**Hints.**

- With the filesystem provider, look for the metadata sidecar next to the object (§6.3).
- `lsof -p <pid> | wc -l` makes step 5 observable locally.

**How to verify.** A test asserting the round-tripped bytes match and the `sha256` tag equals an
independently computed digest.

### Lab 2 — Intermediate: attack your own upload endpoint

**Goal.** Try the standard attacks and watch each control fire.

**Steps.**

1. Build an upload endpoint using `ContentTypeValidator` and `FileUploadPolicy` (§4.4).
2. Upload a real PDF. Accepted.
3. Rename an executable to `invoice.pdf` and upload it. What happens, and **which check** caught it?
4. Upload with a forged header: `curl -F "file=@evil.exe;type=application/pdf"`. Same question.
5. Upload with filename `../../etc/passwd`. What does `SafeFilename.sanitize` return?
6. Try to `put` with key `../../escape.txt` directly. What exception, and from which layer?
7. Upload a file larger than `max-file-size`. Where was it rejected — your controller, or before it?
8. Upload a filename containing a newline and serve it back. Inspect the raw response headers.

**Expected outcome.** Steps 3 and 4 are both rejected by the **magic-byte sniff**, not by the extension
or header — confirm that is the check that fired. Step 6 gives `ObjectStoreException(INVALID_KEY)`.
**Step 7 never reaches your controller**, which is the point of §2.6. Step 8 shows sanitisation
preventing header injection.

**Hints.**

- Log which check rejected, so you can attribute each rejection rather than assume.
- For step 7, add a breakpoint or a log line at the top of the controller and confirm it is never hit.

**How to verify.** A parameterised test over the six attack inputs asserting each is rejected, and one
asserting a legitimate PDF is accepted.

### Lab 3 — Advanced: swap the provider and certify it

**Goal.** Prove the abstraction holds, and use the TCK the way a provider author would.

**Steps.**

1. Run the filesystem provider. Note where objects land and what the sidecars contain.
2. Add an `S3Client` bean pointed at LocalStack (`docker compose up localstack`). Confirm the provider
   switched — check `/actuator/platform`.
3. Run the **same** application tests against both. Which, if any, fail?
4. Write your own `ObjectStore` — an in-memory one is fine — and confirm the platform's decorators
   still wrap it. Does it get checksums? Observations?
5. Extend `ObjectStoreTck` against your implementation. Which invariants fail first?
6. Fix them until the TCK passes. Note especially the key-validation cases.
7. **The multi-instance trap**: run two instances with `storage-fs` and different roots behind a
   balancer. Upload through one, download repeatedly. What is the failure rate, and why?
8. Repeat step 7 with S3. Explain the difference in one sentence.

**Expected outcome.** Step 3 should pass identically — that is the abstraction earning its place. Step
4 shows the decorators are provider-independent (§6.1). **Step 5 is the real exercise**: the TCK will
catch invariants you did not think about, particularly around key validation and `delete` semantics.
Step 7 fails roughly half the time, which is §5.4's warning made concrete.

**Hints.**

- The TCK is `platform-tck-storage`, and `example-extension-provider` shows the extension pattern
  end to end.
- Step 5's most commonly missed invariants are `delete` returning `false` for an absent object, and
  `list` returning an empty stream rather than null.

**How to verify.** Your provider passes the full `ObjectStoreTck` — which is the platform's own
definition of "certified".

---

## 8. Checklist / Quick Reference

**Add them**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-storage-fs</artifactId>   <!-- or -storage-s3 -->
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-files</artifactId>
</dependency>
```

**Storage API**

```java
ObjectRef put(bucket, key, InputStream, ObjectMetadata);   // no byte[] overload, deliberately
Optional<StoredObject> get(bucket, key);                   // AutoCloseable — CLOSE IT
boolean delete(bucket, key);
Stream<ObjectSummary> list(bucket, prefix);                // AutoCloseable — CLOSE IT
```

**Files API**

```java
contentTypes.permits(bytes, policy);        // sniffs MAGIC BYTES, not the extension
policy.allowsSize(bytes.length);
SafeFilename.sanitize(clientFilename);      // "../../etc/passwd" -> "passwd", null -> "unnamed"
StreamingDownloads.write(store, bucket, key);
```

**Properties**

| Key | Default |
|---|---|
| `dc.platform.storage.fs.root` | `${java.io.tmpdir}/dc-storage` — **change it outside local** |
| `dc.platform.storage.checksum.enabled` | `true` |
| `dc.platform.files.max-file-size` | `10MB` (also the servlet limit) |
| `dc.platform.files.max-request-size` | `10MB` |
| `dc.platform.files.allowed-types` | pdf, png, jpeg, zip, csv, plain text — **narrow it** |

**Upload checklist**

1. Size — enforced at the servlet layer, before buffering
2. Magic-byte sniff against an allow-list — never the extension, never the header
3. `SafeFilename.sanitize` the client filename
4. Use the sanitised name as a *component* of a generated key, not as the key
5. Serve back with `Content-Disposition: attachment`, ideally from a separate domain

**Diagnose it**

```bash
curl -s localhost:8080/actuator/platform | jq                       # storage[ACTIVE] (fs|s3)?
curl -s localhost:8080/actuator/metrics/dc.platform.storage | jq '.availableTags'
curl -s localhost:8080/actuator/env/dc.platform.storage.fs.root | jq
xxd suspicious-file | head -1                                       # what ARE those bytes?
```

**Rules of thumb**

- Close `StoredObject` and the `list` stream. Always. The leak surfaces hours later.
- There is no `byte[]` overload on purpose. Streaming is the default for a reason.
- Never trust the extension, the `Content-Type`, the filename, or the declared size.
- Allow-list content types, never deny-list. Narrow the default set.
- Sanitise filenames on write **and** on read.
- Never use a client filename as a storage key — sanitise it, then make it a component.
- `fs.root` defaults to a temp directory. Change it anywhere real.
- `storage-fs` with more than one replica is almost always wrong.
- Nothing expires automatically. Retention is a lifecycle policy or a locked scheduled job.
- Magic bytes are not antivirus.
- Never make a storage call inside a database transaction.

---

**Next:** [Chapter 12 — Audit](12-audit.md), which records who uploaded that document, and whether the
attempt succeeded.

**Reference:** [modules/storage.md](../../modules/storage.md) ·
[modules/files.md](../../modules/files.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
