# shellcheck shell=bash
# Consumer-harness library for the capability-hardening initiative (docs/production-readiness/plan-v3.md
# §4.3, §8 Gate F). A scenario script sources this file, then:
#
#   hh_init <phase-slug> <scenario-name>        # run dir, evidence dir, docker network, cleanup trap
#   hh_infra <name> <image> [docker-run args…]  # backing service on the run network (Postgres, Rabbit…)
#   hh_generate <artifactId> <features|->       # archetype app OUTSIDE the repo, from the local catalog
#   hh_add_sources <dir> <package-subpath>      # consumer code (e.g. a probe controller) into the app
#   hh_build_app                                # generated `mvn package`, then a container image
#   hh_app_start [env KEY=VALUE…]               # app container on the run network, port published
#                                               # (HH_APP_NAME selects the container name; default "app")
#   hh_wait_ready [timeout-s]                   # /actuator/health/readiness == UP
#   hh_http <name> <method> <path> [curl args…] # saves status + body to evidence, sets HH_STATUS/HH_BODY
#   hh_expect_status <code> / hh_expect_json <python-expr>
#   hh_app_stop_graceful [grace-s]              # docker stop = real SIGTERM, asserts clean exit + log line
#   hh_finish                                   # teardown, leftover check, single truthful exit code
#
# Everything a run starts carries the docker label dc-hardening=<run id>; hh_finish removes exactly
# those and fails the run if any survive. The app runs in a Linux container (decision HC5) so SIGTERM
# and the graceful-shutdown phase are real, as on OpenShift.
#
# Environment: HH_MAVEN_REPO (default ~/.m2/repository; the platform must already be installed there),
# HH_RUNS_ROOT (default ~/dc-hardening-runs), HH_JRE_IMAGE (default eclipse-temurin:25-jre),
# JAVA_HOME (JDK 25).

set -euo pipefail

HH_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
HH_GROUP_ID="ae.gov.dubaicustoms.platform"
HH_ARCHETYPE_PLUGIN="org.apache.maven.plugins:maven-archetype-plugin:3.1.2"
HH_JRE_IMAGE="${HH_JRE_IMAGE:-eclipse-temurin:25-jre}"
HH_FAILURES=0
HH_ASSERTIONS=0
HH_CONTAINERS=()
HH_PIDS=()

