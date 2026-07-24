# Dependency constitution

> From the architecture design §4. These rules are **executable** (ADR-007): the build fails on any
> violation, so the architecture cannot silently decay.

Capability dependency graph (arrows = "depends on"):

```
starter ──▶ autoconfigure ──▶ impl(provider) ──▶ spi ──▶ api ──▶ core-api
                     └────────── (optional) ──────┘
```

Maximum depth from a starter to JDK-only code is **4 platform hops**.

## Allowed dependencies

1. `api → core-api`, `api → JDK`, `api → spring-core` (only if unavoidable; prefer none).
2. `spi → api` (same capability), `spi → core-api`.
3. `impl → spi, api` (same capability) + its third-party library.
4. `autoconfigure → api, spi` (same capability); `autoconfigure → other capability api` **only**
   with `@ConditionalOnClass` guarding and `<optional>true</optional>`.
5. `autoconfigure → impl` only as `<optional>true</optional>` (compiled against, activated
   conditionally).
6. `starter → autoconfigure + exactly the impl(s) it names + that impl's driver`.
7. `test-support → api, spi` (+ test libs).
8. Everything → `platform-build-tools` (build-time only).

## Forbidden dependencies

1. `api → spi | impl | autoconfigure | starter`. **API must never depend on implementation.**
2. `impl → impl` (cross-provider or cross-capability).
3. `starter → starter`.
4. `spi → impl`.
5. Any platform module → examples or any application.
6. Any module → another capability's `spi`, `impl`, or `internal` packages.
7. `core-api → anything platform` (it is the root).
8. Compile-scope Spring Boot starters inside `api`/`spi` modules.

## Quantitative limits

- Maximum platform dependency depth: **4**.
- Maximum fan-out per module: **api ≤ 1** platform dep, **spi ≤ 2**, **impl ≤ 3**,
  **autoconfigure ≤ 6** (incl. optionals), **starter ≤ 4**.
- Any non-core module with fan-in > 5 must justify or split.
- **Zero cycles.**

## How the build enforces it

The rules are fitness functions, evaluated on every `mvn verify`:

- **maven-enforcer** — `banCircularDependencies` plus the custom `PlatformLayerRule` in
  `platform-build-tools`, which reads each module's category from its coordinates and fails the build
  on any forbidden edge, naming the violated edge and the fix.
- **ArchUnit** — the `PlatformArchRules` rule-jar runs in every module's test phase
  (`ArchConstitutionTest`), catching package-level violations (e.g. importing another capability's
  `.internal`).
- **japicmp** — binary-compatibility gate over `-api`/`-spi` packages (armed after the first tag);
  `.internal` packages are excluded.
- **dependency-convergence** — one version per third-party across the reactor.

A gate that never fails might be a gate that never runs: seed a forbidden `starter → starter` edge on
a scratch branch and confirm the build fails with the right message.

## Next

- [Architecture](architecture.md) · [Conventions](conventions.md) · [ADR-007](../decisions/adr-007.md).
