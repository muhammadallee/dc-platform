# Chassis audit — working state

Companion to [chassis-audit.md](chassis-audit.md). Update before any handoff; evidence from an earlier
revision does not prove the current tree.

- Base: `main` @ `efd3070ff68044600308d63b3df7414b2fedd564`; all changes are uncommitted in the working
  tree (nothing committed or pushed). Patch identity: see the final report / recompute with the command
  under "Identity" below.
- Task-owned locations (outside the checkout, safe to delete): Maven repositories `D:\dcpa\m2`
  (baseline + iteration) and `D:\dcpa\m2f` (final, cold); a C:-drive copy under the session scratchpad;
  generated projects and gate work dirs `D:\dcpa\gen`, `D:\dcpa\work*`, `D:\dcpa\final-gate-*`,
  `D:\dcpa\win`; logs `D:\dcpa\evidence`.
- JDK: Corretto 25.0.3 (`JAVA_HOME` must be set explicitly; the shell default is JDK 21).
- Build rule for this machine: full reactor builds run in the FOREGROUND, at `-T1`, with Maven's
  `-l <log>`, split into ~30-module chunks (`D:\dcpa\evidence\final-chunk{1..4}.list`).

## Done (verified on the final tree)

- Tested reactor `clean install` into the fresh `D:\dcpa\m2f`: 952 tests, 0 failures/skips.
- Gate, all 8 scenarios + negative inputs + requested-service profile/prod probes: PASS (3 chunks,
  `GP_SKIP_INSTALL=1`); gate install step: PASS; archetype-it basic/service/full: PASS.
- Windows-native PowerShell generate/verify/launch (repository on C:, project on D:): PASS.
- Findings, decisions (D83–D87), docs, CHANGELOG written.

## Open

- CI: push a branch and confirm `build` + `generator-gate` on Linux (not authorized in this session).
- Residuals in chassis-audit.md §10 (F13 LogSanitizer, F14 relay scoping, F15 Jackson 3 rule, F16
  webmvc starter, F18, Docker/PostgreSQL, offline).

## Next command

After pushing: watch the `generator-gate` job; locally, re-run
`GP_MAVEN_REPO=<fresh repo> bash tooling/scripts/golden-path.sh` (splitting with `GP_SCENARIOS` when a
single foreground run would exceed the tool limit).

## Identity

`{ git diff HEAD --binary; git ls-files -o --exclude-standard | grep -v '^dc-platform-chassis-review-fix-prompt.md$' | sort | while read -r f; do echo "== $f"; cat "$f"; done; } | sha256sum`
