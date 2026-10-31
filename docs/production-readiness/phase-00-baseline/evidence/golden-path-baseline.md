# Baseline golden path (Phase 0, T8)

| Field | Value |
|---|---|
| Command | `GP_MAVEN_REPO=~/.m2-baseline GP_WORKDIR=/c/Users/Ali/dc-hardening-runs/phase-00/baseline-20261026-061913 GP_THREADS=1 bash tooling/scripts/golden-path.sh` |
| Platform commit | `7b248e9` |
| Maven repository | `~/.m2-baseline` (cold, resolved from Maven Central; HC4) |
| Exit code | 0 |
| Result | `GOLDEN PATH OK: 8 scenario(s), 1267s total (per-service SLA 600s)` |
| Archetype | `train=1.0.0-SNAPSHOT  archetype=ae.gov.dubaicustoms.platform:platform-service-archetype:1.0.0-SNAPSHOT sha256=b98fb89dee346b9f…` |
| Raw install log | `/c/Users/Ali/dc-hardening-runs/phase-00/baseline-20261026-061913/evidence/platform-install.log` (sha256 `ba59ba808a1d95e58775a564ae8a82f555edda28d0c8f9c99761ddb833cc60c9`, not committed) |

| Scenario | Features | Generated tests | Seconds | Result |
|---|---|---|---|---|
| none-defaults | none | 14 | 61 | PASS |
| messaging | messaging | 14 | 57 | PASS |
| data | data | 18 | 88 | PASS |
| restclient | restclient | 22 | 59 | PASS |
| messaging-data | messaging,data | 18 | 85 | PASS |
| messaging-restclient | messaging,restclient | 22 | 72 | PASS |
| data-restclient | data,restclient | 26 | 85 | PASS |
| all-relocated | restclient,messaging,data | 26 | 77 | PASS |
