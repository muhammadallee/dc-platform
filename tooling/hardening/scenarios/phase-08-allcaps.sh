#!/usr/bin/env bash
# Hardening phase 8, step 3: one archetype-generated consumer with all seven certified capabilities (core and errors, logging and
# observability, Oracle data, outbox, RabbitMQ, REST client and resilience, security), in prod, running the C1 and C2 scenarios of
# plan §9 together, and the chain only the combination has: request -> DB write + outbox -> RabbitMQ -> handler -> outbound call.
#
#   HH_MAVEN_REPO=~/.m2-hardening/phase-07 bash tooling/hardening/scenarios/phase-08-allcaps.sh
#
# Nothing is opened to anonymous callers: every probe path needs a token. Needs about 5 GB of free memory: Oracle, RabbitMQ,
# Toxiproxy (in front of both) and one app container (-Xmx400m) at a time.
source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORACLE_IMAGE="${HH_ORACLE_IMAGE:-gvenzl/oracle-free:23-slim-faststart}"
RABBIT_IMAGE="${HH_RABBIT_IMAGE:-rabbitmq:3.13-management-alpine}"
TOXIPROXY_IMAGE="${HH_TOXIPROXY_IMAGE:-ghcr.io/shopify/toxiproxy:2.9.0}"
ISS=http://issuer.test

hex() { python -I -c "import secrets,sys; print(secrets.token_hex(int(sys.argv[1])))" "$1"; }
pin() { docker image inspect --format '{{index .RepoDigests 0}}' "$1" 2>/dev/null || echo "$1 (no digest)"; }
toxi() { curl -s -o /dev/null -w '%{http_code}' -X "$1" "$TOXI_API$2" ${3:+-H 'Content-Type: application/json' --data "$3"}; }
sql() { MSYS_NO_PATHCONV=1 docker exec -i "$ORA" sqlplus -s -L "$1/$2@//localhost:1521/FREEPDB1"; }
sys_sql() { sql system "$SYSTEM_PW"; }
mint() { curl -s "$IDP/__mint?$1"; }
script() { curl -s -o /dev/null -X POST "$STUB/__script" -H 'Content-Type: application/json' --data "$1"; curl -s -o /dev/null -X POST "$STUB/__reset"; }
stub_eval() { curl -s "$STUB/__requests" | python -I -c "import json,sys; d=json.load(sys.stdin); r=d['requests']; print($1)"; }
expect_count() {
  local got; got=$(stub_eval "len([q for q in r if q['path'] == '/x'])")
  if [ "$got" = "$2" ]; then hh_pass "$1: $2 request(s) reached the downstream"; else hh_fail "$1: $got request(s), expected $2"; fi
}
# received <prefix> <field>: what the handler recorded (total, distinct_events, trace, principal, downstream)
received() {
  curl -s --max-time 10 "${A[@]}" "$HH_BASE/all/received/$1" \
      | python -I -c "import json,sys; b={k.lower(): v for k, v in json.load(sys.stdin).items()}; print(b[sys.argv[1]])" "$2"
}
wait_received() { # prefix distinct timeout-s
  local deadline=$(( $(date +%s) + $3 )) got=0
  while [ "$(date +%s)" -lt "$deadline" ]; do
    got=$(received "$1" distinct_events 2>/dev/null || echo 0)
    [ "$got" = "$2" ] && return 0
    sleep 2
  done
  hh_log "only $got of $2 '$1' events received within $3 s"
  return 1
}
assert_all_received() { # label prefix count max-duplicates
  local distinct total
  distinct=$(received "$2" distinct_events); total=$(received "$2" total)
  echo "$1 distinct=$distinct total=$total" >> "$HH_EVIDENCE/run-identity.txt"
  if [ "$distinct" = "$3" ]; then hh_pass "$1: all $3 events received"; else hh_fail "$1: $distinct of $3 events received: EVENTS LOST"; fi
  if [ $(( total - distinct )) -le "$4" ]; then hh_pass "$1: $(( total - distinct )) duplicates (at most $4)"
  else hh_fail "$1: $(( total - distinct )) duplicates, more than $4"; fi
}

hh_init phase-08 allcaps

OWNER=HH_OWNER RUNTIME=HH_RT
SYSTEM_PW="Hs$(hex 8)" OWNER_PW="Ho$(hex 8)" RUNTIME_PW="Hr$(hex 8)" RABBIT_PW="Hq$(hex 8)"

