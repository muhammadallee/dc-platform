# Execution state

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Baseline commit | `7b248e919a7ffed9b2ae36379c8e9a84105f0b75` (main) |
| Branch | `hardening/v1` |
| Current phase | 3 — Database / Data Access (Oracle only, HD3 amended; prod 19c, HD13) |
| Current gate | D done (round 2, `review-round-2.md`: R2-F1 started-pool crash fixed incl. suspended pools; R2-F2 SelectionQuery/raw-delegate queries take the query default — Medium residual, documented and pinned; R2-F3 URL-built application Flyway bean unbounded — Medium residual, documented; 44 Oracle container tests). Gate C: done (round 1: 6 findings — F1–F3, F5, F6 accepted and fixed, F4 fixed at Medium, F2 fixed with a {vendor}-location residual; AC8 conceded and fixed; scope amendment A1 for AC10; 40 Oracle container tests). Gate B: done (Oracle starter + archetype `database` HD12/D113, 23 Oracle 19c-mode container tests, data errors 409/503 D111, bounded waits D110, Flyway pins + out-of-order guard D112, Instant = TSTZ D114, GX-04 guard). Gate B evidence: golden path 9/9 on `f9d1a86`, downstream test-compile 13 modules, errors+data `-Pdocker` verify green, Phase 1 consumer 92/92 on Phase 3 code |
| Last commit | see `git log -1 hardening/v1` |
| Open blockers | none (owner decided A1, A3, HI-0010) |
| Pending human decisions | HD7 chart-tool details (asked at Phase 8 step 7); HD11 outbox approach (asked at Phase 4 Gate A) |
| Known `BLOCKED` | Oracle RAC failover (HD4, no environment) |
| Next action | Phase 1 consumer rerun (owed, below), then Gate E. Gate E must register: R2-F2 (Hibernate SelectionQuery and raw-delegate queries ignore the remaining transaction time), R2-F3 (URL-built application Flyway bean gets no Oracle driver bounds), the F2 residual ({vendor} Flyway location + separate Flyway user opens one unbounded connection); a transaction-timeout cancel of SQL inside a PL/SQL function was missed once in ~7 runs (JPQL path, 23ai Free via Docker Desktop; not reproduced in 5 reruns; raw log `phase-03-data-access/evidence-raw/gateB-errors-data-verify.log`), plus the Gate A findings list. Owed (§3.10): a Phase 1 consumer rerun on the post-Gate-D code — `4fab487` changed `DataAccessFailure` after the 92/92 run. Gate F: Oracle consumer harness (Toxiproxy slow/down, pool exhaustion, runtime user cannot ALTER/DROP, read-timeout backstop). Phase consumer repo `~/.m2-hardening/phase-03` needs the full Phase 2 module list plus the data starters (a partial list mixes baseline security/logging with new core). Testcontainers reuse is on locally (Oracle cold start ~10 min). |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `7b248e9` | complete (tracking docs, tooling, harness) | `hardening/phase-00-closed` | 2026-10-26 |
| 1 | `f231689` | **PASS** (final `76cfcc7`; consumer 3× 92/92, golden path 8/8, CI green) | `hardening/phase-01-closed` | 2026-10-26 |
| 2 | `a33168d` | **PASS** (final code `5b8e820`; consumer 3× 75/75, Phase 1 regression 92/92, golden path 8/8, CI green; residuals accepted) | `hardening/phase-02-closed` | 2026-10-28 |
| 3 | `60afe86` | in progress (Gates A–C done) | — | — |

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
