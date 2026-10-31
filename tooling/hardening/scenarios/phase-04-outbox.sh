#!/usr/bin/env bash
# Hardening phase 4 (transactional outbox), Gate F: an archetype-generated consumer (-Ddatabase=oracle, prod profile),
# with the outbox adopted the way docs/modules/outbox.md says (add the starter, copy the DDL), against real Oracle and real
# RabbitMQ behind Toxiproxy, with two instances and real SIGKILLs. Proves the consumer-level acceptance criteria of
# docs/production-readiness/phase-04-outbox/scope.md. Fixtures, not certified here: RabbitMQ (Phase 5), security (JWKS).
#
#   HH_MAVEN_REPO=~/.m2-hardening/phase-04 bash tooling/hardening/scenarios/phase-04-outbox.sh
#
# Edits to the generated project: pom.xml swaps the sample's in-memory transport for RabbitMQ and adds the outbox and events
# starters; the probe and two migrations are added. The drainer's retry timings are shortened through its documented
# properties so the run takes minutes (recorded in app-env.txt); every other outbox default stands, batch size 50 included.
# Delivery is counted from a table the consumer's handler writes, one row per delivery, so loss and duplicates are facts.
#
# Needs about 4 GB of free memory: Oracle, RabbitMQ and up to two app containers (-Xmx400m each).
source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORACLE_IMAGE="${HH_ORACLE_IMAGE:-gvenzl/oracle-free:23-slim-faststart}"
RABBIT_IMAGE="${HH_RABBIT_IMAGE:-rabbitmq:3.13-management-alpine}"
TOXIPROXY_IMAGE="${HH_TOXIPROXY_IMAGE:-ghcr.io/shopify/toxiproxy:2.9.0}"

hex() { python -I -c "import secrets,sys; print(secrets.token_hex(int(sys.argv[1])))" "$1"; }
pin() { docker image inspect --format '{{index .RepoDigests 0}}' "$1" 2>/dev/null || echo "$1 (no digest)"; }
toxi() { curl -s -o /dev/null -w '%{http_code}' -X "$1" "$TOXI_API$2" ${3:+-H 'Content-Type: application/json' --data "$3"}; }
sql() { MSYS_NO_PATHCONV=1 docker exec -i "$ORA" sqlplus -s -L "$1/$2@//localhost:1521/FREEPDB1"; }
sys_sql() { sql system "$SYSTEM_PW"; }
# value <select>: one number or word from a query as the DBA account.
value() {
  printf 'set heading off feedback off pagesize 0\n%s;\nexit\n' "$1" | sys_sql 2>&1 | tr -d ' \r\t' | grep -v '^$' | head -1
}
delivered_distinct() { value "select count(distinct event_id) from $OWNER.hh_received where ref like '$1%'"; }
delivered_total() { value "select count(*) from $OWNER.hh_received where ref like '$1%'"; }
outbox_rows() { value "select count(*) from $OWNER.platform_outbox where status = '$1'"; }

# wait_delivered <prefix> <count> <timeout-s>: until that many different events have arrived.
wait_delivered() {
  local deadline=$(( $(date +%s) + $3 )) got=0
  while [ "$(date +%s)" -lt "$deadline" ]; do
    got=$(delivered_distinct "$1")
    [ "$got" = "$2" ] && return 0
    sleep 2
  done
  hh_log "only $got of $2 '$1' events delivered within $3 s"
  return 1
}
metric() { # name [filter]: first sample from the current app's scrape, without the fraction
  curl -s --max-time 10 "${AUTH[@]}" "$HH_BASE/actuator/prometheus" | { grep -E "^$1(\{| )" || true; } | { grep -F "${2:-}" || true; } \
      | head -1 | awk '{print $NF}' | cut -d. -f1
}
assert_all_delivered() { # label prefix count max-duplicates
  local distinct total
  distinct=$(delivered_distinct "$2"); total=$(delivered_total "$2")
  echo "$1 distinct=$distinct total=$total" >> "$HH_EVIDENCE/run-identity.txt"
  if [ "$distinct" = "$3" ]; then hh_pass "$1: all $3 events delivered"
  else hh_fail "$1: $distinct of $3 events delivered: EVENTS LOST"; fi
  if [ $(( total - distinct )) -le "$4" ]; then hh_pass "$1: $(( total - distinct )) duplicates (at most $4)"
  else hh_fail "$1: $(( total - distinct )) duplicates, more than $4"; fi
}
post_ok() { hh_http "$1" POST "$2" ${3:+-H "$3"}; hh_expect_status "${4:-200}"; }

