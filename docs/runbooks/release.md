# Runbook — Cutting a Release Train

## Versioning recap
Single train version for all artifacts. SemVer: patch = fixes only; minor = additive (new modules,
new default methods on SPIs, new properties); major = breaking (removals, Boot major). See
[ADR-005](../decisions/adr-005.md) and [compatibility](../reference/compatibility.md).

## Local rehearsal (always do this first)
```bash
git switch main && git pull && git status   # clean tree recommended (rehearsal only warns if dirty)
./tooling/scripts/release.sh 1.0.0-RC1 --rehearse
```
Rehearsal runs, in order: verify at `-Drevision=<v>` → `golden-path.sh` → `smoke-matrix.sh` →
release notes (`target/release-notes-<v>.md`, from conventional commits since the last tag) →
aggregate japicmp compatibility report (`docs/reference/compatibility-<v>.md`) → deploy to a
file:// staging repo under `target/staging-repo` via `-Plocal-release`. It never tags or pushes.

## Real release
```bash
./tooling/scripts/release.sh 1.0.0-RC1     # runs the same gates, then tags v1.0.0-RC1 (no push)
git push origin v1.0.0-RC1                  # pushing the tag triggers release.yml: deploy + docs + notes
```
A real release refuses a dirty tree and a pre-existing tag. CI (`release.yml`) re-runs the gates with
deploy credentials, performs the real `mvn deploy`, and regenerates notes/compat via
`release.sh <v> --notes-only`.
Then: bump `<revision>` to next `-SNAPSHOT` on main; move CHANGELOG Unreleased → 0.2.0 section;
write `docs/upgrade/0.2.0.md` (highlights, deprecations, ACTION REQUIRED — may be "none").

## Patch train (hotfix)
Branch from tag `vX.Y.Z` → `release/X.Y` → fix + test → release.sh X.Y.(Z+1) from that branch.
Security fixes: target <48h; only the fix, nothing else rides along.

## Gate checklist (release.sh enforces; human confirms)
- [ ] root verify green, docker suite green on CI nightly
- [ ] japicmp: no binary breaks in -api/-spi (or major + migration guide + OpenRewrite recipe)
- [ ] golden-path + smoke-matrix green
- [ ] docs build with version banner; property/error-code references regenerated
- [ ] CHANGELOG + upgrade note written
- [ ] examples build against the new train in the N-1 nightly job (`release.yml` `compat-n-1`; a no-op
      until the first tag exists, then it builds the previous tag's examples against the current BOM)