hh_step "generate a consumer (data, messaging, restclient, database=oracle), adopt the outbox and RabbitMQ as outbox.md says"
hh_generate hardening-all data,messaging,restclient -Ddatabase=oracle
hh_add_sources "$HERE/phase-08/probe" ae/gov/dubaicustoms/hardening/probe
hh_add_migrations "$HERE/phase-08/migration"
OUTBOX_JAR="$HH_MAVEN_REPO/ae/gov/dubaicustoms/platform/platform-messaging-outbox/$HH_REV/platform-messaging-outbox-$HH_REV.jar"
unzip -p "$(hh_native "$OUTBOX_JAR")" ae/gov/dubaicustoms/platform/messaging/outbox/platform_outbox.sql \
    > "$HH_APP_DIR/src/main/resources/db/migration/V2__platform_outbox.sql" || hh_abort "could not read the DDL from the outbox jar"
python -I "$(hh_native "$HERE/phase-04/edit_pom.py")" "$(hh_native "$HH_APP_DIR/pom.xml")" || hh_abort "could not edit pom.xml"
cp "$HH_APP_DIR/pom.xml" "$HH_EVIDENCE/generated-pom-with-outbox.xml"
hh_build_app
for GA in platform-starter-core platform-starter-errors platform-starter-logging platform-starter-observability \
    platform-starter-data-jpa-oracle platform-starter-messaging-outbox platform-starter-messaging-rabbit \
    platform-starter-restclient resilience4j-circuitbreaker resilience4j-bulkhead platform-starter-security; do
  if grep -q "$GA" "$HH_EVIDENCE/dependency-tree.txt"; then hh_pass "dependency tree has $GA"; else hh_fail "dependency tree lacks $GA"; fi
done

hh_step "scripted identity provider and downstream on the host"
NIMBUS=$(find "$HH_MAVEN_REPO/com/nimbusds/nimbus-jose-jwt" -name 'nimbus-jose-jwt-*.jar' | sort -V | tail -1)
IDP_PORT_FILE="$HH_RUN_DIR/idp-port"
java -cp "$(hh_native "$NIMBUS")" "$(hh_native "$HERE/phase-07/Idp.java")" "$(hh_native "$IDP_PORT_FILE")" "$ISS" > "$HH_EVIDENCE/idp.log" 2>&1 &
HH_PIDS+=($!)
for _ in $(seq 1 120); do [ -s "$IDP_PORT_FILE" ] && break; sleep 0.5; done
[ -s "$IDP_PORT_FILE" ] || hh_abort "the IdP did not start"
IDP="http://127.0.0.1:$(cat "$IDP_PORT_FILE")"
STUB_PORT_FILE="$HH_RUN_DIR/stub-port"
python -I "$(hh_native "$HERE/phase-06/stub.py")" "$(hh_native "$STUB_PORT_FILE")" > "$HH_EVIDENCE/stub.log" 2>&1 &
HH_PIDS+=($!)
for _ in $(seq 1 60); do [ -s "$STUB_PORT_FILE" ] && break; sleep 0.5; done
[ -s "$STUB_PORT_FILE" ] || hh_abort "the stub did not start"
STUB="http://127.0.0.1:$(cat "$STUB_PORT_FILE")"

hh_step "infrastructure: Oracle Free, RabbitMQ, Toxiproxy in front of both"
hh_infra oracle "$ORACLE_IMAGE" -e "ORACLE_PASSWORD=$SYSTEM_PW"
ORA="$HH_RUN_ID-oracle"
ORACLE_STARTED=$(date +%s)
until [ "$(docker logs "$ORA" 2>&1 | grep -c 'DATABASE IS READY TO USE')" -gt 0 ]; do
  [ "$(docker inspect -f '{{.State.Running}}' "$ORA")" = true ] || hh_abort "Oracle container exited (infra log)"
  [ $(( $(date +%s) - ORACLE_STARTED )) -lt 1200 ] || hh_abort "Oracle not ready within 20 minutes"
  sleep 5
done
hh_infra rabbitmq "$RABBIT_IMAGE" -e RABBITMQ_DEFAULT_USER=hh -e "RABBITMQ_DEFAULT_PASS=$RABBIT_PW"
for _ in $(seq 1 90); do [ "$(docker logs "$HH_RUN_ID-rabbitmq" 2>&1 | grep -c 'Server startup complete')" -gt 0 ] && break; sleep 2; done
[ "$(docker logs "$HH_RUN_ID-rabbitmq" 2>&1 | grep -c 'Server startup complete')" -gt 0 ] || hh_abort "RabbitMQ not ready within 3 minutes"
hh_infra toxiproxy "$TOXIPROXY_IMAGE" -p 127.0.0.1::8474
TOXI_API="http://127.0.0.1:$(docker port "$HH_RUN_ID-toxiproxy" 8474/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 1 60); do [ "$(toxi GET /version)" = 200 ] && break; sleep 0.5; done
[ "$(toxi POST /proxies '{"name":"rabbit","listen":"0.0.0.0:5672","upstream":"rabbitmq:5672"}')" = 201 ] || hh_abort "no route to RabbitMQ"
[ "$(toxi POST /proxies '{"name":"oracle","listen":"0.0.0.0:1521","upstream":"oracle:1521"}')" = 201 ] || hh_abort "no route to Oracle"
{
  echo "oracle_image=$(pin "$ORACLE_IMAGE")"
  echo "rabbit_image=$(pin "$RABBIT_IMAGE")"
  echo "toxiproxy_image=$(pin "$TOXIPROXY_IMAGE")"
} >> "$HH_EVIDENCE/run-identity.txt"