hh_init phase-04 outbox

OWNER=HH_OWNER RUNTIME=HH_RT EMPTY=HH_EMPTY
SYSTEM_PW="Hs$(hex 8)" OWNER_PW="Ho$(hex 8)" RUNTIME_PW="Hr$(hex 8)" RABBIT_PW="Hq$(hex 8)"

hh_step "generate a consumer (data, messaging, database=oracle) and adopt the outbox as outbox.md says"
hh_generate hardening-outbox data,messaging -Ddatabase=oracle
hh_add_sources "$HERE/phase-04/probe" ae/gov/dubaicustoms/hardening/probe
hh_add_migrations "$HERE/phase-04/migration"
OUTBOX_JAR="$HH_MAVEN_REPO/ae/gov/dubaicustoms/platform/platform-messaging-outbox/$HH_REV/platform-messaging-outbox-$HH_REV.jar"
unzip -p "$(hh_native "$OUTBOX_JAR")" ae/gov/dubaicustoms/platform/messaging/outbox/platform_outbox.sql \
    > "$HH_APP_DIR/src/main/resources/db/migration/V2__platform_outbox.sql" || hh_abort "could not read the DDL from the outbox jar"
python -I "$(hh_native "$HERE/phase-04/edit_pom.py")" "$(hh_native "$HH_APP_DIR/pom.xml")" || hh_abort "could not edit pom.xml"
cp "$HH_APP_DIR/pom.xml" "$HH_EVIDENCE/generated-pom-with-outbox.xml"
hh_build_app
for GA in platform-starter-messaging-outbox platform-messaging-outbox-autoconfigure platform-messaging-outbox: platform-messaging-rabbit; do
  if grep -q "$GA" "$HH_EVIDENCE/dependency-tree.txt"; then hh_pass "dependency tree has $GA"
  else hh_fail "dependency tree lacks $GA"; fi
done

hh_step "loopback JWKS issuer (installed test kit), for a real principal and the Prometheus scrape"
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
BEARER="Authorization: Bearer $(cat "$IDP_DIR/token-valid")"
AUTH=(-H "$BEARER")

hh_step "infrastructure: Oracle Free, RabbitMQ, Toxiproxy in front of RabbitMQ"
hh_infra oracle "$ORACLE_IMAGE" -e "ORACLE_PASSWORD=$SYSTEM_PW"
ORA="$HH_RUN_ID-oracle"
ORACLE_STARTED=$(date +%s)
until [ "$(docker logs "$ORA" 2>&1 | grep -c 'DATABASE IS READY TO USE')" -gt 0 ]; do
  [ "$(docker inspect -f '{{.State.Running}}' "$ORA")" = true ] || hh_abort "Oracle container exited (infra log)"
  [ $(( $(date +%s) - ORACLE_STARTED )) -lt 1200 ] || hh_abort "Oracle not ready within 20 minutes"
  sleep 5
done
echo "oracle_ready_seconds=$(( $(date +%s) - ORACLE_STARTED ))" >> "$HH_EVIDENCE/run-identity.txt"
hh_infra rabbitmq "$RABBIT_IMAGE" -e RABBITMQ_DEFAULT_USER=hh -e "RABBITMQ_DEFAULT_PASS=$RABBIT_PW"
for _ in $(seq 1 90); do docker exec "$HH_RUN_ID-rabbitmq" rabbitmq-diagnostics -q ping >/dev/null 2>&1 && break; sleep 2; done
hh_infra toxiproxy "$TOXIPROXY_IMAGE" -p 127.0.0.1::8474
TOXI_API="http://127.0.0.1:$(docker port "$HH_RUN_ID-toxiproxy" 8474/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 1 60); do [ "$(toxi GET /version)" = 200 ] && break; sleep 0.5; done
[ "$(toxi POST /proxies '{"name":"rabbit","listen":"0.0.0.0:5672","upstream":"rabbitmq:5672"}')" = 201 ] \
    || hh_abort "could not create the toxiproxy route to RabbitMQ"
{
  echo "oracle_image=$(pin "$ORACLE_IMAGE")"
  echo "rabbit_image=$(pin "$RABBIT_IMAGE")"
  echo "toxiproxy_image=$(pin "$TOXIPROXY_IMAGE")"
} >> "$HH_EVIDENCE/run-identity.txt"

