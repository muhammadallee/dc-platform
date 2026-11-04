# Phase 8: verification of the dependency change (D133)

Static review only. The change is `git diff 85dfbdd HEAD -- pom.xml build/`: Spring Boot 4.1.0 to 4.1.2; in `build/platform-dependencies`, OpenTelemetry's BOM 1.67.0 imported before Boot's, `com.rabbitmq:amqp-client` pinned at 5.37.0, and the Prometheus BOM override removed; in `build/platform-service-parent`, Boot's `rabbit-amqp-client.version` and `opentelemetry.version` properties set. Context: `docs/production-readiness/phase-08-final/run-results.md` (step 4) and HI-0105 in `docs/production-readiness/issue-register.md`. The full reactor verify, the container suites and an all-capability consumer app pass on it; a scan of that app's jar finds no known vulnerability.

1. Does every route a service can take get the fixed versions: `platform-service-parent`; importing `platform-bom` in its own `dependencyManagement`, before or after Boot's BOM; the archetype's generated POM? Name a route that still resolves a vulnerable version, with the mechanism.
2. Is removing the Prometheus override safe: does anything in Boot 4.1.2's universe declare a higher `io.prometheus` version than Boot manages, so `requireUpperBoundDeps` or a runtime mismatch follows?
3. Is OpenTelemetry 1.67.0 compatible with what Boot 4.1.2 manages around it (Micrometer Tracing's OTel bridge, the OTLP exporter, `opentelemetry-instrumentation` or semconv artifacts if managed)? A version skew that fails at runtime is a defect.
4. Is amqp-client 5.37.0 compatible with Spring AMQP as Boot 4.1.2 manages it?
5. Anything in the diff that changes behaviour for a consumer beyond versions.

Report concrete defects only, each with file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