hh_step "schema owner (runs Flyway) and runtime user (role HH_RW only)"
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

P=dc.platform.restclient
read -r -d '' CONFIG <<JSON || true
{"spring.jpa.properties.jakarta.persistence.database-major-version":"19",
 "$P.token-relay.clients":"main","$P.defaults.read-timeout":"3s","$P.defaults.deadline":"8s",
 "$P.clients.main.circuit-breaker.enabled":false,"$P.clients.main.bulkhead.max-concurrent":60,
 "management.endpoint.health.show-details":"always"}
JSON
J=SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT
APP_ENV=(SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS=-Xmx400m MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
  SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//toxiproxy:1521/FREEPDB1
  SPRING_DATASOURCE_USERNAME=$RUNTIME SPRING_DATASOURCE_PASSWORD=$RUNTIME_PW
  "SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL=alter session set current_schema=$OWNER"
  SPRING_FLYWAY_USER=$OWNER SPRING_FLYWAY_PASSWORD=$OWNER_PW
  SPRING_RABBITMQ_HOST=toxiproxy SPRING_RABBITMQ_USERNAME=hh SPRING_RABBITMQ_PASSWORD=$RABBIT_PW
  "${J}_ISSUER_URI=$ISS" "${J}_JWK_SET_URI=http://host.docker.internal:$(cat "$IDP_PORT_FILE")/jwks" "${J}_AUDIENCES=svc"
  DC_PLATFORM_SECURITY_JWT_ALLOWINSECUREJWKS=true
  DC_PLATFORM_MESSAGING_OUTBOX_POLLINTERVAL=200ms DC_PLATFORM_MESSAGING_OUTBOX_INITIALBACKOFF=200ms
  DC_PLATFORM_MESSAGING_OUTBOX_MAXBACKOFF=2s
  "PROBE_BASEURL=http://host.docker.internal:$(cat "$STUB_PORT_FILE")" "APP_GREETING_BASEURL=http://host.docker.internal:$(cat "$STUB_PORT_FILE")"
  "SPRING_APPLICATION_JSON=$(printf '%s' "$CONFIG" | tr -d '\n')")
printf '%s\n' "${APP_ENV[@]}" | sed -E 's/(PASSWORD=).*/\1****/' > "$HH_EVIDENCE/app-env.txt"

hh_step "boot in prod"
HH_APP_NAME=first hh_app_start "${APP_ENV[@]}"
FIRST="$HH_APP_CONTAINER"
hh_wait_ready 300
ALICE=$(mint 'sub=alice-all'); A=(-H "Authorization: Bearer $ALICE")

hh_step "authenticated by default"
hh_http anon-orders POST /all/orders; hh_expect_status 401
hh_http anon-received GET /all/received/x; hh_expect_status 401
hh_http anon-ready GET /actuator/health/readiness; hh_expect_status 200

hh_step "M1 request -> DB write + outbox -> RabbitMQ -> handler -> outbound call: one trace; the principal stays on the request"
script '{"default":{"status":200,"body":"hello"}}'
T=5af7651916cd43dd8448eb211c80319c
hh_http chain POST "/all/orders?ref=m1" "${A[@]}" -H "traceparent: 00-$T-b7ad6b7169203331-01"
hh_expect_status 200
hh_expect_json "principal in context before and after the call, trace kept" \
    "b['subject']=='alice-all' and b['subjectAfter']=='alice-all' and b['trace']=='$T' and b['downstream']=='hello'"