hh_step "schema owner (runs Flyway), runtime user (role HH_RW only), and a user with no outbox table"
if ! sys_sql > "$HH_EVIDENCE/oracle-setup.out" 2>&1 <<SQL
whenever sqlerror exit failure
create user $OWNER identified by "$OWNER_PW" quota unlimited on users;
grant create session, create table, create sequence, create view to $OWNER;
create role hh_rw;
create user $RUNTIME identified by "$RUNTIME_PW";
grant create session to $RUNTIME;
grant hh_rw to $RUNTIME;
create user $EMPTY identified by "$RUNTIME_PW";
grant create session to $EMPTY;
exit
SQL
then hh_abort "Oracle user setup failed (oracle-setup.out)"; fi

APP_ENV=(SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS=-Xmx400m
  SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//oracle:1521/FREEPDB1
  SPRING_DATASOURCE_USERNAME=$RUNTIME SPRING_DATASOURCE_PASSWORD=$RUNTIME_PW
  "SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL=alter session set current_schema=$OWNER"
  SPRING_FLYWAY_USER=$OWNER SPRING_FLYWAY_PASSWORD=$OWNER_PW
  SPRING_RABBITMQ_HOST=toxiproxy SPRING_RABBITMQ_USERNAME=hh SPRING_RABBITMQ_PASSWORD=$RABBIT_PW
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI="$JWKS_FOR_APP"
  DC_PLATFORM_EVENTS_RELAY_ENABLED=true
  DC_PLATFORM_MESSAGING_OUTBOX_POLLINTERVAL=200ms DC_PLATFORM_MESSAGING_OUTBOX_INITIALBACKOFF=200ms
  DC_PLATFORM_MESSAGING_OUTBOX_MAXBACKOFF=2s DC_PLATFORM_MESSAGING_OUTBOX_MAXREJECTIONS=2
  'SPRING_APPLICATION_JSON={"spring.jpa.properties.jakarta.persistence.database-major-version":"19"}')
printf '%s\n' "${APP_ENV[@]}" | sed -E 's/(PASSWORD=).*/\1****/' > "$HH_EVIDENCE/app-env.txt"

hh_step "boot in prod; Flyway applies the copied DDL as the owner; the runtime user has DML only"
HH_APP_NAME=first hh_app_start "${APP_ENV[@]}" PROBE_INSTANCE=first
FIRST="$HH_APP_CONTAINER"; FIRST_BASE="$HH_BASE"
hh_wait_ready 300
HISTORY=$(printf 'set heading off feedback off pagesize 0\nselect "version" || %s || "success" from %s."flyway_schema_history" order by "installed_rank";\nexit\n' "' '" "$OWNER" | sys_sql)
echo "$HISTORY" > "$HH_EVIDENCE/flyway-history.out"
if grep -q '^2 1' <<<"$HISTORY" && grep -q '^3 1' <<<"$HISTORY"; then hh_pass "AC15 the copied DDL migrated (V2) as the owner"
else hh_fail "AC15 flyway history: $HISTORY"; fi
sql "$RUNTIME" "$RUNTIME_PW" > "$HH_EVIDENCE/ac15-runtime-ddl.out" 2>&1 <<SQL
drop table $OWNER.platform_outbox;
exit
SQL
if grep -q 'ORA-01031' "$HH_EVIDENCE/ac15-runtime-ddl.out"; then hh_pass "AC15 the runtime user cannot drop the outbox table"
else hh_fail "AC15 runtime user DDL: $(tr '\n' ' ' < "$HH_EVIDENCE/ac15-runtime-ddl.out")"; fi

hh_step "AC1 commit delivers; rollback leaves no row and no message"
post_ok ac1-commit "/probe/orders?ref=ac1-ok"
wait_delivered ac1-ok 1 30 || hh_fail "AC1 committed event not delivered"
hh_http ac1-rollback POST "/probe/orders?ref=ac1-no&fail=true"; hh_expect_status 500
sleep 3
if [ "$(value "select count(*) from $OWNER.hh_order where ref = 'ac1-no'")" = 0 ] && [ "$(delivered_total ac1-no)" = 0 ]; then
  hh_pass "AC1 rollback: no business row, no outbox row, no delivery"
