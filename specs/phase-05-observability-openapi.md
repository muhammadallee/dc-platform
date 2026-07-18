# Phase 5 — Observability & OpenAPI (P1, size M, no Docker)

## A. Observability (`observability/`): `platform-observability-autoconfigure`, `platform-starter-observability`
- `ObservabilityProperties` (`acme.platform.observability`): `common-tags.enabled=true`,
  `otlp.enabled=false` (local default OFF — comment: no collector on laptops; export config documented),
  `health.groups.enabled=true`, `platform-endpoint.enabled=true`.
- Autoconfigurations:
  * `CommonTagsAutoConfiguration` — `MeterRegistryCustomizer`: tags `service`(=app name), `env`
    (`spring.profiles.active` first), `platform.version` (from build-info of the platform jar manifest).
  * `ObservationConventionsAutoConfiguration` — server/client `ObservationConvention` beans adding
    correlationId as low-cardinality? NO — high-cardinality: add to tracing baggage only; comment the
    cardinality rationale explicitly (this comment is mandatory).
  * `HealthGroupsAutoConfiguration` — contribute `management.endpoint.health.group.liveness/readiness`
    defaults via `EnvironmentPostProcessor` with lowest precedence (user yaml wins); readiness includes
    `readinessState` + db/broker indicators when present.
  * `PlatformInfoEndpointAutoConfiguration` — actuator `@Endpoint(id="platform")` returning the
    `CapabilityDescriptor` list from phase 3 as JSON. Secured by default (actuator exposure conventions:
    expose `health,info,platform,metrics,prometheus` over http in defaults, same EnvPostProcessor).
  * Contribute `CapabilityDescriptor("observability", …)`.
- starter: micrometer-core + tracing-bridge-otel + registry-otlp + registry-prometheus (optional? keep both,
  prometheus scrapes locally) + actuator.
- Tests: matrix; endpoint test via `@SpringBootTest(webEnvironment=RANDOM_PORT)` hitting `/actuator/platform`;
  common-tags assertion on SimpleMeterRegistry.

## B. OpenAPI (`openapi/`): `platform-openapi-autoconfigure`, `platform-starter-openapi`
- `OpenApiProperties` (`acme.platform.openapi`): `enabled`, `title=${spring.application.name}`,
  `version=${info.app.version:dev}`, `security-scheme=bearer-jwt|none` (default bearer-jwt).
- Autoconfigure: springdoc `OpenAPI` bean (`@ConditionalOnMissingBean`): info, server list, bearer scheme,
  and a reusable `ProblemDetail` schema + default 4xx/5xx responses appended via `OpenApiCustomizer`
  (so every operation documents platform errors — comment linking to errors slice).
- starter: springdoc-openapi-starter-webmvc-ui (pin version in platform-dependencies NOW).
- Tests: matrix; boot test asserting `/v3/api-docs` contains ProblemDetail schema + bearer scheme.

Acceptance: root verify; scratch app `/actuator/platform`, `/actuator/prometheus`, `/swagger-ui.html` all live.
Docs pages; BOM; CHANGELOG.
