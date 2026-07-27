#!/usr/bin/env bash
# Cut a release train (phase-15 §D). Local-first: with --rehearse everything runs against a file://
# staging repo and nothing is tagged or pushed, so a release can be dry-run end to end without
# credentials or a server. Without --rehearse it verifies, tags v<version>, and leaves the actual
# deploy to CI (release.yml runs this same script with deploy credentials).
#
#   tooling/scripts/release.sh <version> [--rehearse]
#
# Steps: clean-tree check -> mvn -Drevision=<v> verify -> golden-path.sh -> smoke-matrix.sh ->
#        release notes from conventional commits -> japicmp aggregate compat report ->
#        (rehearse) deploy to file:// staging | (real) tag v<v>.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

VERSION="${1:-}"
MODE="${2:-}"
if [ -z "$VERSION" ]; then
  echo "usage: release.sh <version> [--rehearse]"; exit 2
fi
REHEARSE=0
NOTES_ONLY=0
case "$MODE" in
  --rehearse) REHEARSE=1 ;;
  --notes-only) NOTES_ONLY=1 ;;  # regenerate release notes + compat report only (used by CI post-deploy)
  "" ) ;;
  * ) echo "usage: release.sh <version> [--rehearse|--notes-only]"; exit 2 ;;
esac

SEMVER='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'
if ! [[ "$VERSION" =~ $SEMVER ]]; then
  echo "RELEASE FAILED: '$VERSION' is not a SemVer version (e.g. 1.0.0 or 1.0.0-RC1)"; exit 1
fi

TAG="v${VERSION}"
NOTES="target/release-notes-${VERSION}.md"
echo "== Release ${VERSION} (${TAG})$( [ "$REHEARSE" = 1 ] && echo '  [REHEARSAL]' ) =="

if [ "$NOTES_ONLY" != 1 ]; then
  # 1. Clean tree. A real release refuses a dirty tree; a rehearsal only warns (you may be iterating).
  if [ -n "$(git status --porcelain)" ]; then
    if [ "$REHEARSE" = 1 ]; then
      echo "WARNING: working tree is not clean (allowed for a rehearsal)"
    else
      echo "RELEASE FAILED: working tree is not clean; commit or stash first"; exit 1
    fi
  fi

  # 2. Full verify at the release version (gates: constitution, ArchUnit, coverage, checkstyle, japicmp).
  echo "== 1/6 Verify at -Drevision=${VERSION} =="
  mvn -T1C -Drevision="${VERSION}" verify

  # 3. Golden path: generate a service from the archetype, build/boot/probe it.
  echo "== 2/6 Golden path =="
  ./tooling/scripts/golden-path.sh

  # 4. Smoke matrix over example-golden-path.
  echo "== 3/6 Smoke matrix =="
  ./tooling/scripts/smoke-matrix.sh
fi

# 5. Release notes from conventional commits since the previous tag.
echo "== 4/6 Release notes -> ${NOTES} =="
generate_release_notes() {
  mkdir -p target
  local prev range
  prev="$(git describe --tags --abbrev=0 2>/dev/null || true)"
  if [ -n "$prev" ]; then range="${prev}..HEAD"; else range=""; fi
  {
    echo "# Release ${VERSION}"
    echo
    [ -n "$prev" ] && echo "Changes since ${prev}:" || echo "First tagged release."
    echo
    local section
    for section in "feat:Features" "fix:Fixes" "build:Build" "docs:Docs" "test:Tests" "refactor:Refactoring"; do
      local type="${section%%:*}" title="${section##*:}" lines
      # shellcheck disable=SC2086
      lines="$(git log ${range} --no-merges --pretty=format:'%s' 2>/dev/null \
                | grep -E "^${type}(\(.+\))?!?: " || true)"
      if [ -n "$lines" ]; then
        echo "## ${title}"
        echo "$lines" | sed -E "s/^${type}(\(([^)]+)\))?!?: /- (\2) /; s/- \(\) /- /"
        echo
      fi
    done
    # Adoption snapshot (phase-16 F.3). TODO(observability): once a Grafana instance exists, replace
    # this line with a deep link to the platform-adoption dashboard
    # (tooling/dashboards/platform-adoption.grafana.json) filtered to this train.
    echo "## Adoption"
    echo "- Snapshot: see docs/operations/adoption.md (Grafana deep link TODO — no instance yet)."
    echo
  } > "$NOTES"
  echo "   wrote $(wc -l < "$NOTES") lines"
}
generate_release_notes

# 6. Aggregate japicmp compatibility report. With no prior released baseline japicmp reports nothing
#    to compare (ignoreMissingOldVersion=true), so the aggregate degrades to "first release / no
#    baseline" rather than failing — exactly the state of a train that has never been deployed.
echo "== 5/6 Compatibility report =="
generate_compat_report() {
  local reports
  reports="$(find . -path '*/target/japicmp/*.diff' -not -path '*/staging-repo/*' 2>/dev/null || true)"
  local out="docs/reference/compatibility-${VERSION}.md"
  {
    echo "# Binary compatibility — ${VERSION}"
    echo
    if [ -z "$reports" ]; then
      echo "No prior released baseline was available to compare against: this is a first release of"
      echo "the affected artifacts (japicmp \`ignoreMissingOldVersion\`). Once \`${TAG}\` is published,"
      echo "the next train compares against it and this report lists any api/spi differences."
    else
      echo "japicmp compared each module against the last released train (\`RELEASE\`). Where no prior"
      echo "release exists yet, the per-module report is a first-baseline (informational, not a break)."
      echo "api/spi incompatibilities fail the build (breakBuild=true); other differences are reported."
      echo
      local r
      for r in $reports; do echo "- \`${r#./}\`"; done
    fi
  } > "$out"
  echo "   wrote ${out}"
}
generate_compat_report

if [ "$NOTES_ONLY" = 1 ]; then
  echo "NOTES ONLY: wrote ${NOTES} and the compatibility report; no verify/deploy/tag."
  exit 0
fi

# 7. Deploy (rehearsal: file:// staging, no tag/push) or tag (real: CI deploys).
echo "== 6/6 $( [ "$REHEARSE" = 1 ] && echo 'Staging deploy' || echo 'Tag' ) =="
if [ "$REHEARSE" = 1 ]; then
  rm -rf target/staging-repo
  mvn -T1C -Drevision="${VERSION}" -Plocal-release deploy -DskipTests
  echo "REHEARSAL OK: staged to target/staging-repo, notes at ${NOTES} (no tag, no push)"
else
  if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
    echo "RELEASE FAILED: tag ${TAG} already exists"; exit 1
  fi
  git tag -a "${TAG}" -m "Release ${VERSION}"
  echo "TAGGED ${TAG}. Push it (git push origin ${TAG}) to trigger release.yml (deploy + docs + notes)."
fi