else hh_fail "AC1 rollback left something behind"; fi

hh_step "AC12 row and message carry the trace, the principal and the id; a credential header is refused"
T=$(hex 16)
post_ok ac12-traced "/probe/orders?ref=ac12-t" "traceparent: 00-$T-$(hex 8)-01"
wait_delivered ac12-t 1 30 || hh_fail "AC12 event not delivered"
if [ "$(value "select substr(traceparent, 4, 32) from $OWNER.hh_received where ref = 'ac12-t'")" = "$T" ]; then
  hh_pass "AC12 the message carries the inbound trace"
else hh_fail "AC12 trace not on the message"; fi
hh_http ac12-principal POST "/probe/orders?ref=ac12-p" -H "$BEARER"; hh_expect_status 200
wait_delivered ac12-p 1 30 || hh_fail "AC12 event not delivered"
SUBJECT=$(value "select principal from $OWNER.hh_received where ref = 'ac12-p'")
if [ -n "$SUBJECT" ] && [ "$SUBJECT" != null ] && ! grep -q eyJ <<<"$SUBJECT"; then hh_pass "AC12 the message carries the subject, not a token ($SUBJECT)"
else hh_fail "AC12 principal: '$SUBJECT'"; fi
if [ "$(value "select dump(event_id) from $OWNER.platform_outbox where 1 = 0" | wc -c)" -ge 0 ] \
    && ! printf 'select headers from %s.platform_outbox;\nexit\n' "$OWNER" | sys_sql | grep -q 'eyJ'; then
  hh_pass "AC12 no token in any stored row"
else hh_fail "AC12 a token reached a row"; fi
hh_http ac12-credential POST "/probe/orders?ref=ac12-c&header=x-auth-token"
[ "${HH_STATUS:0:1}" = 5 ] && hh_pass "AC12 a credential-named header is refused ($HH_STATUS)" || hh_fail "AC12 credential header status $HH_STATUS"
[ "$(value "select count(*) from $OWNER.hh_order where ref = 'ac12-c'")" = 0 ] && hh_pass "AC12 the refusal rolled the business write back" \
    || hh_fail "AC12 the business write survived a refused publish"

hh_step "AC16 the relay writes through the outbox inside the transaction"
post_ok ac16 "/probe/relay?ref=ac16-r"
wait_delivered ac16-r 1 30 && hh_pass "AC16 a relayed domain event is delivered" || hh_fail "AC16 relayed event not delivered"
if [ "$(value "select count(*) from $OWNER.platform_outbox where destination = 'dc.hh.relay'")" = 1 ]; then
  hh_pass "AC16 it went through the outbox table"
else hh_fail "AC16 no outbox row for the relayed event"; fi

hh_step "AC5 RabbitMQ down: requests unaffected, rows accumulate, lag rises; recovery delivers everything"
[ "$(toxi POST /proxies/rabbit '{"enabled":false}')" = 200 ] || hh_fail "could not disable the RabbitMQ proxy"
SLOWEST=0
for i in $(seq 1 30); do
  CODE_TIME=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 30 -X POST "$HH_BASE/probe/orders?ref=ac5-$i")
  [ "${CODE_TIME%% *}" = 200 ] || hh_fail "AC5 request $i: $CODE_TIME"
  SLOWEST=$(python -I -c "import sys; print(max(float(sys.argv[1]), float(sys.argv[2])))" "$SLOWEST" "${CODE_TIME##* }")
done
python -I -c "import sys; sys.exit(0 if float(sys.argv[1]) < 3 else 1)" "$SLOWEST" \
    && hh_pass "AC5 30 requests all 200 with RabbitMQ down, slowest ${SLOWEST}s" || hh_fail "AC5 slowest request ${SLOWEST}s"
