#!/usr/bin/env bash
# Runs a guided lab exactly as a reader would (Spring-native plan §5.10): every ```bash lab-step block in order, every
# ```bash lab-check block as a check that must exit 0, every ```<lang> lab-file PATH block written to PATH. One honest
# exit code: 0 only if every block ran and every check passed.
#
#   bash tooling/labs/run-lab.sh <capability>        # e.g. first-service
#
# - Runs in a fresh directory outside the repository: ${LAB_WORK_ROOT:-~/dc-labs}/<capability>-<timestamp>.
# - The lab gets PLATFORM_HOME (this checkout), LAB_DIR (that directory) and LAB_RUN (a unique run id).
# - A lab that starts a process writes its PID to "$LAB_DIR/<name>.pid"; a lab that starts a container labels it
#   "dc-lab=$LAB_RUN". Whatever happens, the runner then stops exactly those, and nothing else.
# - The lab's own step 0 builds its dedicated local Maven repository (tooling/hardening/native-repo.sh).
set -uo pipefail

capability=${1:?usage: run-lab.sh <capability>}
if [[ ! "$capability" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  echo "run-lab: '$capability' is not a lab name" >&2
  exit 2
fi
PLATFORM_HOME=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
lab="$PLATFORM_HOME/docs/labs/$capability/index.md"
[ -f "$lab" ] || { echo "run-lab: no lab at $lab" >&2; exit 2; }

LAB_RUN="lab-$capability-$(date +%Y%m%d-%H%M%S)"
LAB_DIR="${LAB_WORK_ROOT:-$HOME/dc-labs}/$LAB_RUN"
mkdir -p "$LAB_DIR"
script="$LAB_DIR/lab.sh"
python -I -X utf8 "$PLATFORM_HOME/tooling/labs/lab_script.py" "$lab" > "$LAB_DIR/steps.sh" \
    || { echo "run-lab: could not read the lab's blocks" >&2; exit 2; }

{
  echo 'set -euo pipefail'
  echo 'lab_fail() { echo "LAB FAIL at step $LAB_STEP (status $1)" >&2; exit 1; }'
  echo 'trap '"'"'echo "LAB FAIL at step $LAB_STEP" >&2'"'"' ERR'
  echo 'cd "$LAB_DIR"'
  cat "$LAB_DIR/steps.sh"
} > "$script"

# Stops what the lab left running and confirms it is gone; returns non-zero if something survived.
cleanup() {
  local pidfile pid waited survivors=0
  for pidfile in "$LAB_DIR"/*.pid; do
    [ -f "$pidfile" ] || continue
    pid=$(cat "$pidfile")
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      for waited in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
      if kill -0 "$pid" 2>/dev/null; then
        kill -9 "$pid" 2>/dev/null; sleep 1
      fi
      if kill -0 "$pid" 2>/dev/null; then
        echo "run-lab: process $pid ($(basename "$pidfile")) is still running" >&2
        survivors=1
      else
        echo "run-lab: stopped process $pid ($(basename "$pidfile"))"
      fi
    fi
    rm -f "$pidfile"
  done
  if command -v docker >/dev/null 2>&1; then
    docker ps -aq --filter "label=dc-lab=$LAB_RUN" | xargs -r docker rm -f >/dev/null
    if [ -n "$(docker ps -aq --filter "label=dc-lab=$LAB_RUN")" ]; then
      echo "run-lab: containers labelled dc-lab=$LAB_RUN are still there" >&2
      survivors=1
    fi
  fi
  return "$survivors"
}
trap cleanup EXIT

echo "run-lab: $capability in $LAB_DIR"
PLATFORM_HOME="$PLATFORM_HOME" LAB_DIR="$LAB_DIR" LAB_RUN="$LAB_RUN" bash "$script" 2>&1 | tee "$LAB_DIR/lab.log"
rc=${PIPESTATUS[0]}
trap - EXIT
cleanup || { [ "$rc" -ne 0 ] || rc=3; }
if [ "$rc" -eq 0 ]; then
  echo "LAB PASS — $capability — log: $LAB_DIR/lab.log"
else
  echo "LAB FAIL — $capability (exit $rc) — log: $LAB_DIR/lab.log"
fi
exit "$rc"
