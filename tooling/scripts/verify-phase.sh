#!/usr/bin/env bash
# Human-side phase verification. Usage: ./tooling/scripts/verify-phase.sh 03
# Runs the objective checks itself, then prints the phase's Acceptance block for you to run
# any interactive/scratch-app steps by hand. Never trust an agent's "it passed" — reproduce it.
set -uo pipefail
PHASE="${1:?usage: verify-phase.sh <two-digit phase, e.g. 03>}"
SPEC=$(ls specs/phase-${PHASE}-*.md 2>/dev/null | head -1)
[ -z "${SPEC}" ] && { echo "FAIL: no spec found for phase ${PHASE}"; exit 1; }
FAILURES=0
step() { printf '\n== %s\n' "$1"; }
check() { if eval "$2" >/dev/null 2>&1; then echo "PASS: $1"; else echo "FAIL: $1"; FAILURES=$((FAILURES+1)); fi; }

step "1/5 Git hygiene"
check "working tree clean" "git diff --quiet && git diff --cached --quiet"

step "2/5 Full reactor build (this is the main gate)"
if mvn -T1C verify; then echo "PASS: mvn -T1C verify"; else echo "FAIL: mvn -T1C verify"; FAILURES=$((FAILURES+1)); fi

step "3/5 Bookkeeping"
check "CHANGELOG has Unreleased entries" "awk '/## \\[Unreleased\\]/{f=1;next}/^## /{f=0}f' CHANGELOG.md | grep -q '[a-zA-Z]'"
check "no stray SNAPSHOT third-party versions outside build/" "! grep -rn '<version>.*SNAPSHOT' --include=pom.xml . | grep -v 'revision' | grep -v '^./build/' | grep -q ."

step "4/5 Phase Acceptance block from ${SPEC} — run these yourself now:"
awk '/^## Acceptance/{f=1} f{print} f&&/^## /&&!/^## Acceptance/{exit}' "${SPEC}"

step "5/5 Result"
if [ "${FAILURES}" -eq 0 ]; then
  echo "AUTOMATED CHECKS: PASS — complete the manual Acceptance steps above, then merge/tag."
  exit 0
else
  echo "AUTOMATED CHECKS: FAIL (${FAILURES}) — do not merge. Re-open the phase with Claude Code."
  exit 1
fi