sleep 8
PENDING=$(metric platform_outbox_pending); AGE=$(metric platform_outbox_oldest_pending_age_seconds)
echo "ac5 pending=$PENDING oldest_age=$AGE" >> "$HH_EVIDENCE/run-identity.txt"
[ "${PENDING:-0}" -ge 25 ] && hh_pass "AC5 pending metric shows the backlog ($PENDING)" || hh_fail "AC5 pending metric: ${PENDING:-none}"
[ "${AGE:-0}" -ge 3 ] && hh_pass "AC5 the lag metric rises (${AGE}s)" || hh_fail "AC5 lag metric: ${AGE:-none}"
PARKED=$(outbox_rows PARKED)
[ "$PARKED" = 0 ] && hh_pass "AC5 the outage parked nothing" || hh_fail "AC5 the outage parked $PARKED rows"
[ "$(toxi POST /proxies/rabbit '{"enabled":true}')" = 200 ] || hh_fail "could not re-enable the RabbitMQ proxy"
wait_delivered ac5- 30 120 || hh_fail "AC5 backlog not drained within 120 s"
assert_all_delivered AC5 ac5- 30 30

hh_step "AC6 + AC10 RabbitMQ slower than the confirm timeout: requests unaffected, the drainer stays on its own pool"
[ "$(toxi POST /proxies/rabbit/toxics '{"name":"slow","type":"latency","stream":"downstream","attributes":{"latency":8000}}')" = 200 ] \
    || hh_fail "could not add the latency toxic"
DIR="$HH_EVIDENCE/http/ac6"; mkdir -p "$DIR"; PIDS=()
for i in $(seq 1 20); do
  curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 30 -X POST "$HH_BASE/probe/orders?ref=ac6-$i" > "$DIR/$i.result" &
  PIDS+=($!)
done
for P in "${PIDS[@]}"; do wait "$P" || true; done
python -I - "$(hh_native "$DIR")" > "$HH_EVIDENCE/check-ac6.json" <<'EOF' && hh_pass "AC6 $(cat "$HH_EVIDENCE/check-ac6.json")" || hh_fail "AC6 $(cat "$HH_EVIDENCE/check-ac6.json")"
import json, sys
from pathlib import Path
rows = [p.read_text().split() for p in Path(sys.argv[1]).glob('*.result')]
slowest = max(float(r[1]) for r in rows)
ok = all(r[0] == '200' for r in rows) and len(rows) == 20 and slowest < 5
print(json.dumps({'ok': ok, 'requests': len(rows), 'slowest_seconds': slowest}))
sys.exit(0 if ok else 1)
EOF
sleep 6
DRAINER_ACTIVE=$(metric hikaricp_connections_active 'pool="platform-outbox"'); DRAINER_MAX=$(metric hikaricp_connections_max 'pool="platform-outbox"')
MAIN_PENDING=$(metric hikaricp_connections_pending 'pool="HikariPool-1"')
echo "ac10 drainer_active=$DRAINER_ACTIVE drainer_max=$DRAINER_MAX main_pending=$MAIN_PENDING" >> "$HH_EVIDENCE/run-identity.txt"
[ "${DRAINER_MAX:-0}" = 2 ] && hh_pass "AC10 the drainer's pool is capped at 2" || hh_fail "AC10 drainer pool max: ${DRAINER_MAX:-none}"
[ "${MAIN_PENDING:-1}" = 0 ] && hh_pass "AC10 no request waits on the application pool" || hh_fail "AC10 main pool pending: ${MAIN_PENDING:-none}"
toxi DELETE /proxies/rabbit/toxics/slow >/dev/null
wait_delivered ac6- 20 180 || hh_fail "AC6 events not delivered within 180 s of the latency ending"
# A send past its confirm timeout may yet arrive, then repeats (scope AC8); every event must still be there.
assert_all_delivered AC6 ac6- 20 100
sent_marked_before_ack=$(outbox_rows PARKED)
[ "$sent_marked_before_ack" = 0 ] && hh_pass "AC6 latency parked nothing" || hh_fail "AC6 latency parked $sent_marked_before_ack rows"