wait_received m1 1 60 || hh_fail "M1 the event did not reach the handler"
[ "$(received m1 trace)" = "$T" ] && hh_pass "M1 the handler ran on the request's trace" || hh_fail "M1 handler trace: $(received m1 trace)"
[ "$(received m1 principal)" = none ] && hh_pass "M1 no principal on the listener thread" || hh_fail "M1 handler principal: $(received m1 principal)"
[ "$(received m1 downstream)" = hello ] && hh_pass "M1 the handler's outbound call succeeded" || hh_fail "M1 handler call: $(received m1 downstream)"
REQ_CALL="[q for q in r if q['path']=='/x'][-1]"
HANDLER_CALL="[q for q in r if q['path'].startswith('/handler')][-1]"
[ "$(stub_eval "$REQ_CALL['headers'].get('traceparent','').startswith('00-$T-')")" = True ] \
    && hh_pass "M1 the request's outbound call carried the trace" || hh_fail "M1 no trace on the request's call"
[ "$(stub_eval "$REQ_CALL['auth'] is not None")" = True ] \
    && hh_pass "M1 the request's outbound call relayed the token" || hh_fail "M1 no relay on the request's call"
[ "$(stub_eval "$HANDLER_CALL['headers'].get('traceparent','').startswith('00-$T-')")" = True ] \
    && hh_pass "M1 the handler's outbound call carried the same trace" || hh_fail "M1 no trace on the handler's call"
[ "$(stub_eval "$HANDLER_CALL['auth'] is None")" = True ] \
    && hh_pass "M1 the handler's outbound call carried no token" || hh_fail "M1 a token on the handler's call"

hh_step "M2 downstream 401 and 503: mapped, nothing leaked, 401 not retried"
MARK=hhDownstreamBodyMarker
script "{\"default\":{\"status\":401,\"body\":\"$MARK token=abc\"}}"
hh_http down-401 GET /all/remote/main "${A[@]}"; hh_expect_status 500
hh_expect_json "generic body, not retryable" "b.get('code')=='DC-RCLIENT-0500' and b.get('retryable')==False"
grep -qF "$MARK" <<<"$HH_BODY" && hh_fail "M2 the downstream body reached the response" || hh_pass "M2 no downstream body in the response"
expect_count "M2 401 not retried" 1
script "{\"default\":{\"status\":503,\"body\":\"$MARK\"}}"
hh_http down-503 GET /all/remote/main "${A[@]}"; hh_expect_status 503
expect_count "M2 503 retried" 3

hh_step "M3 database slow while broker and downstream are healthy"
script '{"default":{"status":200,"body":"hello"}}'
[ "$(toxi POST /proxies/oracle/toxics '{"name":"slow","type":"latency","stream":"downstream","attributes":{"latency":700}}')" = 200 ] || hh_abort "no toxic"
STARTED=$(date +%s%N)
hh_http slow-remote GET /all/remote/main "${A[@]}"; hh_expect_status 200
TOOK=$(( ($(date +%s%N) - STARTED) / 1000000 ))
[ "$TOOK" -lt 700 ] && hh_pass "M3 an outbound call needs no database: ${TOOK} ms" || hh_fail "M3 outbound call took ${TOOK} ms"
hh_http slow-order POST "/all/orders?ref=m3" "${A[@]}"; hh_expect_status 200
hh_http slow-ready GET /actuator/health/readiness; hh_expect_status 200
[ "$(toxi DELETE /proxies/oracle/toxics/slow)" = 204 ] || hh_abort "could not remove the toxic"
wait_received m3 1 60 && hh_pass "M3 the event was delivered" || hh_fail "M3 the event was not delivered"

hh_step "M4 broker down while the database is healthy: requests succeed, events wait, nothing lost"
[ "$(toxi POST /proxies/rabbit '{"enabled":false}')" = 200 ] || hh_abort "could not cut RabbitMQ"
OK=0
for I in 1 2 3 4 5; do hh_http "m4-$I" POST "/all/orders?ref=m4-$I" "${A[@]}"; [ "$HH_STATUS" = 200 ] && OK=$((OK + 1)); done
[ "$OK" = 5 ] && hh_pass "M4 5 orders accepted with the broker down" || hh_fail "M4 $OK of 5 accepted"
hh_http m4-ready GET /actuator/health/readiness; hh_expect_status 200
sleep 3
[ "$(received m4- distinct_events)" = 0 ] && hh_pass "M4 nothing delivered while the broker is down" || hh_fail "M4 delivered while down"
[ "$(toxi POST /proxies/rabbit '{"enabled":true}')" = 200 ] || hh_abort "could not restore RabbitMQ"
wait_received m4- 5 120 || true
assert_all_received M4 m4- 5 5

