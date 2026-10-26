#!/usr/bin/env bash
# The generator gate — the DX contract, executable end to end (runbooks/claude-code-handoff.md). Wired into
# CI as the `generator-gate` job.
#
#   1. install the platform (tests skipped: the tested reactor build is the separate `mvn verify` gate)
#      into an ISOLATED Maven repository, and record the archetype that was installed;
#   2. reject invalid `features` input (unknown token, substring lookalike, spaces, none+feature);
#   3. for every scenario — all 8 combinations of the optional tokens messaging/data/restclient, one of
#      them with every optional property omitted, one relocated (groupId != package, hyphenated
#      artifactId, path with a space) — generate OUTSIDE the checkout, assert the generated files, run the
#      generated `mvn verify` (every expected test class must run: no zero-test or skipped passes), check
#      the executable jar, boot it with `java -jar` and probe it over real HTTP: readiness, capability
#      report (present AND absent capabilities), 401 for missing/untrusted/expired/malformed tokens, 200
#      for a token from a loopback JWKS issuer, correlation echo, structured JSON log events;
#   4. for the requested service (data,restclient) additionally: the documented `mvn spring-boot:run`
#      launch, the `local` profile (console logs), `prod` with each mandatory setting missing (must fail
#      at startup with an actionable message), and `prod` with externally supplied fixture settings,
#      booted twice against the same file database (migration applied once, then "up to date").
#
# Environment (all optional):
#   GP_MAVEN_REPO     Maven local repository for EVERY Maven process (default: fresh $GP_WORKDIR/m2, cold).
#                     Never point it at a shared cache you want untouched: step 1 installs into it.
#   GP_WORKDIR        scenario/work directory (default: mktemp -d; kept when the gate fails)
#   GP_EVIDENCE       reports + sanitized logs (default: $GP_WORKDIR/evidence) — CI uploads it
#   GP_SCENARIOS      comma-separated scenario ids, or "all" (default)
#   GP_SKIP_INSTALL=1 reuse the platform already installed in GP_MAVEN_REPO (local iteration only)
#   GP_SLA_SECONDS    per-service DX SLA: generate + verify + packaged boot of ONE service (default 600)
#   GP_THREADS        -T value for the platform install (default 1C)
#
# Prerequisites: JDK 25 (java on PATH), Maven 3.9+, curl, unzip. Maven Central reachable (no offline
# claim); no Docker, credentials, or other network. Bash on Linux/macOS; on Windows it runs under Git Bash
# (Windows generation/build/launch is validated separately — see docs/chassis-audit.md).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# maven-archetype-plugin 3.1.2 is the version the docs publish. 3.4.0 also generates project-less on Maven
# 3.9.x (verified), but its bundled Groovy cannot parse Java 25 class files, so the archetype must never
# rely on archetype-post-generate.groovy; feature pruning stays in Velocity (decision D66, docs/chassis-audit.md).
ARCHETYPE_PLUGIN="org.apache.maven.plugins:maven-archetype-plugin:3.1.2"
GROUP_ID="ae.gov.dubaicustoms.platform"
SLA_SECONDS="${GP_SLA_SECONDS:-600}"
READY_TIMEOUT=120
EXIT_TIMEOUT=120

native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

WORKDIR="${GP_WORKDIR:-$(mktemp -d)}"
mkdir -p "$WORKDIR"
WORKDIR="$(cd "$WORKDIR" && pwd)"
EVIDENCE="${GP_EVIDENCE:-$WORKDIR/evidence}"
MAVEN_REPO="${GP_MAVEN_REPO:-$WORKDIR/m2}"
mkdir -p "$EVIDENCE" "$MAVEN_REPO"
EVIDENCE="$(cd "$EVIDENCE" && pwd)"
MVN=(mvn -B -ntp "-Dmaven.repo.local=$(native_path "$MAVEN_REPO")")
TIMEOUT=()
command -v timeout >/dev/null 2>&1 && TIMEOUT=(timeout 1200)

RESULT=FAIL
PIDS=()
IDP_DIR=""
cleanup() {
  local pid
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do kill "$pid" 2>/dev/null || true; done
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do wait "$pid" 2>/dev/null || true; done
  if [ -n "$IDP_DIR" ]; then touch "$IDP_DIR/stop" 2>/dev/null || true; fi
  if [ "$RESULT" = PASS ] && [ -z "${GP_WORKDIR:-}" ]; then
    rm -rf "$WORKDIR"
  else
    echo "work dir kept: $WORKDIR   evidence: $EVIDENCE"
  fi
}
trap cleanup EXIT