hh_step "AC9 an unroutable destination is rejected and parked, with a metric and a WARN; replay delivers it, same id"
post_ok ac9 "/probe/orders?ref=ac9-u&destination=dc.hh-outbox:nobody-bound"
for _ in $(seq 1 30); do [ "$(outbox_rows PARKED)" = 1 ] && break; sleep 1; done
[ "$(outbox_rows PARKED)" = 1 ] && hh_pass "AC9 the row is parked after max-rejections" || hh_fail "AC9 parked rows: $(outbox_rows PARKED)"
hh_wait_log 'parked (2 rejections)' 10 || true
sleep 6
[ "$(metric platform_outbox_parked)" = 1 ] && hh_pass "AC9 the parked metric reads 1" || hh_fail "AC9 parked metric: $(metric platform_outbox_parked)"
post_ok ac9-others "/probe/orders?ref=ac9-other"
wait_delivered ac9-other 1 30 && hh_pass "AC9 other rows keep flowing" || hh_fail "AC9 other rows blocked"
PARKED_ID=$(value "select event_id from $OWNER.platform_outbox where status = 'PARKED'")
MSYS_NO_PATHCONV=1 docker exec "$HH_RUN_ID-rabbitmq" rabbitmqadmin -u hh -p "$RABBIT_PW" declare queue name=hh-replay durable=true >/dev/null
MSYS_NO_PATHCONV=1 docker exec "$HH_RUN_ID-rabbitmq" rabbitmqadmin -u hh -p "$RABBIT_PW" declare binding source=dc.hh-outbox destination=hh-replay routing_key=nobody-bound >/dev/null
sys_sql > "$HH_EVIDENCE/ac9-replay.out" 2>&1 <<SQL
update $OWNER.platform_outbox set status = 'PENDING', rejections = 0, next_attempt_at = current_timestamp where status = 'PARKED';
commit;
exit
SQL
for _ in $(seq 1 30); do [ "$(outbox_rows PARKED)" = 0 ] && break; sleep 1; done
if [ "$(value "select status from $OWNER.platform_outbox where event_id = '$PARKED_ID'")" = SENT ]; then
  hh_pass "AC9 the documented replay sent it under the same id"
else hh_fail "AC9 replay did not send it"; fi
for _ in $(seq 1 10); do
  COUNT=$(MSYS_NO_PATHCONV=1 docker exec "$HH_RUN_ID-rabbitmq" rabbitmqctl -q list_queues name messages | awk '$1=="hh-replay"{print $2}')
  [ "$COUNT" = 1 ] && break; sleep 1
done
[ "$COUNT" = 1 ] && hh_pass "AC9 the replayed message reached the queue" || hh_fail "AC9 hh-replay holds ${COUNT:-nothing}"

hh_step "AC8 two instances, 500 events: none lost, none sent twice, both instances share the work"
HH_APP_NAME=second hh_app_start "${APP_ENV[@]}" PROBE_INSTANCE=second
SECOND="$HH_APP_CONTAINER"; SECOND_BASE="$HH_BASE"
hh_wait_ready 300
SENT_BEFORE_2=$(metric platform_outbox_sent_total)
HH_BASE=$FIRST_BASE; SENT_BEFORE_1=$(metric platform_outbox_sent_total)
for BATCH in 1 2 3 4 5; do post_ok "ac8-$BATCH" "/probe/orders/many?prefix=ac8-$BATCH&count=100"; done
wait_delivered ac8- 500 180 || hh_fail "AC8 not all delivered within 180 s"
assert_all_delivered AC8 ac8- 500 0
SENT_1=$(( $(metric platform_outbox_sent_total) - SENT_BEFORE_1 ))
HH_BASE=$SECOND_BASE; SENT_2=$(( $(metric platform_outbox_sent_total) - SENT_BEFORE_2 ))
echo "ac8 sent_first=$SENT_1 sent_second=$SENT_2" >> "$HH_EVIDENCE/run-identity.txt"
[ "$SENT_1" -gt 0 ] && [ "$SENT_2" -gt 0 ] && hh_pass "AC8 both instances drained ($SENT_1 + $SENT_2)" || hh_fail "AC8 work not shared ($SENT_1 + $SENT_2)"

hh_step "AC7 pod killed mid-drain (SIGKILL), with another instance alive: nothing lost, duplicates at most one batch"
toxi POST /proxies/rabbit/toxics '{"name":"pace","type":"latency","stream":"downstream","attributes":{"latency":100}}' >/dev/null
HH_BASE=$SECOND_BASE
post_ok ac7-a "/probe/orders/many?prefix=ac7a&count=300"
sleep 4
docker kill "$SECOND" >/dev/null
[ "$(docker inspect -f '{{.State.ExitCode}}' "$SECOND")" = 137 ] && hh_pass "AC7 the instance was killed (137)" || hh_fail "AC7 exit code"
docker logs "$SECOND" > "$HH_EVIDENCE/second.log" 2>&1 || true
HH_BASE=$FIRST_BASE
toxi DELETE /proxies/rabbit/toxics/pace >/dev/null
wait_delivered ac7a 300 180 || hh_fail "AC7 survivor did not deliver the backlog"
assert_all_delivered AC7-one-killed ac7a 300 50