hh_step "M5 two principals, 100 concurrent chains: no bleed; the drainer and requests share the pool"
script '{"default":{"status":200,"body":"hello"}}'
PA=$(mint 'sub=principal-a'); PB=$(mint 'sub=principal-b')
BLEED=$(HH_BASE="$HH_BASE" A="$PA" B="$PB" python -I "$(hh_native "$HERE/phase-08/bleed-all.py")")
[ "$BLEED" = "100 0 0" ] && hh_pass "M5 100 chains, no wrong principal, no repeated trace" || hh_fail "M5 answered, wrong, repeated: $BLEED"
for WHO in principal-a principal-b; do
  hh_http "rows-$WHO" GET "/all/count/$WHO" "${A[@]}"
  [ "$HH_BODY" = 50 ] && hh_pass "M5 $WHO owns 50 rows" || hh_fail "M5 $WHO owns $HH_BODY rows"
done
wait_received b- 100 120 || true
assert_all_received M5 b- 100 10
[ "$(stub_eval "len({q['auth'] for q in r if q['path']=='/x'})")" = 2 ] \
    && hh_pass "M5 each principal's own token reached the downstream" || hh_fail "M5 tokens at the downstream"
[ "$(stub_eval "len([q for q in r if q['path'].startswith('/handler') and q['auth'] is not None])")" = 0 ] \
    && hh_pass "M5 no handler call carried a token" || hh_fail "M5 a handler call carried a token"

hh_step "M6 the app killed mid-flow (SIGKILL), then a new instance: nothing lost, duplicates at most one batch"
for I in $(seq -w 1 30); do curl -s -o /dev/null --max-time 10 -X POST "${A[@]}" "$HH_BASE/all/orders?ref=k-$I"; done
docker kill "$FIRST" >/dev/null
[ "$(docker inspect -f '{{.State.ExitCode}}' "$FIRST")" = 137 ] && hh_pass "M6 the instance was killed (137)" || hh_fail "M6 exit code"
docker logs "$FIRST" > "$HH_EVIDENCE/first.log" 2>&1 || true
docker logs "$FIRST" > "$HH_EVIDENCE/first.stdout.log" 2>/dev/null || true
ORDERED=$(printf 'set heading off feedback off pagesize 0\nselect count(*) from %s.all_order where ref like %s;\nexit\n' "$OWNER" "'k-%'" | sys_sql | tr -d ' \r\t' | grep -v '^$' | head -1)
echo "m6 ordered_before_kill=$ORDERED" >> "$HH_EVIDENCE/run-identity.txt"
HH_APP_NAME=second hh_app_start "${APP_ENV[@]}"
hh_wait_ready 300
wait_received k- "$ORDERED" 180 || true
assert_all_received M6 k- "$ORDERED" 50

hh_step "M7 SIGTERM with a chain in flight"
script '{"default":{"status":200,"body":"inflight","delay_ms":2500}}'
curl -s -o "$HH_EVIDENCE/term.body" -w '%{http_code}' --max-time 40 -X POST "${A[@]}" "$HH_BASE/all/orders?ref=m7" > "$HH_EVIDENCE/term.status" &
TERM_PID=$!
for _ in $(seq 1 40); do [ "$(stub_eval "len([q for q in r if q['path']=='/x'])")" = 1 ] && break; sleep 0.1; done
expect_count "M7 the call is in flight" 1
HH_APP_NAME=second hh_app_stop_graceful
wait "$TERM_PID" || true
[ "$(cat "$HH_EVIDENCE/term.status")" = 200 ] && grep -q inflight "$HH_EVIDENCE/term.body" \
    && hh_pass "M7 the in-flight chain finished" || hh_fail "M7 $(cat "$HH_EVIDENCE/term.status")"

hh_step "logs: principals and tokens absent, the trace on the stored line, the pool never exhausted"
LOGS=("$HH_EVIDENCE/first.stdout.log" "$HH_EVIDENCE/second.stdout.log")
cat "${LOGS[@]}" > "$HH_EVIDENCE/all.stdout.log"
LOG="$HH_EVIDENCE/all.stdout.log"
grep -F "\"traceId\":\"$T\"" "$LOG" | grep -q 'order stored' && hh_pass "the stored line carries the trace" || hh_fail "no stored line on the trace"
for NEEDLE in alice-all principal-a principal-b "$MARK"; do
  grep -qF "$NEEDLE" "$LOG" && hh_fail "'$NEEDLE' in a log" || hh_pass "'$NEEDLE' not in a log"
done
grep -qE 'eyJ[A-Za-z0-9_-]{8,}\.' "$LOG" && hh_fail "something JWT-shaped in a log" || hh_pass "nothing JWT-shaped in a log"
grep -q 'Connection is not available' "$HH_EVIDENCE/first.log" "$HH_EVIDENCE/second.log" \
    && hh_fail "the connection pool was exhausted" || hh_pass "the connection pool was never exhausted"
