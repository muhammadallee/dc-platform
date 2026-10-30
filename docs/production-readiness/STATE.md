# Execution state

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Baseline commit | `7b248e919a7ffed9b2ae36379c8e9a84105f0b75` (main) |
| Branch | `hardening/v1` |
| Current phase | 3 — Database / Data Access (Oracle only, HD3 amended; prod 19c, HD13) |
| Current gate | E done (issue register HI-0051–HI-0067 for Phase 3; CI green on `773dbcf` (run 40966973355); HI-0041 FIXED via HD12/D113; no Critical/High open — HI-0057 RAC is BLOCKED under HD4; OPEN Mediums: HI-0055 and HI-0056 (Gate F), HI-0064 and HI-0065 (residuals, owner acceptance at Gate H), HI-0066 (PL/SQL cancel miss)). Gate D: done (round 2, `review-round-2.md`: R2-F1 started-pool crash fixed incl. suspended pools; R2-F2 SelectionQuery/raw-delegate queries take the query default — Medium residual, documented and pinned; R2-F3 URL-built application Flyway bean unbounded — Medium residual, documented; 44 Oracle container tests). Gate C: done (round 1: 6 findings — F1–F3, F5, F6 accepted and fixed, F4 fixed at Medium, F2 fixed with a {vendor}-location residual; AC8 conceded and fixed; scope amendment A1 for AC10; 40 Oracle container tests). Gate B: done (Oracle starter + archetype `database` HD12/D113, 23 Oracle 19c-mode container tests, data errors 409/503 D111, bounded waits D110, Flyway pins + out-of-order guard D112, Instant = TSTZ D114, GX-04 guard). Gate B evidence: golden path 9/9 on `f9d1a86`, downstream test-compile 13 modules, errors+data `-Pdocker` verify green, Phase 1 consumer 92/92 on Phase 3 code |
| Last commit | see `git log -1 hardening/v1` |
| Open blockers | none (owner decided A1, A3, HI-0010) |
| Pending human decisions | HD7 chart-tool details (asked at Phase 8 step 7); HD11 outbox approach (asked at Phase 4 Gate A) |
| Known `BLOCKED` | Oracle RAC failover (HD4, no environment) |
| Next action | Gate F: Oracle consumer harness covering AC1, AC3–AC6, AC9, AC11, AC13, AC14 — Toxiproxy slow/down and silent peer (incl. the separate Flyway user), pool exhaustion → 503 `DC-DATA-0503`, runtime user cannot ALTER/DROP (HI-0056), SIGTERM, read-timeout backstop (HI-0066), Phase 2 consumer rerun with the DB-down check tightened to `503 DC-DATA-0503` (`tooling/hardening/scenarios/phase-02-logging-observability.sh:215`). Phase consumer repo `~/.m2-hardening/phase-03` needs the full Phase 2 module list plus the data starters. Testcontainers reuse is on locally (Oracle cold start ~10 min). |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `7b248e9` | complete (tracking docs, tooling, harness) | `hardening/phase-00-closed` | 2026-10-26 |
| 1 | `f231689` | **PASS** (final `76cfcc7`; consumer 3× 92/92, golden path 8/8, CI green) | `hardening/phase-01-closed` | 2026-10-26 |
| 2 | `a33168d` | **PASS** (final code `5b8e820`; consumer 3× 75/75, Phase 1 regression 92/92, golden path 8/8, CI green; residuals accepted) | `hardening/phase-02-closed` | 2026-10-28 |
| 3 | `60afe86` | in progress (Gates A–E done) | — | — |

## Environment notes for resumption

- Every Maven call: `export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"`.
- Targeted builds only (HC3); never a root test run before Phase 8.
- Consumer runs: `bash tooling/hardening/scenarios/<scenario>.sh` with `HH_MAVEN_REPO` pointing at the
  repository the phase installed into; runs land in `~/dc-hardening-runs/<phase>/`.
- Compatibility baseline repository: `~/.m2-baseline` (baseline `7b248e9` installed by the golden path).
  **Never install into it.** Its platform artifacts are pinned by
  `phase-00-baseline/evidence/m2-baseline-manifest.sha256` (230 files). Phase consumer runs use
  `bash tooling/hardening/phase-repo.sh <phase> <modules>`, which verifies the manifest, copies the
  baseline to `~/.m2-hardening/<phase>` and installs the phase's modules on top.
- CI runs on every push via draft PR #2 (not mergeable until Phase 8; HC6 does not apply).
- Gate F composition conflict (recorded 2026-10-26): a feature-less archetype app already includes
  core, errors, logging, observability, openapi, security and validation starters (archetype baseline).
  Resolution: these are documented **fixtures** in Phases 1–7 (not certified by the phase unless it is
  their phase); the generated POM is not edited. Kill switches are used only where a fixture would
  interfere with the scenario, and each use is recorded in the phase evidence.