SUMMARY="$EVIDENCE/summary.tsv"
printf 'scenario\tfeatures\ttests\tseconds\tresult\n' > "$SUMMARY"
# stderr, so a failure inside $(...) is still shown; the non-zero exit then stops the script (set -e).
fail() { echo "GATE FAILED: $*" >&2; printf '%s\n' "FAILED: $*" >> "$EVIDENCE/failure.txt"; exit 1; }
step() { printf '\n== %s\n' "$*"; }

# ---------------------------------------------------------------------------------------------------
step "0 Preconditions"
java -version 2>&1 | head -1 | grep -Eq '"25(\.|")' || fail "JDK 25 required on PATH (found: $(java -version 2>&1 | head -1))"
for tool in mvn curl unzip; do command -v "$tool" >/dev/null 2>&1 || fail "$tool not on PATH"; done
echo "   workdir=$WORKDIR  repo=$MAVEN_REPO  evidence=$EVIDENCE"

step "1 Install the platform into the isolated repository"
START=$(date +%s)
if [ "${GP_SKIP_INSTALL:-0}" != 1 ]; then
  "${MVN[@]}" -T"${GP_THREADS:-1C}" install -DskipTests -l "$(native_path "$EVIDENCE/platform-install.log")" \
      || fail "platform install (see $EVIDENCE/platform-install.log)"
fi
REV=$("${MVN[@]}" -q -N help:evaluate -Dexpression=revision -DforceStdout)
ARTIFACTS="$MAVEN_REPO/ae/gov/dubaicustoms/platform"
ARCHETYPE_JAR="$ARTIFACTS/platform-service-archetype/$REV/platform-service-archetype-$REV.jar"
TEST_API_JAR="$ARTIFACTS/platform-test-api/$REV/platform-test-api-$REV.jar"
[ -f "$ARCHETYPE_JAR" ] || fail "archetype not installed at $ARCHETYPE_JAR"
[ -f "$TEST_API_JAR" ] || fail "platform-test-api not installed at $TEST_API_JAR"
echo "   train=$REV  archetype=$GROUP_ID:platform-service-archetype:$REV" \
     "sha256=$(sha256sum "$ARCHETYPE_JAR" | cut -c1-16)…" | tee "$EVIDENCE/archetype-identity.txt"
unzip -p "$ARCHETYPE_JAR" META-INF/maven/archetype-metadata.xml | grep -q "<defaultValue>$REV</defaultValue>" \
    || fail "archetype platformVersion default is not the train ($REV)"

