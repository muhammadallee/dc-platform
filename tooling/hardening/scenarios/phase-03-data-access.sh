#!/usr/bin/env bash
# Hardening phase 3 (data access, Oracle), Gate F: an archetype-generated consumer (-Ddatabase=oracle,
# prod profile) against a real Oracle Free behind Toxiproxy proves the consumer-level acceptance criteria
# of docs/production-readiness/phase-03-data-access/scope.md (AC1, AC3-AC6, AC9, AC11, AC13, AC14).
# Fixtures, not certified here: core, errors' generic contract, logging, observability, security (JWKS).
#
#   HH_MAVEN_REPO=~/.m2-hardening/phase-03 bash tooling/hardening/scenarios/phase-03-data-access.sh
#
# The generated project is not edited: the probe sources and one Flyway migration are added. The schema
# owner runs Flyway (spring.flyway.user); the service runs as a runtime user that holds only a role with
# DML grants and reaches the owner's tables through CURRENT_SCHEMA (connection-init-sql), as data.md
# documents. Every platform timeout is left at its default (connection 5 s, transaction 30 s, Oracle
# read 60 s on the pool and 10 min on Flyway's own connection, connect 10 s). Oracle 23ai Free stands in
# for 19c (HD13), so Hibernate is pinned to 19 exactly as the container tests do; a real 19c needs no pin.
#
# Needs about 3 GB of free memory: one Oracle (about 2 GB) and one app container at a time.
source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKS=(python -I "$(hh_native "$HERE/phase-03/checks.py")")
ORACLE_IMAGE="${HH_ORACLE_IMAGE:-gvenzl/oracle-free:23-slim-faststart}"
TOXIPROXY_IMAGE="${HH_TOXIPROXY_IMAGE:-ghcr.io/shopify/toxiproxy:2.9.0}"
SILENT_IMAGE="${HH_SILENT_IMAGE:-alpine:3}" # busybox nc: a peer that accepts and never answers

