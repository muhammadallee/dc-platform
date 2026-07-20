# OpenAPI

A springdoc-generated OpenAPI document that already knows how the platform reports errors, so no
team hand-writes the same 4xx/5xx shapes.

## What you get

- **`/v3/api-docs`** and **`/swagger-ui.html`** — springdoc's usual surface, zero controller
  annotations required to get a baseline document.
- **Title/version identity for free** — default to `spring.application.name` /
  `info.app.version` (falls back further to `dev`); override explicitly when you want a
  human-facing title that differs from the Spring application name.
- **Bearer-JWT security scheme documented by default** — matches the platform's expected auth
  model; every operation is marked as requiring it (turn off per-operation as usual, or switch
  the scheme off platform-wide).
- **Every operation documents the platform's error shape** — `ProblemDetail` schema plus default
  400/401/403/404/409/422/500 responses appended automatically (errors slice, phase 4); an
  operation-defined status is never overwritten.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-openapi</artifactId>
</dependency>
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.openapi.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.openapi.title` | `""` (→ `spring.application.name`) | Document title. |
| `dc.platform.openapi.version` | `""` (→ `info.app.version`, then `dev`) | Document version. |
| `dc.platform.openapi.security-scheme` | `BEARER_JWT` | `BEARER_JWT` or `NONE`. |

(Hand-written until the generated reference lands in phase 14.)

## Customize

- Define your own `OpenAPI` bean to replace the platform default wholesale (title, servers,
  security schemes — everything).
- Contribute additional `OpenApiCustomizer` beans (a different bean name) to layer more
  documentation on top; the platform's `platformProblemDetailOpenApiCustomizer` still runs.
- `dc.platform.openapi.security-scheme=NONE` documents no authentication (public APIs).

## Replace / Disable

- `springdoc.api-docs.enabled=false` / `springdoc.swagger-ui.enabled=false` turn off springdoc's
  own endpoints (Boot property, not a platform one).
- `dc.platform.openapi.enabled=false` switches the platform layer off; springdoc still generates
  a document from your own beans/annotations if the starter is on the classpath.

## Error codes

None — the OpenAPI document is generated at request time; a malformed customizer only affects
that document, never request handling.

## Testing

The `ApplicationContextRunner` matrix covers activation/back-off/title-version resolution/
security-scheme toggling; `ProblemDetailOpenApiCustomizerTest` unit-tests the schema/response
appending directly; a `RANDOM_PORT` boot test asserts `/v3/api-docs` contains both the
`ProblemDetail` schema and the `bearer-jwt` scheme end to end.

## Local dev notes

Open `http://localhost:<port>/swagger-ui.html` while the service is running. If a status code is
missing from an operation's documented responses, check whether the controller method already
declares that status explicitly — the platform customizer never overwrites an existing entry.
