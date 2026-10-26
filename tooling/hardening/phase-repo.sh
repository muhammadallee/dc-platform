#!/usr/bin/env bash
# Creates the Maven repository a phase's consumer runs resolve from (plan §4.3): a copy of the cold
# baseline repository with the phase's modules installed on top. ~/.m2-baseline itself is the Phase 8
# compatibility reference and must never be installed into (both carry 1.0.0-SNAPSHOT coordinates).
#
#   bash tooling/hardening/phase-repo.sh <phase-slug> <maven -pl list>
#   e.g. bash tooling/hardening/phase-repo.sh phase-01 core/platform-starter-core,errors/platform-starter-errors
#
# Prints the repository path; use it as HH_MAVEN_REPO for the harness. Run only after the same modules
# passed their targeted tests (plan §4.3 step 1). Verifies the baseline manifest before copying.
set -euo pipefail

phase=$1 modules=$2
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
baseline="$HOME/.m2-baseline"
manifest="$root/docs/production-readiness/phase-00-baseline/evidence/m2-baseline-manifest.sha256"
target="$HOME/.m2-hardening/$phase"
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

(cd "$baseline/ae/gov/dubaicustoms/platform" && sha256sum --quiet -c "$manifest") >&2 \
    || { echo "baseline repository no longer matches its manifest: $baseline" >&2; exit 1; }

rm -rf "$target"
mkdir -p "$(dirname "$target")"
cp -r "$baseline" "$target"
# Root, parents, BOM, dependencies and the archetype always travel with the changed modules so the
# consumer resolves one consistent train.
(cd "$root" && mvn -B -ntp "-Dmaven.repo.local=$(native "$target")" \
    -pl "build/platform-parent,build/platform-bom,build/platform-dependencies,build/platform-service-parent,tooling/platform-service-archetype,$modules" \
    -am install -DskipTests -l "$(native "$target/install.log")") >&2 \
    || { echo "install into $target failed (see $target/install.log)" >&2; exit 1; }

pom="$target/ae/gov/dubaicustoms/platform/platform-bom/$(cd "$root" && mvn -q -N help:evaluate -Dexpression=revision -DforceStdout)"
if grep -q '\${revision}' "$pom"/*.pom; then
  echo "installed BOM still contains an unresolved \${revision}: $pom" >&2
  exit 1
fi
echo "$target"
