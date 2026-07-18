# Reference — Coding Standards

## 1. Naming
- Modules: `platform-<cap>[-<provider>|-api|-spi|-autoconfigure|-test]`, starters `platform-starter-<cap>[-provider]`.
- Packages: `com.acme.platform.<cap>` (+ `.annotation .spi .config .autoconfigure .internal .<provider>[.internal] .migration .testing`).
- Properties: `acme.platform.<cap>.*`, kebab-case keys.
- Error codes: `ACME-<CAP 2–8 chars>-<NNNN>`; ranges: 0001–0399 business, 0400–0499 client, 0500–0599 infra.
- Beans: type-based; names only when needed for back-off targeting (`platformExceptionHandler`).
- Tests: `<Class>Test` unit, `<Cap>AutoConfigurationTest` matrix, `<X>IT` failsafe, `@Tag("docker")` for infra.

## 2. Javadoc & comment policy (checkstyle-enforced where possible)
**Public API & SPI types (non-internal packages):**
- Class javadoc: (1) one-sentence purpose; (2) short usage snippet in `<pre>{@code …}</pre>` for
  entry-point types; (3) thread-safety statement ("Thread-safe.", "Not thread-safe; confine to one request.");
  (4) nullability convention note if not obvious; (5) `@since <version>`.
- Method javadoc: behavior incl. failure modes ("@throws EventPublishException if the transport rejects…"),
  units for durations/sizes, blocking behavior for I/O methods.
- Records: component meaning in class javadoc if names aren't self-evident; validation rules stated.
- SPI interfaces additionally: an "Implementation requirements" section (thread-safety obligations,
  idempotency, what the platform guarantees to the implementor, evolution note re: default methods).

**Auto-configuration classes:** leading comment block (above annotations), exact shape:
```java
/*
 * Activates when: <conditions in order>            (e.g. web app + acme.platform.core.enabled!=false)
 * Backs off when: <user bean types that disable it>
 * Beans: <bean → one-line responsibility>
 * Order: <before/after and WHY>
 */
```
**Internal classes:** one header line: why this exists / what would break without it. No API-style javadoc required.
**Inline comments:** only for non-obvious decisions — cardinality choices, ordering, security trade-offs,
  algorithm sources (Lua scripts cite the pattern). Never narrate the obvious; never leave commented-out code.
**POMs:** every non-boilerplate block gets a one-line `<!-- why -->`.

## 3. Java style
- Java 21; records for values & properties; sealed where it clarifies; `Optional` returns not params;
  JSpecify `@Nullable` on the rare nullable; no `null` returns from public API.
- Constructor injection only; classes final by default; fields final; no field/setter injection; no Lombok
  (records + IDE suffice; decision logged).
- Time: inject `Clock`; never `Instant.now()` in logic (testability).
- Exceptions: platform code throws `PlatformException` subtypes with codes; never swallow — log-with-code or rethrow.
- Logging: SLF4J; parameterized messages; no secrets/PII (LogSanitizer patterns); levels: ERROR=needs human,
  WARN=degraded-but-running, INFO=lifecycle one-liners, DEBUG=diagnostics.
- Concurrency: prefer virtual threads for blocking I/O executors; document confinement.

## 4. Test style
- AssertJ only; one behavior per test; consistent naming (pick a scheme, log the decision).
- ContextRunner matrix is mandatory per autoconfigure class (see autoconfigure-pattern.md §4).
- No sleeps: Awaitility (pin) for async; `InMemoryEventTransport.awaitIdle` for messaging.
- Coverage gate 80% (jacoco); don't chase it with trivial tests — exclude pure records via config if noisy.
