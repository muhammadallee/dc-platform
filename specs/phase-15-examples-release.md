# Phase 15 — Examples, Smoke Matrix, Release Automation (P1, size M; Docker optional)

## A. Examples (`examples/`, built in reactor, `<skip>` deploy, parent=platform-service-parent)
1. `example-minimal` — starter-core+errors+logging only; proves the floor.
2. `example-golden-path` — REST + validation + security + openapi + observability + data-jpa(H2 local,
   pg profile) + messaging(inmemory local, kafka profile) + cache + resilience + audit; a small "orders"
   domain; heavily commented as the canonical reference; integration tests via platform slices.
3. `example-extension-provider` — implements a custom `ObjectStore` (encrypting-fs) + its own
   autoconfiguration BEFORE platform default + passes `platform-tck-storage`; proves the extension model.
4. `example-event-driven` — two modules (producer/consumer) over messaging; inmemory in tests,
   kafka via `-Pdocker` compose profile; demonstrates @EventHandler retry/DLQ.

## B. Smoke matrix
`tooling/scripts/smoke-matrix.sh`: boots example-golden-path with curated starter/profile combos
(~10: minimal, +security-off?, no — security stays; ±messaging, ±data, ±cache, ±flags, local/prod-sim profiles),
asserts health + `/actuator/platform` capability statuses match expectation table (checked into repo).
Runs in CI on every PR touching >1 capability; nightly full.

## C. Startup budget test
JUnit in example-golden-path: context startup wall-clock recorded; fail if > baseline+15%
(baseline file checked in, updated deliberately — comment governance).

## D. Release automation (finalize runbooks/release.md)
- `tooling/scripts/release.sh <version>`: verify clean tree → `mvn -T1C -Drevision=<v> verify` →
  golden-path.sh → tag `v<v>` → `mvn -Drevision=<v> deploy -DskipTests` (BOM/parents first is automatic
  via reactor order) → generate release notes from conventional commits (git-cliff or script) →
  build docs with version banner → japicmp aggregate compat report to docs/reference/compatibility.
- CI `release.yml`: tag-triggered, runs the same script; signing/deploy creds via CI secrets (documented,
  not required locally — local release deploys to a file:// staging repo for rehearsal: `-Plocal-release`).
- N-1 job: checkout examples at previous tag, run against new BOM (compat proof), nightly.

Acceptance:
```bash
mvn -T1C verify && ./tooling/scripts/golden-path.sh && ./tooling/scripts/smoke-matrix.sh
./tooling/scripts/release.sh 1.0.0-RC1        # local rehearsal with -Plocal-release
```
Tag `1.0.0-RC1` (Milestone M3). Soak, then 1.0.0 per runbook.