hh_step "AC7 the only instance killed mid-drain, then a new one: nothing lost, duplicates at most one batch"
toxi POST /proxies/rabbit/toxics '{"name":"pace","type":"latency","stream":"downstream","attributes":{"latency":100}}' >/dev/null
post_ok ac7-b "/probe/orders/many?prefix=ac7b&count=200"
sleep 4
docker kill "$FIRST" >/dev/null
docker logs "$FIRST" > "$HH_EVIDENCE/first.log" 2>&1 || true
toxi DELETE /proxies/rabbit/toxics/pace >/dev/null
HH_APP_NAME=third hh_app_start "${APP_ENV[@]}" PROBE_INSTANCE=third
hh_wait_ready 300
wait_delivered ac7b 200 180 || hh_fail "AC7 the restart did not deliver the backlog"
assert_all_delivered AC7-all-killed ac7b 200 50

hh_step "AC14 SIGTERM mid-batch: the batch ends after the send in flight, exit 143, nothing lost or repeated"
toxi POST /proxies/rabbit/toxics '{"name":"pace","type":"latency","stream":"downstream","attributes":{"latency":100}}' >/dev/null
post_ok ac14 "/probe/orders/many?prefix=ac14&count=150"
sleep 3
HH_APP_NAME=third hh_app_stop_graceful
toxi DELETE /proxies/rabbit/toxics/pace >/dev/null
HH_APP_NAME=fourth hh_app_start "${APP_ENV[@]}" PROBE_INSTANCE=fourth
hh_wait_ready 300
wait_delivered ac14 150 180 || hh_fail "AC14 the backlog was not delivered after the restart"
assert_all_delivered AC14 ac14 150 0
HH_APP_NAME=fourth hh_app_stop_graceful

hh_step "AC13 outbox disabled: a startup WARN, no drainer, publish after commit"
HH_APP_NAME=disabled hh_app_start "${APP_ENV[@]}" PROBE_INSTANCE=disabled DC_PLATFORM_MESSAGING_OUTBOX_ENABLED=false
hh_wait_ready 300
hh_wait_log 'NO LONGER GUARANTEED' 5 || true
ROWS_BEFORE=$(value "select count(*) from $OWNER.platform_outbox")
post_ok ac13 "/probe/orders?ref=ac13-d"
wait_delivered ac13-d 1 30 && hh_pass "AC13 published after commit" || hh_fail "AC13 not delivered"
[ "$(value "select count(*) from $OWNER.platform_outbox")" = "$ROWS_BEFORE" ] && hh_pass "AC13 no outbox row was written" || hh_fail "AC13 a row was written while disabled"
HH_APP_NAME=disabled hh_app_stop_graceful

hh_step "AC13 no outbox table: startup fails naming the DDL"
HH_APP_NAME=notable hh_app_start "${APP_ENV[@]}" PROBE_INSTANCE=notable SPRING_DATASOURCE_USERNAME=$EMPTY SPRING_FLYWAY_ENABLED=false \
    SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL="select 1 from dual" DC_PLATFORM_DATA_JPA_REQUIREMIGRATIONS=false
HH_APP_NAME=notable hh_wait_exit 180
[ "$HH_EXIT_CODE" != 0 ] && hh_pass "AC13 startup failed (exit $HH_EXIT_CODE) in ${HH_EXIT_SECONDS}s" || hh_fail "AC13 startup did not fail"
grep -q 'platform_outbox.sql' "$HH_EVIDENCE/notable.log" && hh_pass "AC13 the failure names the DDL file" || hh_fail "AC13 no mention of the DDL in notable.log"

if grep -qF -e "$OWNER_PW" -e "$RUNTIME_PW" -e "$SYSTEM_PW" -e "$RABBIT_PW" -r "$HH_EVIDENCE"; then hh_fail "a password reached the evidence"
else hh_pass "no password in the evidence"; fi
