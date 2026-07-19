# Phase 4 — Errors, Logging, Validation (P0, size M, no Docker)

**Milestone M1 at the end of this phase → tag 0.1.0.**

## A. Errors slice (`errors/`)
Modules: `platform-errors-api`, `platform-errors-autoconfigure`, `platform-starter-errors`.

### errors-api (signatures; full javadoc)
```java
package ae.gov.dubaicustoms.platform.errors;

/** Thrown for business-rule violations that map to HTTP 4xx. @since 0.1.0 */
public class BusinessException extends PlatformException {
    public BusinessException(ErrorCode code, String message) { … }
    public HttpStatusHint statusHint();            // default UNPROCESSABLE(422)
}
public class NotFoundException extends BusinessException { … }   // 404, code param
public class ConflictException extends BusinessException { … }   // 409

/** SPI-lite: customize the outgoing ProblemDetail. Beans are applied in @Order. @since 0.1.0 */
@FunctionalInterface
public interface ProblemDetailCustomizer {
    void customize(ProblemDetail detail, Throwable source);
}
```
(`HttpStatusHint` = small enum wrapping the int; avoids Spring types in api? `ProblemDetail` IS Spring-web —
acceptable exception, documented: errors-api may depend on `spring-web` because ProblemDetail is the
standard model (ADR note in module README). Enforcer: whitelist spring-web for errors-api only.)

### errors-autoconfigure
- `ErrorsProperties` (`dc.platform.errors`): `include-stacktrace=false`, `type-base-uri="https://errors.dc.com/"`,
  `map-validation=true`.
- `PlatformErrorHandlingAutoConfiguration` → `@RestControllerAdvice PlatformExceptionHandler`
  (`@ConditionalOnMissingBean(name="platformExceptionHandler")`):
  * `PlatformException` → ProblemDetail: status from hint, `type = base+code`, `title` = code,
    `detail` = message, extensions: `code`, `correlationId` (from RequestContext), `timestamp`.
  * `MethodArgumentNotValidException`/`ConstraintViolationException` → 400 with `errors[]`
    (field, message, rejectedValue REDACTED for fields named like password/secret/token — comment why).
  * fallback `Exception` → 500, code `DC-CORE-0500`, message NOT leaked (generic text), full log with correlation.
  * applies `ProblemDetailCustomizer` beans in order.
- **Error-code registry test** (lives here, runs at build): classgraph-scan reactor classpath for
  `ErrorCode` constants; assert regex + uniqueness; write `target/error-codes.csv` (docs pick it up phase 14).
- Test matrix + MockMvc tests per mapping (assert full JSON shape once with JSONAssert).

## B. Logging slice (`logging/`)
Modules: `platform-logging-api`, `platform-logging-autoconfigure`, `platform-starter-logging`.
- logging-api: `AuditSafe` marker annotation? no — keep: `LogSanitizer` interface
  (`String sanitize(String key, String value)`) as customizer SPI-lite; `StructuredArguments`-style
  helper `Kv.of(key,value)` wrapper (thin, no logstash types in api).
- logging-autoconfigure:
  * `LoggingProperties` (`dc.platform.logging`): `format=json|console` (default `json`, but
    auto-fallback to `console` when `spring.profiles.active` contains `local` — comment the DX rationale),
    `include-mdc=true`, `service-name=${spring.application.name}`.
  * Ship `logback-platform.xml` resource + `LoggingSystem` initialization via
    `EnvironmentPostProcessor` (registered in `spring.factories` — the ONE legacy registration allowed,
    comment why: logging must configure before context). JSON encoder: logstash-logback-encoder ECS-ish
    fields: `@timestamp, level, logger, message, service, correlationId, mdc.*, stack_trace`.
  * MDC bridge: correlationId auto-included (from core filter).
- Tests: capture stdout (OutputCaptureExtension), assert JSON keys; console profile fallback test.
- starter POM.

## C. Validation slice (`validation/`)
Modules: `platform-validation-api`, `platform-validation-autoconfigure`, `platform-starter-validation`.
- validation-api: common constraints with validators: `@NotBlankTrimmed`, `@Ulid`, `@SafeText`
  (rejects control chars), `@FutureInstant`. Each with javadoc + usage snippet.
- autoconfigure: message source `platform-validation-messages.properties` wired in; method validation on.
- starter: aggregates spring-boot-starter-validation.
- Tests: per-constraint parameterized happy/sad; message resolution test.

## Acceptance
```bash
mvn -T1C verify
# scratch app: throw NotFoundException in a controller →
curl -s localhost:8080/missing | jq .    # RFC-9457 body with code, correlationId; JSON log line correlates
```
DoD per module checklist; docs pages errors.md/logging.md/validation.md; CHANGELOG; **tag 0.1.0**
(runbooks/release.md local flow), then bump revision 0.2.0-SNAPSHOT; enable japicmp against 0.1.0.
