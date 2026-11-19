#!/usr/bin/env bash
# Creates the Maven repository a Spring-native phase's consumer runs resolve from (plan §5.9): a copy of a seed
# repository with every platform artifact removed, then this checkout installed on top. Nothing cut can survive in it,
# so a kept module that still needs a cut one fails the install instead of passing on a stale artifact.
#
#   bash tooling/hardening/native-repo.sh <phase-slug>        e.g. native-02
#
# The seed supplies third-party artifacts only (default ~/.m2/repository; override with HH_SEED_REPO). The install runs
# offline. Prints the repository path; use it as HH_MAVEN_REPO for the harness. Run after the phase's targeted tests pass.
# Never point it at ~/.m2-baseline or ~/.m2-native-baseline: both are read-only references.
set -euo pipefail

phase=$1
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
seed="${HH_SEED_REPO:-$HOME/.m2/repository}"
target="$HOME/.m2-hardening/$phase"
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

case "$target" in "$HOME/.m2-baseline"*|"$HOME/.m2-native-baseline"*) echo "refusing to write $target" >&2; exit 1;; esac

rm -rf "$target"
mkdir -p "$(dirname "$target")"
cp -r "$seed" "$target"
rm -rf "$target/ae/gov/dubaicustoms/platform"

(cd "$root" && mvn -o -T1 -B -ntp "-Dmaven.repo.local=$(native "$target")" install -DskipTests -Djacoco.skip=true \
    -l "$(native "$target/install.log")") >&2 \
    || { echo "install into $target failed (see $target/install.log)" >&2; exit 1; }

pom="$target/ae/gov/dubaicustoms/platform/platform-bom/$(cd "$root" && mvn -o -q -N help:evaluate -Dexpression=revision -DforceStdout)"
if grep -q '\${revision}' "$pom"/*.pom && ! grep -q '<revision>' "$pom"/*.pom; then
  echo "installed BOM references \${revision} without defining it: $pom" >&2
  exit 1
fi
echo "$target"
