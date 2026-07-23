# Files

Handle uploaded and downloaded files safely: validate content by **what the bytes actually are** (not
the extension), sanitise filenames against **path traversal**, cap **upload sizes**, and **stream**
downloads straight from object storage without buffering them in heap.

## What you get

- **`SafeFilename.sanitize(String)`** — turns a client-supplied name into one safe to use as a key or
  on disk: strips any directory component, null bytes, and control characters; `../../etc/passwd`
  becomes `passwd`.
- **`FileUploadPolicy(maxSizeBytes, allowedTypes)`** — the size + content-type rules for an upload.
  A default one is auto-configured from `dc.platform.files.*`.
- **`ContentTypeValidator`** — magic-byte content sniffing. The default recognises the v1 set —
  PDF, PNG, JPEG, ZIP, CSV, plain text — and rejects anything else against a strict allow-list.
- **Servlet multipart limits** — the platform sets the multipart max file/request sizes from
  properties (ahead of Boot's defaults).
- **`StreamingDownloads.write(objectStore, bucket, key)`** — builds a
  `ResponseEntity<StreamingResponseBody>` that streams the stored object to the response (200) or
  returns 404, with a sanitised `Content-Disposition` filename. Requires the storage capability.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-files</artifactId>
</dependency>
```

## Usage

```java
if (!policy.allowsSize(bytes.length) || !validator.permits(bytes, policy)) {
    throw new BadUpload("rejected upload");
}
String key = SafeFilename.sanitize(upload.getOriginalFilename());
```

```java
@GetMapping("/files/{key}")
ResponseEntity<StreamingResponseBody> download(@PathVariable String key) {
    return StreamingDownloads.write(objectStore, "invoices", key);
}
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.files.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.files.max-file-size` | `10MB` | Largest single upload; also the servlet multipart max file size. |
| `dc.platform.files.max-request-size` | `10MB` | Largest total multipart request. |
| `dc.platform.files.allowed-types` | pdf, png, jpeg, zip, csv, text | Content types the default policy permits. |

## Replace / Disable

- Define your own `ContentTypeValidator` bean for broader/stricter detection (it backs off).
- Define your own `FileUploadPolicy` bean to override the default size/type rules.
- `dc.platform.files.enabled=false` switches the capability off wholesale.

## Design notes

- **Magic-byte sniffing, not Tika (decision D55).** The default validator covers the accepted v1 set
  with no extra dependency; supply your own `ContentTypeValidator` for more.
- **`StreamingDownloads` lives in the autoconfigure module (decision D56)**, not the api, because it
  bridges storage's `ObjectStore` and Spring MVC's `StreamingResponseBody` — types the constitution
  bars from an api signature. It reaches consumers transitively through the starter.

## Local dev notes

Everything is tested with no Docker: the sanitiser and sniffer as pure units, the streaming helper
against an in-memory `ObjectStore`, and the auto-configuration through the ContextRunner.