# JDK-only helper (loopback JWKS issuer via the INSTALLED test kit, free ports, JSON log checks), compiled once.
CP_SEP=':'; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;; esac
APIGUARDIAN_JAR=$(ls "$MAVEN_REPO"/org/apiguardian/apiguardian-api/*/apiguardian-api-*.jar 2>/dev/null | grep -v sources | head -1 || true)
SUPPORT_CLASSES="$WORKDIR/support-classes"
javac -d "$(native_path "$SUPPORT_CLASSES")" \
    -cp "$(native_path "$TEST_API_JAR")${APIGUARDIAN_JAR:+$CP_SEP$(native_path "$APIGUARDIAN_JAR")}" \
    "$(native_path "$REPO_ROOT/tooling/scripts/support/GateSupport.java")" || fail "could not compile GateSupport"
SUPPORT=(java -cp "$(native_path "$SUPPORT_CLASSES")$CP_SEP$(native_path "$TEST_API_JAR")" GateSupport)

step "2 Loopback JWKS issuer (TestJwtIssuer) for real token validation"
IDP_DIR="$WORKDIR/idp"
rm -rf "$IDP_DIR"; mkdir -p "$IDP_DIR"
"${SUPPORT[@]}" idp "$(native_path "$IDP_DIR")" > "$EVIDENCE/idp.log" 2>&1 &
PIDS+=($!)
for _ in $(seq 1 120); do [ -f "$IDP_DIR/ready" ] && break; sleep 0.5; done
[ -f "$IDP_DIR/ready" ] || fail "JWKS issuer did not start (see $EVIDENCE/idp.log)"
JWKS=$(cat "$IDP_DIR/jwks-uri"); TOKEN=$(cat "$IDP_DIR/token-valid")
UNTRUSTED=$(cat "$IDP_DIR/token-untrusted"); EXPIRED=$(cat "$IDP_DIR/token-expired")
echo "   jwks=$JWKS"

# ---------------------------------------------------------------------------------------------------
generate() { # log dir groupId artifactId package features(-=omit) platformVersion(-=omit)
  local log=$1 dir=$2 group=$3 artifact=$4 pkg=$5 features=$6 version=$7
  local args=(-DarchetypeGroupId="$GROUP_ID" -DarchetypeArtifactId=platform-service-archetype
              -DarchetypeVersion="$REV" -DgroupId="$group" -DartifactId="$artifact" -Dpackage="$pkg"
              -DinteractiveMode=false)
  [ "$features" != "-" ] && args+=("-Dfeatures=$features")
  [ "$version" != "-" ] && args+=("-DplatformVersion=$version")
  mkdir -p "$dir"
  (cd "$dir" && "${MVN[@]}" "$ARCHETYPE_PLUGIN:generate" "${args[@]}" -l "$(native_path "$log")")
}

step "3 Invalid feature input is rejected at generation"
NEG="$WORKDIR/negative"
for bad in kafka database "data, restclient" none,data data,data; do
  slug=$(printf '%s' "$bad" | tr -c 'a-z' '_')
  if generate "$EVIDENCE/negative-$slug.log" "$NEG" com.dc.demo "neg-$slug" com.dc.demo "$bad" "$REV"; then
    fail "features='$bad' was accepted"
  fi
  grep -q "unsupported features value" "$EVIDENCE/negative-$slug.log" \
      || fail "features='$bad' failed without the actionable message"
  [ ! -s "$NEG/neg-$slug/pom.xml" ] && [ ! -d "$NEG/neg-$slug/src" ] \
      || fail "features='$bad' left a buildable partial project"
  echo "   rejected: '$bad'"
done

# ---------------------------------------------------------------------------------------------------
has() { case ",$1," in *",$2,"*) return 0 ;; *) return 1 ;; esac; }

assert_generated() { # project package features artifactId
  local p=$1 pkg=$2 features=$3 artifact=$4 src
  src="$p/src/main/java/$(printf '%s' "$pkg" | tr . /)"
  for f in pom.xml .gitignore README.md CLAUDE.md AGENTS.md .mcp.json catalog-info.yaml \
           src/main/resources/application.yml; do
    [ -f "$p/$f" ] || fail "$artifact: generated service has no $f"
  done
  grep -q "^package $pkg;" "$src/Application.java" || fail "$artifact: Application.java not relocated to $pkg"
  grep -q "<version>$REV</version>" <(sed -n '/<parent>/,/<\/parent>/p' "$p/pom.xml") \
      || fail "$artifact: parent is not platform-service-parent:$REV"
  grep -q "dc.platform/train: \"$REV\"" "$p/catalog-info.yaml" || fail "$artifact: catalog-info.yaml train not $REV"
  grep -Eq "importPackages\(\"$pkg\"\)" "$p/src/test/java/$(printf '%s' "$pkg" | tr . /)/PlatformConformanceTest.java" \
      || fail "$artifact: conformance test does not scan $pkg"
  # Template leftovers: Velocity directives or unresolved archetype properties (legit Spring ${...} stay).
  if grep -rEn '^[[:space:]]*#(set|if|elseif|else|end)\b|\$\{(groupId|artifactId|version|package|platformVersion|features|owner|gitignore)\}' \
       "$p/src" "$p/pom.xml" "$p/catalog-info.yaml" > "$EVIDENCE/$artifact-leftovers.txt"; then
    fail "$artifact: template leftovers (see $EVIDENCE/$artifact-leftovers.txt)"
  fi
  local feature starter sample
  for feature in messaging data restclient; do
    case $feature in
      messaging) starter=platform-starter-messaging-inmemory; sample="$src/messaging/OrderEvents.java" ;;
      data) starter=platform-starter-data-jpa; sample="$src/data/Note.java" ;;
      restclient) starter=platform-starter-restclient; sample="$src/client/GreetingClient.java" ;;
    esac
    if has "$features" "$feature"; then
      grep -q "<artifactId>$starter</artifactId>" "$p/pom.xml" || fail "$artifact: $feature enabled but $starter missing"
      [ -s "$sample" ] || fail "$artifact: $feature enabled but $sample is empty"
    else
      ! grep -q "<artifactId>$starter</artifactId>" "$p/pom.xml" || fail "$artifact: $feature disabled but $starter present"
      [ ! -s "$sample" ] || fail "$artifact: $feature disabled but $sample has content"
    fi
  done
  if has "$features" data; then
    [ -s "$p/src/main/resources/db/migration/V1__create_note.sql" ] || fail "$artifact: data enabled but no migration"
  fi
  if has "$features" restclient; then
    grep -q '"${app.greeting.base-url}"' "$src/client/GreetingClient.java" \
        || fail "$artifact: Spring placeholder in GreetingClient was not preserved"
  fi
}

assert_tests() { # project features artifactId -> echoes the executed test count
  local p=$1 features=$2 artifact=$3 reports="$1/target/surefire-reports" total=0 cls xml tests
  local expected=(ApplicationSmokeTest HelloControllerTest PlatformConformanceTest SecurityIntegrationTest StructuredLoggingTest)
  has "$features" data && expected+=(NoteRepositoryTest)
  has "$features" restclient && expected+=(GreetingClientTest)
  for cls in "${expected[@]}"; do
    xml=$(ls "$reports"/TEST-*."$cls".xml 2>/dev/null | head -1)
    [ -n "$xml" ] || fail "$artifact: $cls did not run"
    tests=$(grep -o '<testsuite [^>]*' "$xml" | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*')
    [ "${tests:-0}" -gt 0 ] || fail "$artifact: $cls executed zero tests"
    grep -o '<testsuite [^>]*' "$xml" | grep -Eq ' failures="0"' || fail "$artifact: $cls has failures"
    grep -o '<testsuite [^>]*' "$xml" | grep -Eq ' errors="0"' || fail "$artifact: $cls has errors"
    grep -o '<testsuite [^>]*' "$xml" | grep -Eq ' skipped="0"' || fail "$artifact: $cls skipped tests"
    total=$((total + tests))
  done
  echo "$total"
}

assert_jar() { # project package features artifactId -> echoes jar path
  local p=$1 pkg=$2 features=$3 artifact=$4 jar listing manifest
  jar=$(ls "$p"/target/"$artifact"-*.jar 2>/dev/null | grep -vE '(-sources|-javadoc|\.original)' | head -1)
  [ -n "$jar" ] || fail "$artifact: no executable jar"
  manifest=$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r')
  grep -q '^Main-Class: org.springframework.boot.loader.launch.JarLauncher' <<<"$manifest" || fail "$artifact: jar not repackaged"
  grep -q "^Start-Class: $pkg.Application" <<<"$manifest" || fail "$artifact: wrong Start-Class"
  listing=$(unzip -l "$jar")
  for entry in "BOOT-INF/classes/application.yml" "BOOT-INF/lib/platform-logging-autoconfigure-$REV.jar" \
               "BOOT-INF/lib/logstash-logback-encoder-"; do
    grep -q "$entry" <<<"$listing" || fail "$artifact: jar lacks $entry"
  done
  if has "$features" data; then
    grep -q "BOOT-INF/classes/db/migration/V1__create_note.sql" <<<"$listing" || fail "$artifact: migration not packaged"
    grep -q "BOOT-INF/lib/spring-boot-flyway-" <<<"$listing" || fail "$artifact: Boot Flyway integration not packaged"
  fi
  echo "$jar"
}

APP_PID=""
APP_PORT=""
RUN_ID=""
start_app() { # log cmd... (cmd gets --server.port and run-id args appended)
  local log=$1; shift
  APP_PORT=$("${SUPPORT[@]}" freeport)
  RUN_ID="gate-$(date +%s)-$RANDOM"
  "$@" --server.port="$APP_PORT" --management.info.env.enabled=true --info.gate.run-id="$RUN_ID" > "$log" 2>&1 &
  APP_PID=$!
  PIDS+=("$APP_PID")
}

await_ready() { # log
  local waited=0
  until curl -sf --max-time 5 "http://127.0.0.1:$APP_PORT/actuator/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; do
    kill -0 "$APP_PID" 2>/dev/null || { tail -40 "$1"; return 1; }
    sleep 1; waited=$((waited + 1))
    [ "$waited" -lt "$READY_TIMEOUT" ] || { echo "   not ready after ${READY_TIMEOUT}s"; tail -40 "$1"; return 1; }
  done
  # Identity: the process answering is the one this gate spawned (its unique run id is in /actuator/info).
  curl -sf --max-time 5 "http://127.0.0.1:$APP_PORT/actuator/info" | grep -q "$RUN_ID" \
      || { echo "   port $APP_PORT is answered by a process this gate did not start"; return 1; }
}

stop_app() {
  [ -n "$APP_PID" ] || return 0
  kill "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=""
}

expect_startup_failure() { # label pattern log cmd...
  local label=$1 pattern=$2 log=$3 waited=0 code; shift 3
  start_app "$log" "$@"
  while kill -0 "$APP_PID" 2>/dev/null; do
    sleep 1; waited=$((waited + 1))
    if curl -sf --max-time 2 "http://127.0.0.1:$APP_PORT/actuator/health" >/dev/null 2>&1 || [ "$waited" -ge "$EXIT_TIMEOUT" ]; then
      stop_app; fail "$label: started although mandatory configuration was missing"
    fi
  done
  code=0; wait "$APP_PID" || code=$?
  APP_PID=""
  [ "$code" -ne 0 ] || fail "$label: exited 0 without serving"
  grep -q "$pattern" "$log" || fail "$label: failure did not name the fix ('$pattern' not in $log)"
  echo "   $label: refused to start (exit $code) — '$pattern'"
}

status_of() { # expected-status label curl-args...
  local expected=$1 label=$2 code; shift 2
  code=$(curl -s -o "$WORKDIR/body" -D "$WORKDIR/headers" -w '%{http_code}' --max-time 10 "$@" || true)
  [ "$code" = "$expected" ] || fail "$label: HTTP $code (expected $expected): $(head -c 300 "$WORKDIR/body")"
}

probe_http() { # label features log artifact [prod]
  local label=$1 features=$2 log=$3 artifact=$4 prod=${5:-} base="http://127.0.0.1:$APP_PORT" cid cap
  cid=$(printf '%032x' "$RANDOM$RANDOM$RANDOM" | tail -c 32)
  status_of 401 "$label unauthenticated /hello" "$base/hello"
  grep -qi '^content-type: application/problem+json' "$WORKDIR/headers" || fail "$label: 401 is not RFC 9457"
  status_of 401 "$label untrusted token" -H "Authorization: Bearer $UNTRUSTED" "$base/hello"
  status_of 401 "$label expired token" -H "Authorization: Bearer $EXPIRED" "$base/hello"
  status_of 401 "$label malformed token" -H "Authorization: Bearer not-a-jwt" "$base/hello"
  status_of 200 "$label authenticated /hello" -H "Authorization: Bearer $TOKEN" -H "X-Correlation-Id: $cid" "$base/hello?name=gate"
  [ "$(cat "$WORKDIR/body")" = "Hello, gate!" ] || fail "$label: unexpected /hello body: $(cat "$WORKDIR/body")"
  grep -qi "^x-correlation-id: $cid" "$WORKDIR/headers" || fail "$label: correlation id not echoed"
  if [ -n "$prod" ]; then
    status_of 401 "$label anonymous /actuator/platform in prod" "$base/actuator/platform"
    status_of 200 "$label authenticated /actuator/platform" -H "Authorization: Bearer $TOKEN" "$base/actuator/platform"
  else
    status_of 200 "$label /actuator/platform" "$base/actuator/platform"
  fi
  local report; report=$(cat "$WORKDIR/body")
  for cap in messaging:messaging data:data-jpa restclient:restclient; do
    if has "$features" "${cap%%:*}"; then
      grep -q "\"name\":\"${cap#*:}\"" <<<"$report" || fail "$label: capability ${cap#*:} missing from /actuator/platform"
    else
      ! grep -q "\"name\":\"${cap#*:}\"" <<<"$report" || fail "$label: capability ${cap#*:} reported without its starter"
    fi
  done
  sleep 1 # let the request's log events flush before reading the log
  "${SUPPORT[@]}" logcheck "$(native_path "$log")" "$artifact" "$cid" "$TOKEN" "$UNTRUSTED" "$EXPIRED" \
      || fail "$label: structured log contract ($log)"
  if has "$features" data; then
    grep -q "Successfully applied 1 migration" "$log" || fail "$label: Flyway did not apply the sample migration"
  fi
}

run_scenario() { # id features groupId artifactId package platformVersion subdir
  local id=$1 features=$2 group=$3 artifact=$4 pkg=$5 version=$6 subdir=$7 t0 dir p tests jar log effective
  t0=$(date +%s)
  effective=$([ "$features" = "-" ] && echo none || echo "$features")
  step "Scenario $id (features=$effective, groupId=$group, artifactId=$artifact, package=$pkg)"
  dir="$WORKDIR/scenarios/$id${subdir:+/$subdir}"
  generate "$EVIDENCE/$id-generate.log" "$dir" "$group" "$artifact" "$pkg" "$features" "$version" \
      || fail "$id: generation failed (see $EVIDENCE/$id-generate.log)"
  p="$dir/$artifact"
  assert_generated "$p" "$pkg" "$effective" "$artifact"
  (cd "$p" && ${TIMEOUT[@]+"${TIMEOUT[@]}"} "${MVN[@]}" verify -l "$(native_path "$EVIDENCE/$id-verify.log")") \
      || fail "$id: generated mvn verify failed (see $EVIDENCE/$id-verify.log)"
  tests=$(assert_tests "$p" "$effective" "$artifact")
  mkdir -p "$EVIDENCE/$id-surefire" && cp "$p"/target/surefire-reports/*.xml "$EVIDENCE/$id-surefire/"
  jar=$(assert_jar "$p" "$pkg" "$effective" "$artifact")
  log="$EVIDENCE/$id-jar.log"
  local extra=()
  has "$effective" restclient && extra+=("--app.greeting.base-url=http://127.0.0.1:9")
  start_app "$log" java -jar "$(native_path "$jar")" \
      "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=$JWKS" ${extra[@]+"${extra[@]}"}
  await_ready "$log" || fail "$id: packaged jar did not become ready"
  probe_http "$id jar" "$effective" "$log" "$artifact"
  stop_app
  local secs=$(( $(date +%s) - t0 ))
  [ "$secs" -le "$SLA_SECONDS" ] || fail "$id: ${secs}s exceeds the ${SLA_SECONDS}s per-service DX SLA"
  printf '%s\t%s\t%s\t%s\tPASS\n' "$id" "$effective" "$tests" "$secs" >> "$SUMMARY"
  echo "   PASS $id: $tests generated tests, jar probes OK, ${secs}s"
  [ "$id" = data-restclient ] && requested_service_probes "$p" "$jar" "$artifact"
  return 0
}

requested_service_probes() { # project jar artifactId
  local p=$1 jar=$2 artifact=$3 log db closed
  local auth="--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=$JWKS"
  local downstream="--app.greeting.base-url=http://127.0.0.1:9"
  step "Requested service: documented launch (mvn spring-boot:run, no profile)"
  log="$EVIDENCE/data-restclient-spring-boot-run.log"
  APP_PORT=$("${SUPPORT[@]}" freeport); RUN_ID="gate-run-$RANDOM$RANDOM"
  (cd "$p" && exec "${MVN[@]}" spring-boot:run \
      "-Dspring-boot.run.arguments=--server.port=$APP_PORT $auth --management.info.env.enabled=true --management.info.process.enabled=true --info.gate.run-id=$RUN_ID") > "$log" 2>&1 &
  APP_PID=$!; PIDS+=("$APP_PID")
  await_ready "$log" || fail "spring-boot:run did not become ready"
  probe_http "spring-boot:run" "data,restclient" "$log" "$artifact"
  # spring-boot:run forks the application JVM: stop it by the pid it reports, then the Maven process.
  local app_pid; app_pid=$(curl -sf "http://127.0.0.1:$APP_PORT/actuator/info" | grep -o '"pid":[0-9]*' | grep -o '[0-9]*' || true)
  if [ -n "$app_pid" ]; then
    if command -v taskkill >/dev/null 2>&1; then taskkill //F //PID "$app_pid" >/dev/null 2>&1 || true; else kill "$app_pid" 2>/dev/null || true; fi
  fi
  stop_app
  echo "   PASS documented launch"

  step "Requested service: local profile (human-readable console logs)"
  log="$EVIDENCE/data-restclient-local.log"
  start_app "$log" java -jar "$(native_path "$jar")" --spring.profiles.active=local "$auth"
  await_ready "$log" || fail "local profile did not become ready"
  status_of 200 "local authenticated /hello" -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$APP_PORT/hello?name=gate"
  grep -m1 'Started ' "$log" | grep -vq '^{' || fail "local profile still logs JSON"
  stop_app
  echo "   PASS local profile"

  step "Requested service: prod profile refuses to start without its mandatory settings"
  db="jdbc:h2:file:$(native_path "$WORKDIR/prod-db")/service"
  expect_startup_failure "prod without datasource" "Failed to configure a DataSource" \
      "$EVIDENCE/data-restclient-prod-no-datasource.log" java -jar "$(native_path "$jar")" \
      --spring.profiles.active=prod "$auth" "$downstream"
  expect_startup_failure "prod without identity provider" "JwtDecoder" \
      "$EVIDENCE/data-restclient-prod-no-idp.log" java -jar "$(native_path "$jar")" \
      --spring.profiles.active=prod "--spring.datasource.url=$db" "$downstream"
  expect_startup_failure "prod without downstream base URL" "app.greeting.base-url" \
      "$EVIDENCE/data-restclient-prod-no-downstream.log" java -jar "$(native_path "$jar")" \
      --spring.profiles.active=prod "--spring.datasource.url=$db" "$auth"

  step "Requested service: prod profile with externally supplied fixture settings, twice on one database"
  rm -rf "$WORKDIR/prod-db"
  local run
  for run in 1 2; do
    log="$EVIDENCE/data-restclient-prod-run$run.log"
    start_app "$log" java -jar "$(native_path "$jar")" --spring.profiles.active=prod "$auth" \
        "--spring.datasource.url=$db" "$downstream"
    await_ready "$log" || fail "prod fixture run $run did not become ready"
    if [ "$run" = 1 ]; then
      probe_http "prod run 1" "data,restclient" "$log" "$artifact" prod
    else
      grep -q "No migration necessary" "$log" || fail "prod run 2 did not find the schema up to date"
      ! grep -q "Successfully applied" "$log" || fail "prod run 2 re-applied migrations"
      status_of 200 "prod run 2 authenticated /hello" -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$APP_PORT/hello?name=gate"
    fi
    stop_app
  done
  echo "   PASS prod profile (fail-fast x3, fixture boot x2 with retained schema)"
}

# ---------------------------------------------------------------------------------------------------
# id | features (- = omit) | groupId | artifactId | package | platformVersion (- = omit) | subdir
SCENARIO_TABLE="$(cat <<EOF
none-defaults|-|com.dc.demo|demo-defaults|com.dc.demo|-|
messaging|messaging|com.dc.demo|demo-messaging|com.dc.demo|$REV|
data|data|com.dc.demo|demo-data|com.dc.demo|$REV|
restclient|restclient|com.dc.demo|demo-restclient|com.dc.demo|$REV|
messaging-data|messaging,data|com.dc.demo|demo-messaging-data|com.dc.demo|$REV|
messaging-restclient|messaging,restclient|com.dc.demo|demo-messaging-restclient|com.dc.demo|$REV|
data-restclient|data,restclient|com.dc.demo|demo-service|com.dc.demo|$REV|
all-relocated|restclient,messaging,data|org.example.billing|billing-svc|com.acme.billing.core|$REV|path with space
EOF
)"

SELECTED="${GP_SCENARIOS:-all}"
RAN=0
while IFS='|' read -r id features group artifact pkg version subdir; do
  [ -n "$id" ] || continue
  if [ "$SELECTED" != all ] && ! has "$SELECTED" "$id"; then continue; fi
  run_scenario "$id" "$features" "$group" "$artifact" "$pkg" "$version" "$subdir"
  RAN=$((RAN + 1))
done <<< "$SCENARIO_TABLE"
[ "$RAN" -gt 0 ] || fail "no scenario matched GP_SCENARIOS=$SELECTED"

ELAPSED=$(( $(date +%s) - START ))
step "Summary"
column -t -s $'\t' "$SUMMARY" 2>/dev/null || cat "$SUMMARY"
RESULT=PASS
echo "GOLDEN PATH OK: $RAN scenario(s), ${ELAPSED}s total (per-service SLA ${SLA_SECONDS}s)"