hex() { python -I -c "import secrets,sys; print(secrets.token_hex(int(sys.argv[1])))" "$1"; }
tp() { printf '00-%s-%s-01' "$1" "$(hex 8)"; }
check() { # label check-args… : runs a checks.py check, records its JSON summary as the assertion text
  local label=$1; shift
  local out="$HH_EVIDENCE/check-$label.json"
  if "${CHECKS[@]}" "$@" > "$out" 2>>"$HH_EVIDENCE/harness.log"; then hh_pass "$label $(cat "$out")"
  else hh_fail "$label $(cat "$out")"; fi
}
pin() { docker image inspect --format '{{index .RepoDigests 0}}' "$1" 2>/dev/null || echo "$1 (no digest)"; }
toxi() { curl -s -o /dev/null -w '%{http_code}' -X "$1" "$TOXI_API$2" ${3:+-H 'Content-Type: application/json' --data "$3"}; }
# sql <user> <password> : runs stdin in sqlplus against FREEPDB1 inside the Oracle container (output on stdout).
sql() {
  MSYS_NO_PATHCONV=1 docker exec -i "$ORA" sqlplus -s -L "$1/$2@//localhost:1521/FREEPDB1"
}
sys_sql() { sql system "$SYSTEM_PW"; }
http_code_within() { # seconds path : polls a GET until it answers 200 or the bound passes
  local bound=$1 path=$2 deadline=$(( $(date +%s) + $1 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$HH_BASE$path")" = 200 ] && return 0
    sleep 1
  done
  return 1
}
# burst <label> <n> <method> <path-prefix> : n parallel requests (path gets &i=<i>), results under http/<label>/
burst() {
  local label=$1 n=$2 method=$3 path=$4 dir="$HH_EVIDENCE/http/$1" i pids=()
  mkdir -p "$dir"
  for i in $(seq 1 "$n"); do
    curl -s -o "$dir/$i.body" -w '%{http_code} %{time_total}' --max-time 150 -X "$method" "$HH_BASE$path$i" \
        > "$dir/$i.result" 2>/dev/null &
    pids+=($!)
  done
  for i in "${pids[@]}"; do wait "$i" || true; done
  printf '%s\n' "$dir"
}
scrape() { hh_http "$1" GET /actuator/prometheus "${AUTH[@]}"; }
metric() { # name scrape-label : first sample value of a metric in a saved scrape
  { grep -E "^$1(\{| )" "$HH_EVIDENCE/http/$2.body" || true; } | head -1 | awk '{print $NF}'
}

hh_init phase-03 data-access

OWNER=HH_OWNER RUNTIME=HH_RT
SYSTEM_PW="Hs$(hex 8)" OWNER_PW="Ho$(hex 8)" RUNTIME_PW="Hr$(hex 8)"

hh_step "generate a consumer (data, database=oracle), add the probe and its migration"
hh_generate hardening-data data -Ddatabase=oracle
hh_add_sources "$HERE/phase-03/probe" ae/gov/dubaicustoms/hardening/probe
hh_add_migrations "$HERE/phase-03/migration"
cp "$HH_APP_DIR/pom.xml" "$HH_EVIDENCE/generated-pom.xml"
hh_build_app

hh_step "AC1 the Oracle starter brings the driver and Flyway's Oracle module"
for GA in platform-starter-data-jpa-oracle com.oracle.database.jdbc:ojdbc11 org.flywaydb:flyway-database-oracle; do
  if grep -q "$GA" "$HH_EVIDENCE/dependency-tree.txt"; then hh_pass "AC1 dependency tree has $GA"
  else hh_fail "AC1 dependency tree lacks $GA"; fi
done
if grep -qiE 'postgresql|mysql|mariadb' "$HH_EVIDENCE/generated-pom.xml"; then
  hh_log "note: generated pom.xml names another database artifact (see generated-pom.xml)"
fi

hh_step "loopback JWKS issuer (installed test kit) for the Prometheus scrape in prod"
CP_SEP=':'; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;; esac
TEST_API_JAR="$HH_MAVEN_REPO/ae/gov/dubaicustoms/platform/platform-test-api/$HH_REV/platform-test-api-$HH_REV.jar"
SUPPORT_CLASSES="$HH_RUN_DIR/support-classes"
javac -nowarn -d "$(hh_native "$SUPPORT_CLASSES")" -cp "$(hh_native "$TEST_API_JAR")" \
    "$(hh_native "$HH_REPO_ROOT/tooling/scripts/support/GateSupport.java")" > "$HH_EVIDENCE/support-compile.log" 2>&1 \
    || hh_abort "could not compile GateSupport (support-compile.log)"
IDP_DIR="$HH_RUN_DIR/idp"; mkdir -p "$IDP_DIR"
java -cp "$(hh_native "$SUPPORT_CLASSES")$CP_SEP$(hh_native "$TEST_API_JAR")" GateSupport idp "$(hh_native "$IDP_DIR")" \
    > "$HH_EVIDENCE/idp.log" 2>&1 &
HH_PIDS+=($!)
for _ in $(seq 1 120); do [ -f "$IDP_DIR/ready" ] && break; sleep 0.5; done
[ -f "$IDP_DIR/ready" ] || hh_abort "JWKS issuer did not start (idp.log)"
JWKS_FOR_APP=$(sed 's#//127.0.0.1:#//host.docker.internal:#; s#//localhost:#//host.docker.internal:#' "$IDP_DIR/jwks-uri")
AUTH=(-H "Authorization: Bearer $(cat "$IDP_DIR/token-valid")")

hh_step "infrastructure: Oracle Free, Toxiproxy in front of its listener"
hh_infra oracle "$ORACLE_IMAGE" -e "ORACLE_PASSWORD=$SYSTEM_PW"
ORA="$HH_RUN_ID-oracle"
ORACLE_STARTED=$(date +%s)
until [ "$(docker logs "$ORA" 2>&1 | grep -c 'DATABASE IS READY TO USE')" -gt 0 ]; do
  [ "$(docker inspect -f '{{.State.Running}}' "$ORA")" = true ] || hh_abort "Oracle container exited (infra log)"
  [ $(( $(date +%s) - ORACLE_STARTED )) -lt 1200 ] || hh_abort "Oracle not ready within 20 minutes"
  sleep 5
done
echo "oracle_ready_seconds=$(( $(date +%s) - ORACLE_STARTED ))" >> "$HH_EVIDENCE/run-identity.txt"
hh_log "Oracle ready after $(( $(date +%s) - ORACLE_STARTED ))s"
hh_infra toxiproxy "$TOXIPROXY_IMAGE" -p 127.0.0.1::8474
TOXI_API="http://127.0.0.1:$(docker port "$HH_RUN_ID-toxiproxy" 8474/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 1 60); do [ "$(toxi GET /version)" = 200 ] && break; sleep 0.5; done
[ "$(toxi POST /proxies '{"name":"oracle","listen":"0.0.0.0:1521","upstream":"oracle:1521"}')" = 201 ] \
    || hh_abort "could not create the toxiproxy route to Oracle"
{
  echo "oracle_image=$(pin "$ORACLE_IMAGE")"
  echo "toxiproxy_image=$(pin "$TOXIPROXY_IMAGE")"
} >> "$HH_EVIDENCE/run-identity.txt"

hh_step "schema owner (runs Flyway) and runtime user (role HH_RW only), created by the DBA account"
# Passwords stay out of the evidence: the statements are logged with them masked.
if ! sys_sql > "$HH_EVIDENCE/oracle-setup.out" 2>&1 <<SQL
whenever sqlerror exit failure
create user $OWNER identified by "$OWNER_PW" quota unlimited on users;
grant create session, create table, create sequence, create view to $OWNER;
create role hh_rw;
create user $RUNTIME identified by "$RUNTIME_PW";
grant create session to $RUNTIME;
grant hh_rw to $RUNTIME;
exit
SQL
then hh_abort "Oracle user setup failed (oracle-setup.out)"; fi
hh_pass "owner $OWNER (create session/table/sequence/view) and runtime $RUNTIME (create session + role hh_rw) created"

APP_ENV=(SPRING_PROFILES_ACTIVE=prod
  SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//toxiproxy:1521/FREEPDB1
  SPRING_DATASOURCE_USERNAME=$RUNTIME SPRING_DATASOURCE_PASSWORD=$RUNTIME_PW
  # Relaxed binding: dashes are dropped from an environment name. CONNECTION_INIT_SQL would bind
  # hikari.connection (a getter that opens a connection) and fail startup.
  "SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL=alter session set current_schema=$OWNER"
  SPRING_FLYWAY_USER=$OWNER SPRING_FLYWAY_PASSWORD=$OWNER_PW
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI="$JWKS_FOR_APP"
  'SPRING_APPLICATION_JSON={"spring.jpa.properties.jakarta.persistence.database-major-version":"19"}')
printf '%s\n' "${APP_ENV[@]}" | sed -E 's/(PASSWORD=).*/\1****/' > "$HH_EVIDENCE/app-env.txt"

hh_step "AC1 boot in prod against Oracle; Flyway migrates as the owner"
hh_app_start "${APP_ENV[@]}"
hh_wait_ready 300
STARTUP_HEALTHY=$HH_READY_SECONDS
sys_sql > "$HH_EVIDENCE/flyway-history.out" 2>&1 <<SQL
set heading off feedback off pagesize 0
select "version" || ' ' || "success" || ' ' || "installed_by" from $OWNER."flyway_schema_history" order by "installed_rank";
exit
SQL
if grep -q "^1 1 $OWNER" "$HH_EVIDENCE/flyway-history.out" && grep -q "^2 1 $OWNER" "$HH_EVIDENCE/flyway-history.out"; then
  hh_pass "AC1 flyway_schema_history: V1 and V2 succeeded, installed by $OWNER"
else hh_fail "AC1 flyway_schema_history unexpected: $(tr '\n' ';' < "$HH_EVIDENCE/flyway-history.out")"; fi

hh_step "AC9 the service runs as the runtime user on the owner's schema; that user cannot ALTER or DROP"
hh_http ac9-identity GET /probe/identity; hh_expect_status 200
hh_expect_json "connected as $RUNTIME, current schema $OWNER" "b['user'] == '$RUNTIME' and b['currentSchema'] == '$OWNER'"
sql "$RUNTIME" "$RUNTIME_PW" > "$HH_EVIDENCE/ac9-runtime-ddl.out" 2>&1 <<SQL
alter table $OWNER.hh_parcel add (hh_extra number);
drop table $OWNER.hh_parcel;
create table hh_own_table (id number);
exit
SQL
if [ "$(grep -c 'ORA-01031' "$HH_EVIDENCE/ac9-runtime-ddl.out")" -eq 3 ]; then
  hh_pass "AC9 runtime user: ALTER, DROP and CREATE TABLE all refused with ORA-01031"
else hh_fail "AC9 runtime user DDL not refused three times: $(grep -o 'ORA-[0-9]*' "$HH_EVIDENCE/ac9-runtime-ddl.out" | tr '\n' ' ')"; fi
hh_http ac9-db-after GET /probe/db; hh_expect_status 200

hh_step "AC3 constraint violations through the repository and the EntityManager: 409, no SQL, one WARN on the trace"
# Table, column and constraint names all start with hh_ (the request path is /probe/parcels, so "parcel"
# itself legitimately appears in the problem's instance).
FORBIDDEN=(ora- hh_ uq_ fk_ ck_ constraint insert select sqlstate jdbc hibernate oracle)
REF="u-$(hex 4)"
hh_http ac3-seed POST "/probe/parcels?ref=$REF&weight=5&label=seed"; hh_expect_status 200
declare -A CASES=(
  [unique]="ref=$REF&weight=5&label=dup"
  [foreign-key]="ref=fk-$(hex 4)&weight=5&label=orphan&consignor=999"
  [not-null]="ref=nn-$(hex 4)&weight=5"
  [check]="ref=ck-$(hex 4)&weight=-5&label=negative"
  [empty-string]="ref=es-$(hex 4)&weight=5&label="
)
AC3_TRACES=()
ALL_REFS=("$REF")
for KIND in "${!CASES[@]}"; do R=${CASES[$KIND]#ref=}; ALL_REFS+=("${R%%&*}"); done
for KIND in unique foreign-key not-null check empty-string; do
  for VIA in repository entity-manager; do
    T=$(hex 16); NAME="ac3-$KIND-$VIA"
    hh_http "$NAME" POST "/probe/parcels?${CASES[$KIND]}&path=$VIA" -H "traceparent: $(tp "$T")"
    hh_expect_status 409
    hh_expect_json "on the inbound trace, retryable false" "b['traceId'] == '$T' and b['retryable'] is False"
    check "$NAME-body" clean-body "$(hh_native "$HH_EVIDENCE/http/$NAME.body")" DC-DATA-0409 "${FORBIDDEN[@]}" "$REF"
    AC3_TRACES+=("$NAME:$T")
  done
done
hh_http ac3-unique-count GET "/probe/parcels/count?ref=$REF"; hh_expect_status 200
hh_expect_json "the duplicate was not stored" "b['count'] == 1"

hh_step "AC11 Hikari and repository meters; bounded tags, no SQL or ID in a label value"
hh_http ac11-anon GET /actuator/prometheus; hh_expect_status 401
scrape ac11-scrape; hh_expect_status 200
check ac11-meters meters "$(hh_native "$HH_EVIDENCE/http/ac11-scrape.body")" "${ALL_REFS[@]}"
POOL_MAX=$(metric hikaricp_connections_max ac11-scrape | cut -d. -f1)
[ -n "$POOL_MAX" ] && [ "$POOL_MAX" -gt 0 ] || hh_abort "no hikaricp_connections_max in the scrape"
echo "pool_max=$POOL_MAX" >> "$HH_EVIDENCE/run-identity.txt"

hh_step "AC4 pool exhaustion: $((POOL_MAX * 2)) parallel transactions holding a connection 8 s"
DIR=$(burst ac4-exhaust $((POOL_MAX * 2)) POST "/probe/hold?millis=8000&ref=px-$(hex 3)-")
check ac4-exhaust burst "$(hh_native "$DIR")" "$POOL_MAX" 10 25
scrape ac4-after
PENDING=$(metric hikaricp_connections_pending ac4-after); TIMEOUTS=$(metric hikaricp_connections_timeout_total ac4-after)
if [ "${PENDING%%.*}" = 0 ]; then hh_pass "AC4 pending connections back to 0 (timeouts total $TIMEOUTS)"
else hh_fail "AC4 pending connections $PENDING after the burst"; fi
hh_http ac4-recovered GET /probe/db; hh_expect_status 200

hh_step "AC6 Oracle down (proxy disabled): DB requests 503 fast, others 200; probes UP, dependencies DOWN; recovery"
[ "$(toxi POST /proxies/oracle '{"enabled":false}')" = 200 ] || hh_fail "could not disable the Oracle proxy"
D=$(hex 16)
START=$(date +%s)
hh_http ac6-db-down GET /probe/db -H "traceparent: $(tp "$D")"; hh_expect_status 503
DOWN_SECONDS=$(( $(date +%s) - START ))
hh_expect_json "503 DC-DATA-0503 on the trace, retryable" "b['code'] == 'DC-DATA-0503' and b['traceId'] == '$D' and b['retryable'] is True"
check ac6-db-down-body clean-body "$(hh_native "$HH_EVIDENCE/http/ac6-db-down.body")" DC-DATA-0503 "${FORBIDDEN[@]}" toxiproxy freepdb1
if [ "$DOWN_SECONDS" -le 12 ]; then hh_pass "AC6 DB request failed in ${DOWN_SECONDS}s (connection-timeout 5 s)"
else hh_fail "AC6 DB request took ${DOWN_SECONDS}s while Oracle was down"; fi
hh_http ac6-context-down GET /probe/context; hh_expect_status 200
hh_http ac6-readiness-down GET /actuator/health/readiness; hh_expect_status 200
hh_http ac6-liveness-down GET /actuator/health/liveness; hh_expect_status 200
hh_http ac6-dependencies-down GET /actuator/health/dependencies; hh_expect_status 503
hh_expect_json "dependencies DOWN" "b['status'] == 'DOWN'"
[ "$(toxi POST /proxies/oracle '{"enabled":true}')" = 200 ] || hh_fail "could not re-enable the Oracle proxy"
if http_code_within 60 /actuator/health/dependencies; then hh_pass "AC6 dependencies UP again within 60 s, no restart"
else hh_fail "AC6 dependencies not UP within 60 s of Oracle returning"; fi
hh_http ac6-db-back GET /probe/db; hh_expect_status 200

hh_step "read-timeout backstop (HI-0066 target): Oracle goes silent mid-transaction; the 60 s driver read timeout ends it"
# Proves the mitigation of HI-0066 (a lost cancel is still bounded); it does not reproduce the PL/SQL miss.
R="rt-$(hex 3)"
curl -s -o "$HH_EVIDENCE/http/backstop.body" -w '%{http_code} %{time_total}' --max-time 150 -X POST \
    "$HH_BASE/probe/hold?millis=3000&ref=$R" > "$HH_EVIDENCE/http/backstop.result" 2>/dev/null &
BACKSTOP_PID=$!
hh_wait_log "hold-started $R" 20 || true # records its own failure
[ "$(toxi POST /proxies/oracle/toxics '{"name":"silent","type":"timeout","stream":"downstream","attributes":{"timeout":0}}')" = 200 ] \
    || hh_fail "could not add the silent-peer toxic"
wait "$BACKSTOP_PID" || true
toxi DELETE /proxies/oracle/toxics/silent >/dev/null
read -r BS_STATUS BS_SECONDS < "$HH_EVIDENCE/http/backstop.result" || true # curl -w writes no newline
BS_CODE=$(python -I -c "import json,sys; print(json.load(open(sys.argv[1])).get('code'))" "$(hh_native "$HH_EVIDENCE/http/backstop.body")" 2>/dev/null || echo none)
echo "backstop status=$BS_STATUS seconds=$BS_SECONDS code=$BS_CODE" >> "$HH_EVIDENCE/run-identity.txt"
if [ "$BS_STATUS" = 503 ] && [ "$BS_CODE" = DC-DATA-0503 ] && python -I -c "import sys; sys.exit(0 if 45 <= float(sys.argv[1]) <= 95 else 1)" "$BS_SECONDS"; then
  hh_pass "backstop: silent peer ended by the read timeout, 503 DC-DATA-0503 after ${BS_SECONDS}s (3 s hold + 60 s)"
else hh_fail "backstop: status $BS_STATUS code $BS_CODE after ${BS_SECONDS}s (expected 503 DC-DATA-0503 in 45-95 s)"; fi
if http_code_within 60 /probe/db; then hh_pass "backstop: DB requests succeed again within 60 s of the peer returning"
else hh_fail "backstop: no recovery within 60 s"; fi

hh_step "AC5 Oracle slow (40 s latency): bounded waits, 503s, no pile-up; recovery"
THREADS0=$(scrape ac5-before >/dev/null; metric jvm_threads_live_threads ac5-before | cut -d. -f1)
[ "$(toxi POST /proxies/oracle/toxics '{"name":"slow","type":"latency","stream":"downstream","attributes":{"latency":40000}}')" = 200 ] \
    || hh_fail "could not add the latency toxic"
DIR=$(burst ac5-slow $((POOL_MAX * 2)) GET "/probe/db?i=")
toxi DELETE /proxies/oracle/toxics/slow >/dev/null
check ac5-slow burst "$(hh_native "$DIR")" -1 75 75
python -I - "$(hh_native "$DIR")" "$POOL_MAX" > "$HH_EVIDENCE/check-ac5-fast-503.json" <<'EOF' && hh_pass "AC5 $(cat "$HH_EVIDENCE/check-ac5-fast-503.json")" || hh_fail "AC5 $(cat "$HH_EVIDENCE/check-ac5-fast-503.json")"
import json, sys
from pathlib import Path
rows = [p.read_text().split() for p in Path(sys.argv[1]).glob('*.result')]
fast = [r for r in rows if r[0] == '503' and float(r[1]) <= 12]
ok = len(fast) >= int(sys.argv[2])
print(json.dumps({'ok': ok, 'fast_503_within_12s': len(fast), 'needed': int(sys.argv[2])}))
sys.exit(0 if ok else 1)
EOF
if http_code_within 90 /probe/db; then hh_pass "AC5 DB requests succeed again within 90 s of the latency ending"
else hh_fail "AC5 no recovery within 90 s"; fi
scrape ac5-after
THREADS1=$(metric jvm_threads_live_threads ac5-after | cut -d. -f1)
echo "threads_live before=$THREADS0 after=$THREADS1" >> "$HH_EVIDENCE/run-identity.txt"
if [ $((THREADS1 - THREADS0)) -lt 50 ]; then hh_pass "AC5 live threads bounded ($THREADS0 -> $THREADS1)"
else hh_fail "AC5 live threads grew ($THREADS0 -> $THREADS1)"; fi
PENDING=$(metric hikaricp_connections_pending ac5-after)
[ "${PENDING%%.*}" = 0 ] && hh_pass "AC5 pending connections 0 after recovery" || hh_fail "AC5 pending connections $PENDING"

hh_step "AC13 SIGTERM with an in-flight transaction: it completes and commits, the pool shuts down, exit 143"
R="sg-$(hex 3)"
curl -s -o "$HH_EVIDENCE/http/ac13-inflight.body" -w '%{http_code}' --max-time 60 -X POST \
    "$HH_BASE/probe/hold?millis=8000&ref=$R" > "$HH_EVIDENCE/http/ac13-inflight.status" 2>/dev/null &
INFLIGHT_PID=$!
hh_wait_log "hold-started $R" 20 || true # records its own failure
hh_app_stop_graceful
wait "$INFLIGHT_PID" || true
if [ "$(cat "$HH_EVIDENCE/http/ac13-inflight.status")" = 200 ]; then hh_pass "AC13 in-flight request completed with 200 during shutdown"
else hh_fail "AC13 in-flight request status $(cat "$HH_EVIDENCE/http/ac13-inflight.status")"; fi
sys_sql > "$HH_EVIDENCE/ac13-committed.out" 2>&1 <<SQL
set heading off feedback off pagesize 0
select count(*) from $OWNER.hh_parcel where hh_ref_code = '$R';
exit
SQL
if grep -qE '^[[:space:]]*1[[:space:]]*$' "$HH_EVIDENCE/ac13-committed.out"; then hh_pass "AC13 the in-flight transaction's row is committed"
else hh_fail "AC13 committed rows for $R: $(tr -d ' \n' < "$HH_EVIDENCE/ac13-committed.out")"; fi
if grep -q 'Shutdown completed' "$HH_EVIDENCE/app.log"; then hh_pass "AC13 Hikari pool shut down (logged)"
else hh_fail "AC13 no Hikari 'Shutdown completed' line"; fi

hh_step "AC14 startup with Oracle slow (200 ms latency): ready within the budget (recorded)"
[ "$(toxi POST /proxies/oracle/toxics '{"name":"slowstart","type":"latency","stream":"downstream","attributes":{"latency":200}}')" = 200 ] \
    || hh_fail "could not add the startup latency toxic"
HH_APP_NAME=slowstart hh_app_start "${APP_ENV[@]}"
hh_wait_ready 300
STARTUP_SLOW=$HH_READY_SECONDS
HH_APP_NAME=slowstart hh_app_stop_graceful
toxi DELETE /proxies/oracle/toxics/slowstart >/dev/null

hh_step "AC14 startup with Oracle down: a clear failure within a bounded time"
[ "$(toxi POST /proxies/oracle '{"enabled":false}')" = 200 ] || hh_fail "could not disable the Oracle proxy"
HH_APP_NAME=downstart hh_app_start "${APP_ENV[@]}"
HH_APP_NAME=downstart hh_wait_exit 120
STARTUP_DOWN=$HH_EXIT_SECONDS
if [ "$HH_EXIT_CODE" != 0 ] && [ "$STARTUP_DOWN" -le 60 ]; then hh_pass "AC14 Oracle down: startup failed (exit $HH_EXIT_CODE) in ${STARTUP_DOWN}s"
else hh_fail "AC14 Oracle down: exit $HH_EXIT_CODE after ${STARTUP_DOWN}s (expected non-zero within 60 s)"; fi
DOWN_REASON=$(grep -oE 'IO Error[^"]{0,120}|Connection refused|Failed to obtain JDBC Connection|Unable to obtain connection[^"]{0,80}' "$HH_EVIDENCE/downstart.log" | head -1 || true)
[ -n "$DOWN_REASON" ] && hh_pass "AC14 Oracle down: the log names the cause ($DOWN_REASON)" || hh_fail "AC14 Oracle down: no connection error in downstart.log"
[ "$(toxi POST /proxies/oracle '{"enabled":true}')" = 200 ] || hh_fail "could not re-enable the Oracle proxy"

hh_step "AC14 startup against a silent peer (TCP accepted, never a byte back, never closed), separate Flyway user"
# Not Toxiproxy: through it, Oracle's listener closes a handshake it never completes (inbound connect timeout,
# about 60 s) and the close reaches the client, so only a peer that holds the socket open tests the client bounds.
hh_infra silent "$SILENT_IMAGE" -- nc -lk -p 1521 -e sleep 100000
echo "silent_image=$(pin "$SILENT_IMAGE")" >> "$HH_EVIDENCE/run-identity.txt"
HH_APP_NAME=silentstart hh_app_start "${APP_ENV[@]}" SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//silent:1521/FREEPDB1
# The bound is whichever client timeout ends the first connection attempt (connect 10 s, or a read timeout);
# 12 min covers the longest default (Flyway's own connection, 10 min). The time is recorded.
HH_APP_NAME=silentstart hh_wait_exit 780
STARTUP_SILENT=$HH_EXIT_SECONDS
if [ "$HH_EXIT_CODE" != 0 ] && [ "$STARTUP_SILENT" -le 720 ]; then hh_pass "AC14 silent peer: startup failed (exit $HH_EXIT_CODE) in ${STARTUP_SILENT}s"
else hh_fail "AC14 silent peer: exit $HH_EXIT_CODE after ${STARTUP_SILENT}s (expected non-zero within 12 min)"; fi
SILENT_REASON=$(grep -oE 'ORA-[0-9]+[^"\\]{0,100}' "$HH_EVIDENCE/silentstart.log" | sort -u | head -3 | tr '\n' ';' || true)
echo "silentstart reason=$SILENT_REASON" >> "$HH_EVIDENCE/run-identity.txt"

hh_step "AC3 log checks: the handler logs the full exception once on each violation's trace (Hibernate's own statement lines aside, scope A2)"
for ENTRY in "${AC3_TRACES[@]}"; do
  check "${ENTRY%%:*}-warn" warn-on-trace "$(hh_native "$HH_EVIDENCE/app.log")" "${ENTRY#*:}" DC-DATA-0409
done

if grep -qF -e "$OWNER_PW" -e "$RUNTIME_PW" -e "$SYSTEM_PW" -r "$HH_EVIDENCE"; then hh_fail "a database password reached the evidence"
else hh_pass "no database password in the evidence"; fi
echo "startup_healthy_seconds=$STARTUP_HEALTHY startup_slow_seconds=$STARTUP_SLOW startup_down_seconds=$STARTUP_DOWN startup_silent_seconds=$STARTUP_SILENT" \
    >> "$HH_EVIDENCE/run-identity.txt"
