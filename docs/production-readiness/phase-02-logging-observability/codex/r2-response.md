# Phase 2 round 2: Claude's response to Codex (Gate D, step 2)

Reviewed commit `0ffc3e1`. Before replying, each finding was reproduced on that commit with a new test.
`EncoderRedactionTest` drives the real `PlatformJsonEncoder` on hand-built events and checks the encoded
bytes; this is the encoder path the round-1 F3 test skipped. One assertion was added to
`PlatformEndpointWebTest$Defaults`. The run log is `evidence-raw/d1-red.log` (git-ignored). On
`0ffc3e1`: 16 tests ran, with 13 failures and 3 errors. The web test had 1 failure.

## Round-1 dispositions

| Codex | Reply |
|---|---|
| F1 PARTIAL (quoted cookie pairs) | Agree. Reproduced; see R2-F2. |
| F2 PARTIAL (whitespace before `{` in the JWT header) | Agree. Reproduced; see R2-F7. |
| F3 PARTIAL (boolean and binary never reach the `ValueMasker`) | Agree. Reproduced through the encoder; the direct `mask()` test hid it. See R2-F3. |
| F4 CONFIRMED, F6 CONFIRMED, F7 CONFIRMED | Noted. |
| F5 PARTIAL | Agree. See R2-F4 and R2-F5. |
| Re-evaluation concerns | Noted. The JWT whitespace concern is part of R2-F7. |

## Findings

### R2-F1 — stack overflow on long quoted values: **Agree**
The `password="…"` encoder test reproduces it: 200 000 characters throw `StackOverflowError`. In a
standalone check on JDK 25, the alternation loop `"(?:[^"\\]|\\.)*"` overflows on both 200 000 plain
characters and 100 000 escapes. The unrolled possessive form `"[^"\\]*+(?:\\.[^"\\]*+)*+"` matches both
inputs without recursing. Fix: the unrolled possessive form, used in both `KEY_VALUE` and `COOKIE`. The
tests cover plain, escaped and unterminated values through the encoder.

### R2-F2 — quoted cookie pairs: **Agree**
Reproduced for `Cookie:` and `Set-Cookie:`. Fix: in header text the cookie value runs to the end of the
line, quotes included. A value that starts with a quote is the JSON or quoted-field form; it is masked whole
and the quotes are kept.

### R2-F3 — boolean and binary credentials: **Agree**
Reproduced: `kv("token", true)`, a `byte[]` under `secret`, and a `byte[]` under `auth.password`. Fix: a
`FieldMasker` replaces the whole value of any field whose name is a credential key, at any depth, before
the value is written. The written value can be a string, number, boolean, binary, object or array.

### R2-F4 — structured credentials in the formatted message: **Agree**
Reproduced: `kv("password", "correct horse battery staple")`, the same with `Kv.of`, and value-only
`v("password", "hunter2")`. Fix: the message view always runs, not only when `LogSanitizer` beans exist.
It renders credential-keyed `Kv` and markers as `key=****` (`****` for value-only markers) without calling
the value's `toString`.

### R2-F5 — sanitizer coverage across argument shapes: **Agree**, with a written contract
Reproduced with `entries(...)`, a nested map, an array and a number. Fix: one traversal serves both the
field output and the message:
- scalars, `Map` (including `entries(...)`), `Collection` and arrays are supported, nested in any
  combination;
- an array element gets the nearest enclosing field name as its key;
- numbers go through the sanitizers too.

Markers are rendered from their field name, their format pattern and the sanitized value, using logstash's
own `StructuredArguments.toString`. This replaces textual replacement. For other objects (beans), the JSON
field is sanitized field by field; the message gets their `toString` plus the pattern masks. This contract
is documented in `logging.md`.

### R2-F6 — reserved field names as a bypass: **Agree**
Reproduced: `payload.logger` and `payload.level`. Fix: the name exemption for `@timestamp`, `level` and
`logger` is removed. Those fields are pattern-masked like any other; the negative test covers real logger
names, timestamps with offsets and level names. `traceId`/`spanId` stay exempt only as top-level fields
holding a W3C hex ID. Remaining risk, documented: a consumer that writes a card number as a top-level
`spanId` field.

### R2-F7 — JWT header with leading whitespace: **Agree**
Reproduced: the header ` {"alg":"HS256"}` encodes to `IHsi…`. Fix: compact-token candidates (a run of
three dot-separated base64url segments, the first at least 10 characters) are masked when the first
segment decodes to a JSON object: after JSON whitespace is trimmed it starts with `{` and ends with `}`.
This replaces the base64 prefix check. A negative corpus stays readable:
- stack frames;
- the platform's own fully qualified class names;
- `svc.cluster.local` hosts;
- `backup.tar.gz`;
- version strings;
- `exceptions.internal.…` / `ewallet.…` package names.

### R2-F8 — `toString()` failure path in the message view: **Agree**
Reproduced: the event fails with `IllegalStateException` from `String.valueOf`. Fix:
- each argument is rendered under its own guard, and a failing argument keeps its original form, so SLF4J's
  own guarded formatting handles it;
- a credential-keyed argument needs no `toString`, so its mask survives a sibling's failure;
- arguments past the last placeholder are not rendered.

### R2-F9 — huge decimal exponents: **Agree**
Reproduced: `kv("amount", new BigDecimal("1E+100000000"))` took 195 s on one encode. Fix: the
integer-digit count is computed as `precision - scale` in `long` arithmetic, and the value is rejected
unless that count is 13–19. A `BigInteger` with more than 64 bits is rejected before `toString`. Neither
case is ever expanded.

### R2-F10 — capability gauge missing: **Agree** (pre-existing, in scope)
Reproduced: in the Boot-created registry case (`PlatformEndpointWebTest$Defaults`, no registry bean of the
platform's) the scrape has no `platform_capability_active`. Fix: order the configuration after Boot's
registry auto-configurations, using the exact class names from the Boot 4.1 metrics jar's
`AutoConfiguration.imports`. The web test assertion proves the fix by going from red to green.

## Acceptance criteria

- AC5, AC6 and R5.1/AC7: covered by the fixes above.
- R7.2 docs:
  - book chapters 7, 9, 11 and 13 get the same exposure note as chapter 8;
  - `docs/llms-full.txt` is generated; it is regenerated by its generator, or recorded in the issue register
    as a known-stale generated file with its regeneration step.
