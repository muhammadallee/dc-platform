#!/usr/bin/env bash
# Smoke matrix (phase-15 §B): boot example-golden-path across a curated set of capability toggles and
# assert, for each, that /actuator/health is UP and /actuator/platform matches the checked-in
# expectation table (tooling/smoke/expectations.tsv). Docker-free: H2 + in-memory transport back every
# row. Wire into CI on PRs touching more than one capability; run the full table nightly.
#
# Why toggles, not per-combo rebuilds: the app is packaged ONCE and each row flips kill-switch system
# properties (dc.platform.<cap>.enabled) on the same jar. Recompiling per starter combination cannot
# fit any sane time budget, and the assertion target — the capability report — is identical either way.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

EXPECTATIONS="tooling/smoke/expectations.tsv"
PORT="${SMOKE_PORT:-18080}"
BASE="http://localhost:${PORT}"
READY_TIMEOUT=60

echo "== Smoke matrix for example-golden-path (port ${PORT}) =="

# SMOKE_SKIP_BUILD=1 reuses an already-packaged jar (fast local iteration); CI leaves it unset.
if [ "${SMOKE_SKIP_BUILD:-0}" != "1" ]; then
  echo "== Build the platform and package the example =="
  mvn -q -pl examples/example-golden-path -am package -DskipTests
fi

JAR="$(ls examples/example-golden-path/target/example-golden-path-*.jar | grep -vE '(-sources|-javadoc)\.jar$' | head -1)"
[ -n "$JAR" ] || { echo "SMOKE FAILED: golden-path jar not found"; exit 1; }
echo "   jar: $JAR"

APP_PID=""
cleanup() { [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true; }
trap cleanup EXIT

# Boot the jar with the given -D args; wait until health is UP or fail. Echoes nothing; sets APP_PID.
boot() {
  local jvm_args="$1"
  # shellcheck disable=SC2086
  java $jvm_args -jar "$JAR" --server.port="$PORT" >/tmp/smoke-app.log 2>&1 &
  APP_PID=$!
  local waited=0
  until curl -sf "${BASE}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
    sleep 1; waited=$((waited + 1))
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      echo "   app exited during startup; last log lines:"; tail -20 /tmp/smoke-app.log; return 1
    fi
    if [ "$waited" -ge "$READY_TIMEOUT" ]; then
      echo "   app not healthy after ${READY_TIMEOUT}s"; return 1
    fi
  done
}

stop() { [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; APP_PID=""; }

FAILURES=0
run_row() {
  local id="$1" jvm_args="$2" present="$3" absent="$4"
  [ "$jvm_args" = "-" ] && jvm_args=""
  echo "-- row: ${id}  (args: ${jvm_args:-none})"

  if ! boot "$jvm_args"; then
    echo "   FAIL[${id}]: did not reach health UP"; FAILURES=$((FAILURES + 1)); stop; return
  fi

  local platform; platform="$(curl -sf "${BASE}/actuator/platform" || echo '[]')"

  local cap ok=1
  if [ -n "$present" ]; then
    IFS=',' read -ra caps <<< "$present"
    for cap in "${caps[@]}"; do
      if ! grep -q "\"name\":\"${cap}\"" <<< "$platform"; then
        echo "   FAIL[${id}]: expected capability '${cap}' present, but it was not"; ok=0
      fi
    done
  fi
  if [ -n "$absent" ]; then
    IFS=',' read -ra caps <<< "$absent"
    for cap in "${caps[@]}"; do
      if grep -q "\"name\":\"${cap}\"" <<< "$platform"; then
        echo "   FAIL[${id}]: expected capability '${cap}' absent, but it was present"; ok=0
      fi
    done
  fi

  # Security stays on in every row: an unauthenticated business call must be rejected (401/403).
  if [ "$id" = "baseline" ]; then
    local code; code="$(curl -s -o /dev/null -w '%{http_code}' "${BASE}/orders/1")"
    if [ "$code" != "401" ] && [ "$code" != "403" ]; then
      echo "   FAIL[${id}]: unauthenticated /orders/1 returned ${code}, expected 401/403 (security off?)"; ok=0
    fi
  fi

  if [ "$ok" = 1 ]; then echo "   PASS[${id}]"; else FAILURES=$((FAILURES + 1)); fi
  stop
}

# Read the table (skip comments/blank lines); columns are tab-separated.
while IFS=$'\t' read -r id jvm_args present absent; do
  case "$id" in ''|\#*) continue ;; esac
  run_row "$id" "$jvm_args" "$present" "${absent:-}"
done < "$EXPECTATIONS"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "SMOKE MATRIX OK"
else
  echo "SMOKE MATRIX FAILED: ${FAILURES} row(s) failed"; exit 1
fi
