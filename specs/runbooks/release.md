# Runbook — Cutting a Release Train

## Versioning recap
Single train version for all artifacts. SemVer: patch = fixes only; minor = additive (new modules,
new default methods on SPIs, new properties); major = breaking (removals, Boot major).

## Local rehearsal (always do this first)
```bash
git switch main && git pull && git status   # clean tree required
./tooling/scripts/release.sh 0.2.0 --rehearse   # -Plocal-release deploys to file:// staging
```
Rehearsal runs: full verify → golden-path → smoke-matrix → japicmp report → docs build.

## Real release
```bash
./tooling/scripts/release.sh 0.2.0        # tags v0.2.0, pushes; CI release.yml does deploy+docs+notes
```
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
- [ ] examples pinned to the new train build in the N-1 job config