hh_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
hh_log() { printf '[harness %s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$HH_EVIDENCE/harness.log" >&2; }
hh_step() { printf '\n== %s\n' "$*" | tee -a "$HH_EVIDENCE/harness.log" >&2; }

hh_fail() {
  HH_FAILURES=$((HH_FAILURES + 1))
  hh_log "ASSERTION FAILED: $*"
  printf 'FAIL\t%s\n' "$*" >> "$HH_EVIDENCE/assertions.tsv"
}

hh_pass() {
  HH_ASSERTIONS=$((HH_ASSERTIONS + 1))
  printf 'PASS\t%s\n' "$*" >> "$HH_EVIDENCE/assertions.tsv"
}

# Aborts the run (setup could not complete): records the reason, then hh_finish via the EXIT trap.
hh_abort() { hh_fail "ABORT: $*"; exit 1; }

hh_init() {
  local phase=$1 scenario=$2
  HH_RUN_ID="dch-$(date +%Y%m%d%H%M%S)-$$"
  HH_RUN_DIR="${HH_RUNS_ROOT:-$HOME/dc-hardening-runs}/$phase/$scenario-$(date +%Y%m%d-%H%M%S)"
  HH_EVIDENCE="$HH_RUN_DIR/evidence"
  HH_NETWORK="$HH_RUN_ID"
  HH_MAVEN_REPO="${HH_MAVEN_REPO:-$HOME/.m2/repository}"
  mkdir -p "$HH_EVIDENCE"
  : > "$HH_EVIDENCE/assertions.tsv"
  HH_MVN=(mvn -B -ntp "-Dmaven.repo.local=$(hh_native "$HH_MAVEN_REPO")")
  HH_REV=$(cd "$HH_REPO_ROOT" && "${HH_MVN[@]}" -q -N help:evaluate -Dexpression=revision -DforceStdout)
  {
    echo "run_id=$HH_RUN_ID"
    echo "phase=$phase scenario=$scenario"
    echo "platform_commit=$(git -C "$HH_REPO_ROOT" rev-parse HEAD)"
    echo "platform_tree_dirty_paths=$(git -C "$HH_REPO_ROOT" status --porcelain | wc -l)"
    echo "platform_revision=$HH_REV"
    echo "maven_repo=$HH_MAVEN_REPO"
    echo "jre_image=$HH_JRE_IMAGE"
    echo "started=$(date -Iseconds)"
  } > "$HH_EVIDENCE/run-identity.txt"
  trap hh_finish EXIT
  docker network create --label "dc-hardening=$HH_RUN_ID" "$HH_NETWORK" >/dev/null
  hh_log "run $HH_RUN_ID in $HH_RUN_DIR (platform $HH_REV)"
}

hh_infra() {
  local name=$1 image=$2; shift 2
  local cname="$HH_RUN_ID-$name"
  docker run -d --name "$cname" --network "$HH_NETWORK" --network-alias "$name" \
      --label "dc-hardening=$HH_RUN_ID" "$@" "$image" >/dev/null || hh_abort "could not start $name ($image)"
  HH_CONTAINERS+=("$cname")
  hh_log "infra $name ($image) started as $cname"
}

hh_generate() {
  local artifact=$1 features=$2
  HH_APP_ARTIFACT=$artifact
  HH_APP_DIR="$HH_RUN_DIR/app/$artifact"
  local args=(-DarchetypeGroupId="$HH_GROUP_ID" -DarchetypeArtifactId=platform-service-archetype
              -DarchetypeVersion="$HH_REV" -DarchetypeCatalog=local -DgroupId=ae.gov.dubaicustoms.hardening
              -DartifactId="$artifact" -Dpackage=ae.gov.dubaicustoms.hardening -DinteractiveMode=false)
  [ "$features" != "-" ] && args+=("-Dfeatures=$features")
  mkdir -p "$HH_RUN_DIR/app"
  printf '%q ' "${HH_MVN[@]}" "$HH_ARCHETYPE_PLUGIN:generate" "${args[@]}" > "$HH_EVIDENCE/generate.cmd"
  (cd "$HH_RUN_DIR/app" && "${HH_MVN[@]}" "$HH_ARCHETYPE_PLUGIN:generate" "${args[@]}" \
      -l "$(hh_native "$HH_EVIDENCE/generate.log")") || hh_abort "archetype generation failed (generate.log)"
  hh_log "generated $artifact (features=$features) in $HH_APP_DIR"
}

# hh_wait_log <pattern> [timeout-s]: bounded wait for a line in the app container's log; fails the run if absent.
hh_wait_log() {
  local pattern=$1 timeout=${2:-30}
  local deadline=$(( $(date +%s) + timeout ))
  # grep -c, not -q: under pipefail an early-exiting grep SIGPIPEs `docker logs` on a large log.
  until [ "$(docker logs "$HH_APP_CONTAINER" 2>&1 | grep -c "$pattern")" -gt 0 ]; do
    [ "$(date +%s)" -lt "$deadline" ] || { hh_fail "log line '$pattern' not seen within ${timeout}s"; return 1; }
    sleep 0.2
  done
  hh_pass "log line '$pattern' seen"
}

hh_add_sources() {
  local src=$1 subpath=$2 target
  target="$HH_APP_DIR/src/main/java/$subpath"
  mkdir -p "$target"
  cp "$src"/*.java "$target"/ || hh_abort "could not copy consumer sources from $src"
  (cd "$src" && sha256sum ./*.java) > "$HH_EVIDENCE/consumer-sources.sha256"
  hh_log "added consumer sources from $src to $subpath"
}

hh_build_app() {
  (cd "$HH_APP_DIR" && "${HH_MVN[@]}" package -DskipTests -l "$(hh_native "$HH_EVIDENCE/app-build.log")") \
      || hh_abort "generated app build failed (app-build.log)"
  (cd "$HH_APP_DIR" && "${HH_MVN[@]}" -q dependency:tree -DoutputFile="$(hh_native "$HH_EVIDENCE/dependency-tree.txt")") \
      || hh_log "dependency:tree failed (non-fatal)"
  local jar
  jar=$(ls "$HH_APP_DIR"/target/*.jar | grep -v -- '-plain\.jar$' | head -1)
  [ -f "$jar" ] || hh_abort "no executable jar in $HH_APP_DIR/target"
  local ctx="$HH_RUN_DIR/image"
  mkdir -p "$ctx"
  cp "$jar" "$ctx/app.jar"
  # exec form: java is PID 1, so `docker stop` delivers SIGTERM to the JVM itself.
  cat > "$ctx/Dockerfile" <<EOF
FROM $HH_JRE_IMAGE
WORKDIR /app
COPY app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
EOF
  HH_APP_IMAGE="dc-hardening/$HH_APP_ARTIFACT:$HH_RUN_ID"
  docker build -q --label "dc-hardening=$HH_RUN_ID" -t "$HH_APP_IMAGE" "$(hh_native "$ctx")" \
      > "$HH_EVIDENCE/image-build.log" 2>&1 || hh_abort "image build failed (image-build.log)"
  hh_log "app image $HH_APP_IMAGE ($(sha256sum "$jar" | cut -c1-16)…)"
}

hh_app_start() {
  local env_args=()
  local kv
  for kv in "$@"; do env_args+=(-e "$kv"); done
  HH_APP_CONTAINER="$HH_RUN_ID-${HH_APP_NAME:-app}"
  docker run -d --name "$HH_APP_CONTAINER" --network "$HH_NETWORK" --network-alias "${HH_APP_NAME:-app}" \
      --label "dc-hardening=$HH_RUN_ID" -p 127.0.0.1::8080 ${env_args[@]+"${env_args[@]}"} "$HH_APP_IMAGE" >/dev/null \
      || hh_abort "app container did not start"
  HH_CONTAINERS+=("$HH_APP_CONTAINER")
  HH_APP_PORT=$(docker port "$HH_APP_CONTAINER" 8080/tcp | head -1 | sed 's/.*://')
  HH_BASE="http://127.0.0.1:$HH_APP_PORT"
  HH_APP_STARTED_AT=$(date +%s)
  hh_log "app container $HH_APP_CONTAINER on $HH_BASE"
}

hh_wait_ready() {
  local timeout=${1:-120} body
  local deadline=$(( $(date +%s) + timeout ))
  while :; do
    body=$(curl -s --max-time 5 "$HH_BASE/actuator/health/readiness" 2>/dev/null || true)
    if printf '%s' "$body" | grep -q '"status":"UP"'; then
      HH_READY_SECONDS=$(( $(date +%s) - HH_APP_STARTED_AT ))
      hh_pass "app ready in ${HH_READY_SECONDS}s"
      echo "ready_seconds=$HH_READY_SECONDS" >> "$HH_EVIDENCE/run-identity.txt"
      return 0
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$HH_APP_CONTAINER" 2>/dev/null)" != "true" ]; then
      docker logs "$HH_APP_CONTAINER" > "$HH_EVIDENCE/app-startup-failure.log" 2>&1 || true
      hh_abort "app exited before readiness (app-startup-failure.log)"
    fi
    [ "$(date +%s)" -lt "$deadline" ] || hh_abort "app not ready within ${timeout}s (last: ${body:-no response})"
    sleep 1
  done
}

# hh_http <name> <method> <path> [extra curl args…]: response body + status into evidence/http/<name>.*
hh_http() {
  local name=$1 method=$2 path=$3; shift 3
  mkdir -p "$HH_EVIDENCE/http"
  HH_STATUS=$(curl -s -o "$HH_EVIDENCE/http/$name.body" -w '%{http_code}' --max-time 30 \
      -X "$method" "$@" "$HH_BASE$path" || echo 000)
  HH_BODY=$(cat "$HH_EVIDENCE/http/$name.body" 2>/dev/null || true)
  printf '%s %s -> %s\n' "$method" "$path" "$HH_STATUS" > "$HH_EVIDENCE/http/$name.status"
  HH_LAST_HTTP=$name
}

hh_expect_status() {
  if [ "$HH_STATUS" = "$1" ]; then hh_pass "$HH_LAST_HTTP status $1"
  else hh_fail "$HH_LAST_HTTP status: expected $1, got $HH_STATUS (body: ${HH_BODY:0:300})"; fi
}

# hh_expect_json <description> <python expression over `b` (the parsed body)>
hh_expect_json() {
  local description=$1 expr=$2
  if python -I -c "import json,sys; b=json.load(open(sys.argv[1], encoding='utf-8')); sys.exit(0 if ($expr) else 1)" \
      "$(hh_native "$HH_EVIDENCE/http/$HH_LAST_HTTP.body")" 2>>"$HH_EVIDENCE/harness.log"; then
    hh_pass "$HH_LAST_HTTP: $description"
  else
    hh_fail "$HH_LAST_HTTP: $description [$expr] (body: ${HH_BODY:0:300})"
  fi
}

# Real SIGTERM via docker stop; asserts the JVM exited cleanly within the grace period and that Spring
# completed its graceful-shutdown phase. The default grace (45 s) exceeds Spring's default
# spring.lifecycle.timeout-per-shutdown-phase (30 s), so docker never SIGKILLs a drain Spring would finish.
hh_app_stop_graceful() {
  local grace=${1:-45} started ended code
  started=$(date +%s)
  docker stop -t "$grace" "$HH_APP_CONTAINER" >/dev/null
  ended=$(date +%s)
  code=$(docker inspect -f '{{.State.ExitCode}}' "$HH_APP_CONTAINER")
  # One log file per app container (app.log for the default one); a later container never overwrites it.
  docker logs "$HH_APP_CONTAINER" > "$HH_EVIDENCE/${HH_APP_NAME:-app}.log" 2>&1 || true
  echo "shutdown_seconds=$((ended - started)) exit_code=$code" >> "$HH_EVIDENCE/run-identity.txt"
  # 143 = 128+SIGTERM: the JVM honoured the signal; 137 would mean docker had to SIGKILL after the grace.
  case "$code" in
    # 143 = 128 + SIGTERM: the JVM ran its shutdown on the signal. Anything else is a failure:
    # 137 means docker SIGKILLed it after the grace, 0 means it exited on its own before the signal.
    143) hh_pass "app exited on SIGTERM (code 143) in $((ended - started))s" ;;
    *) hh_fail "app exit code $code after SIGTERM (expected 143; 137 = killed after ${grace}s grace)" ;;
  esac
  if grep -q "Graceful shutdown complete" "$HH_EVIDENCE/${HH_APP_NAME:-app}.log"; then
    hh_pass "graceful shutdown completed (logged)"
  else
    hh_fail "no 'Graceful shutdown complete' line in ${HH_APP_NAME:-app}.log"
  fi
}

hh_finish() {
  local rc=$?
  trap - EXIT
  set +e
  local pid
  for pid in ${HH_PIDS[@]+"${HH_PIDS[@]}"}; do kill "$pid" 2>/dev/null; done
  if [ -n "${HH_APP_CONTAINER:-}" ] && [ ! -f "$HH_EVIDENCE/app.log" ]; then
    docker logs "$HH_APP_CONTAINER" > "$HH_EVIDENCE/app.log" 2>&1
  fi
  local c
  for c in ${HH_CONTAINERS[@]+"${HH_CONTAINERS[@]}"}; do
    case "$c" in *-app*) ;; *) docker logs "$c" > "$HH_EVIDENCE/infra-${c#"$HH_RUN_ID"-}.log" 2>&1 ;; esac
  done
  docker ps -aq --filter "label=dc-hardening=$HH_RUN_ID" | xargs -r docker rm -f >/dev/null 2>&1
  docker network rm "$HH_NETWORK" >/dev/null 2>&1
  [ -n "${HH_APP_IMAGE:-}" ] && docker rmi -f "$HH_APP_IMAGE" >/dev/null 2>&1
  local leftovers
  leftovers=$(docker ps -aq --filter "label=dc-hardening=$HH_RUN_ID" | wc -l)
  docker ps -a --format '{{.Names}}\t{{.Status}}' --filter "label=dc-hardening=$HH_RUN_ID" > "$HH_EVIDENCE/cleanup.txt"
  if [ "$leftovers" -ne 0 ]; then
    hh_fail "cleanup left $leftovers container(s)"
  else
    hh_pass "cleanup: no containers left for $HH_RUN_ID"
  fi
  local verdict=PASS
  if [ "$rc" -ne 0 ] || [ "$HH_FAILURES" -ne 0 ]; then verdict=FAIL; fi
  {
    echo "finished=$(date -Iseconds)"
    echo "assertions_passed=$HH_ASSERTIONS assertions_failed=$HH_FAILURES script_rc=$rc"
    echo "verdict=$verdict"
  } >> "$HH_EVIDENCE/run-identity.txt"
  printf '\nHARNESS %s — %d passed, %d failed — evidence: %s\n' "$verdict" "$HH_ASSERTIONS" "$HH_FAILURES" "$HH_EVIDENCE" >&2
  [ "$verdict" = PASS ] && exit 0 || exit 1
}
