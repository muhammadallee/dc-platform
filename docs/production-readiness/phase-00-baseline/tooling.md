# Phase 0 — tooling verification

All commands run on the developer workstation (Windows 10, Git Bash), 2026-10-26, against baseline
`7b248e9`. Exit codes are the real process exit codes.

| # | Check | Command | Exit | Result |
|---|---|---|---|---|
| T1 | JDK 25 | `$JAVA_HOME/bin/java -version` (Corretto path, see README) | 0 | `openjdk version "25.0.3" 2026-04-21 LTS` |
| T2 | Maven | `mvn -v` | 0 | Apache Maven 3.9.9 |
| T3 | Docker daemon | `docker version --format …` | 0 | client/server 29.9.2, linux/amd64, 8 CPUs, 7.7 GiB |
| T4 | Testcontainers starts a container | `mvn -B -Pdocker -pl data/platform-data-jpa-autoconfigure verify -Djacoco.skip=true` | 0 | `postgres:16-alpine` started in 9.4 s; `PostgresParityIT` 1 test, 0 failures ([evidence](evidence/testcontainers-postgres.txt)) |
| T5 | Images pullable | `docker pull -q <image>` each | 0 (all) | see below |
| T6 | Codex | `codex exec --skip-git-repo-check -s read-only "Reply with exactly: CODEX_OK …"` | 0 | `CODEX_OK GPT-6`; header `model: gpt-6-astra`, `provider: openai` |
| T7 | Foreground timeout | `echo $BASH_MAX_TIMEOUT_MS` after setting it in `~/.claude/settings.json` `env` | 0 | `2700000` (45 min) is set in the shell environment. **Not yet proven** that the tool honours it beyond 10 min (no foreground command has run that long). Until proven, anything that may exceed 10 min runs in supervised background mode (§4.1). |
| T8 | Archetype generates a buildable app at baseline | `GP_MAVEN_REPO=~/.m2-baseline GP_THREADS=1 bash tooling/scripts/golden-path.sh` | see [baseline run](#baseline-golden-path) | all 8 feature combinations generated, verified, booted and probed |
| T9 | Consumer harness | `HH_MAVEN_REPO=~/.m2-baseline bash tooling/hardening/scenarios/phase-00-smoke.sh` | 0 | 8/8 assertions; ready 11 s; SIGTERM → exit 143 in 2 s, "Graceful shutdown complete" logged; 0 leftover containers ([evidence](evidence/harness-smoke-pass/)) |
| T10 | Harness reports failure truthfully | same with `HH_SELFTEST_FAIL=1` | **1** (expected) | 8 passed, 1 failed (`expected 418, got 401`); cleanup still ran, 0 leftovers ([evidence](evidence/harness-smoke-negative/)) |

## Images

| Purpose | Image | Pull |
|---|---|---|
| PostgreSQL (repo tests use 16) | `postgres:16-alpine`, `postgres:17-alpine` | ok |
| Oracle Free | `gvenzl/oracle-free:23-slim-faststart` (6.46 GB) | ok |
| RabbitMQ (repo tests use 3.13) | `rabbitmq:3.13-management-alpine`, `rabbitmq:4-management` | ok |
| Keycloak | `quay.io/keycloak/keycloak:26.4` | ok |
| Toxiproxy | `ghcr.io/shopify/toxiproxy:2.12.0` | ok |
| Testcontainers reaper | `testcontainers/ryuk:0.13.0` | ok |
| Consumer app runtime | `eclipse-temurin:25-jre` | ok |

Image versions per phase are fixed in that phase's `scope.md`; the repo's current test pins (Postgres 16,
RabbitMQ 3.13) are the starting point.

## Baseline golden path

The golden-path gate doubles as the baseline install for the compatibility repository (decision HC4):
it installs the baseline platform into the cold, isolated `~/.m2-baseline` (resolved from Maven Central,
no copy of `~/.m2`), then generates and verifies every feature combination outside the checkout.
Results: see [`evidence/golden-path-baseline.md`](evidence/golden-path-baseline.md).

## Long-running commands

Commands that may exceed the 45-minute foreground cap (Oracle suites, Phase 8 reactor) run with
`run_in_background`, stdout/stderr to a file, the exit code captured to `<file>.exit`, and are waited on
in the same session (plan §4.1). Every such run says so in its evidence.
