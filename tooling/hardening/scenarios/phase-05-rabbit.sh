#!/usr/bin/env bash
# Hardening phase 5 (RabbitMQ messaging), Gate F: an archetype-generated consumer (-Ddatabase=oracle, prod profile) with the
# Rabbit and outbox starters, against real Oracle and real RabbitMQ behind Toxiproxy. Proves the consumer-level acceptance
# criteria of docs/production-readiness/phase-05-rabbitmq-messaging/scope.md. Fixtures, not certified here: security (JWKS).
#
#   HH_MAVEN_REPO=~/.m2-hardening/phase-05 bash tooling/hardening/scenarios/phase-05-rabbit.sh
#
# Attempts, duplicates and trace continuity are read from tables the consumer's handlers write.
#
# Needs about 4 GB of free memory: Oracle, RabbitMQ and one app container (-Xmx400m).
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
count_rows() { value "select count(*) from $OWNER.$1 where $2"; }
# queue_messages <name>: messages in a queue, or nothing if it does not exist.
queue_messages() {
  MSYS_NO_PATHCONV=1 docker exec "$HH_RUN_ID-rabbitmq" rabbitmqctl -q list_queues name messages | awk -v q="$1" '$1==q{print $2}' | tr -d '\r'
}
queue_type() {
  MSYS_NO_PATHCONV=1 docker exec "$HH_RUN_ID-rabbitmq" rabbitmqctl -q list_queues name type | awk -v q="$1" '$1==q{print $2}' | tr -d '\r'
}
wait_queue() { # name count timeout-s
  local deadline=$(( $(date +%s) + $3 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do [ "$(queue_messages "$1")" = "$2" ] && return 0; sleep 1; done
  return 1
}
# publish_raw <routing-key> <payload> <headers-json>: a message from outside the platform.
publish_raw() {
  MSYS_NO_PATHCONV=1 docker exec "$HH_RUN_ID-rabbitmq" rabbitmqadmin -u hh -p "$RABBIT_PW" publish exchange=dc.hh-rabbit \
      routing_key="$1" payload="$2" properties="{\"headers\":$3}" > /dev/null
}

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

hh_init phase-05 rabbit

OWNER=HH_OWNER RUNTIME=HH_RT EMPTY=HH_EMPTY
SYSTEM_PW="Hs$(hex 8)" OWNER_PW="Ho$(hex 8)" RUNTIME_PW="Hr$(hex 8)" RABBIT_PW="Hq$(hex 8)"

hh_step "generate a consumer (data, messaging, database=oracle) and adopt the outbox as outbox.md says"
hh_generate hardening-rabbit data,messaging -Ddatabase=oracle
hh_add_sources "$HERE/phase-05/probe" ae/gov/dubaicustoms/hardening/probe
hh_add_migrations "$HERE/phase-05/migration"
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
hh_infra rabbitmq "$RABBIT_IMAGE" -p 127.0.0.1::15672 -e RABBITMQ_DEFAULT_USER=hh -e "RABBITMQ_DEFAULT_PASS=$RABBIT_PW"
# Its own log line, not rabbitmq-diagnostics: an exec during boot creates the Erlang cookie as root and crashes the broker.
for _ in $(seq 1 90); do [ "$(docker logs "$HH_RUN_ID-rabbitmq" 2>&1 | grep -c 'Server startup complete')" -gt 0 ] && break; sleep 2; done
[ "$(docker logs "$HH_RUN_ID-rabbitmq" 2>&1 | grep -c 'Server startup complete')" -gt 0 ] || hh_abort "RabbitMQ not ready within 3 minutes"
MSYS_NO_PATHCONV=1 docker exec "$HH_RUN_ID-rabbitmq" rabbitmq-plugins enable rabbitmq_shovel rabbitmq_shovel_management > /dev/null \
    || hh_abort "could not enable the shovel plugins"
MGMT_URL="http://127.0.0.1:$(docker port "$HH_RUN_ID-rabbitmq" 15672/tcp | head -1 | sed 's/.*://')"
hh_infra toxiproxy "$TOXIPROXY_IMAGE" -p 127.0.0.1::8474
TOXI_API="http://127.0.0.1:$(docker port "$HH_RUN_ID-toxiproxy" 8474/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 1 60); do [ "$(toxi GET /version)" = 200 ] && break; sleep 0.5; done
[ "$(toxi POST /proxies '{"name":"rabbit","listen":"0.0.0.0:5672","upstream":"rabbitmq:5672"}')" = 201 ] \
    || hh_abort "could not create the toxiproxy route to RabbitMQ"
[ "$(toxi POST /proxies '{"name":"oracle","listen":"0.0.0.0:1521","upstream":"oracle:1521"}')" = 201 ] \
    || hh_abort "could not create the toxiproxy route to Oracle"
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


APP=hardening-rabbit
Q_PLACED="dc.hh-rabbit.order.placed.$APP"; Q_FLAKY="dc.hh-rabbit.order.flaky.$APP"; Q_SLOW="dc.hh-rabbit.order.slow.$APP"

hh_step "AC15 quorum queues by default, main and dead-letter"
[ "$(queue_type "$Q_PLACED")" = quorum ] && hh_pass "AC15 the main queue is quorum" || hh_fail "AC15 main queue: $(queue_type "$Q_PLACED")"
[ "$(queue_type "$Q_PLACED.dlq")" = quorum ] && hh_pass "AC15 the dead-letter queue is quorum" || hh_fail "AC15 DLQ: $(queue_type "$Q_PLACED.dlq")"

hh_step "AC1 one trace from the request through the outbox and RabbitMQ to the handler, and on a direct publish"
T=$(hex 16)
post_ok ac1-outbox "/probe/orders?ref=ac1-outbox" "traceparent: 00-$T-$(hex 8)-01"
wait_delivered ac1-outbox 1 30 || hh_fail "AC1 outbox event not delivered"
[ "$(value "select handler_trace from $OWNER.hh_received where ref = 'ac1-outbox'")" = "$T" ] \
    && hh_pass "AC1 the handler ran on the request's trace, through the outbox" || hh_fail "AC1 outbox: handler trace differs"
[ "$(value "select producer from $OWNER.hh_received where ref = 'ac1-outbox'")" = "$APP" ] \
    && hh_pass "AC1 the outbox message names its producer" || hh_fail "AC1 outbox: producer missing"
T=$(hex 16)
post_ok ac1-direct "/probe/direct?ref=ac1-direct" "traceparent: 00-$T-$(hex 8)-01"
wait_delivered ac1-direct 1 30 || hh_fail "AC1 direct event not delivered"
[ "$(value "select handler_trace from $OWNER.hh_received where ref = 'ac1-direct'")" = "$T" ] \
    && hh_pass "AC1 the handler ran on the request's trace, on a direct publish" || hh_fail "AC1 direct: handler trace differs"
[ "$(value "select producer from $OWNER.hh_received where ref = 'ac1-direct'")" = "$APP" ] \
    && hh_pass "AC1 the direct message names its producer" || hh_fail "AC1 direct: producer missing"

hh_step "AC2 a failing handler: exactly max-attempts attempts, then the DLQ"
post_ok ac2 "/probe/direct?ref=ac2-fail&routingKey=order.flaky"
wait_queue "$Q_FLAKY.dlq" 1 60 && hh_pass "AC2 the message reached the DLQ" || hh_fail "AC2 DLQ holds $(queue_messages "$Q_FLAKY.dlq")"
sleep 3
ATTEMPTS=$(count_rows hh_attempt "ref = 'ac2-fail'")
echo "ac2 attempts=$ATTEMPTS" >> "$HH_EVIDENCE/run-identity.txt"
[ "$ATTEMPTS" = 3 ] && hh_pass "AC2 exactly 3 attempts" || hh_fail "AC2 $ATTEMPTS attempts, expected 3"
[ "$(queue_messages "$Q_FLAKY")" = 0 ] && hh_pass "AC2 the main queue is empty" || hh_fail "AC2 main queue holds $(queue_messages "$Q_FLAKY")"
DLQ_COUNT=$(metric platform_messaging_handled_total 'outcome="dlq"')
[ "${DLQ_COUNT:-0}" -ge 1 ] && hh_pass "AC2 the dlq outcome is counted" || hh_fail "AC2 metric: ${DLQ_COUNT:-none}"

hh_step "AC12 DLQ inspection and replay with the script: delivered once the cause is fixed, same id"
export RABBIT_MGMT_URL="$MGMT_URL" RABBIT_USER=hh RABBIT_PASSWORD="$RABBIT_PW"
REPLAY="$HH_REPO_ROOT/tooling/scripts/rabbit-dlq-replay.sh"
bash "$REPLAY" inspect "$Q_FLAKY" > "$HH_EVIDENCE/ac12-inspect.txt" 2>&1 || hh_fail "AC12 inspect failed: $(tail -1 "$HH_EVIDENCE/ac12-inspect.txt")"
DEAD_ID=$(python -I -c "import json,sys; print(json.loads(open(sys.argv[1]).readline())['eventId'])" "$(hh_native "$HH_EVIDENCE/ac12-inspect.txt")")
[ -n "$DEAD_ID" ] && hh_pass "AC12 inspect shows the event id" || hh_fail "AC12 no event id in the inspection"
[ "$(queue_messages "$Q_FLAKY.dlq")" = 1 ] && hh_pass "AC12 inspect leaves the message in place" || hh_fail "AC12 inspect moved it"
post_ok ac12-fix "/probe/failing?on=false"
bash "$REPLAY" replay "$Q_FLAKY" > "$HH_EVIDENCE/ac12-replay.txt" 2>&1 || hh_fail "AC12 replay failed: $(tail -1 "$HH_EVIDENCE/ac12-replay.txt")"
wait_delivered ac2-fail 1 60 && hh_pass "AC12 the replayed message was handled" || hh_fail "AC12 replay not handled"
[ "$(value "select event_id from $OWNER.hh_received where ref = 'ac2-fail'")" = "$DEAD_ID" ] \
    && hh_pass "AC12 under the same event id" || hh_fail "AC12 the id changed"
wait_queue "$Q_FLAKY.dlq" 0 30 && hh_pass "AC12 the DLQ is empty" || hh_fail "AC12 DLQ holds $(queue_messages "$Q_FLAKY.dlq")"

hh_step "AC3 an unreadable payload: no attempt, the DLQ, other messages flow, the payload is not logged"
MARKER="PAYLOADMARKER$(hex 6)"
publish_raw order.placed "not-json-$MARKER" '{"eventType":"hh.order","eventVersion":"1"}'
wait_queue "$Q_PLACED.dlq" 1 30 && hh_pass "AC3 the unreadable message reached the DLQ" || hh_fail "AC3 DLQ holds $(queue_messages "$Q_PLACED.dlq")"
post_ok ac3-next "/probe/direct?ref=ac3-next"
wait_delivered ac3-next 1 30 && hh_pass "AC3 other messages keep flowing" || hh_fail "AC3 the queue is stuck"

hh_step "AC13 a newer event version goes to the DLQ without an attempt; an unknown field is tolerated"
publish_raw order.placed '{"ref":"ac13-new"}' '{"eventType":"hh.order","eventVersion":"2","eventId":"ac13-new-id"}'
wait_queue "$Q_PLACED.dlq" 2 30 && hh_pass "AC13 the newer version reached the DLQ" || hh_fail "AC13 DLQ holds $(queue_messages "$Q_PLACED.dlq")"
[ "$(count_rows hh_received "ref = 'ac13-new'")" = 0 ] && hh_pass "AC13 it was not handled" || hh_fail "AC13 it was handled"
publish_raw order.placed '{"ref":"ac13-more","addedLater":1}' '{"eventType":"hh.order","eventVersion":"1","eventId":"ac13-more-id"}'
wait_delivered ac13-more 1 30 && hh_pass "AC13 an unknown field is tolerated" || hh_fail "AC13 an unknown field failed"

hh_step "AC10 + AC14 direct publish: unroutable surfaces to the caller; a credential header is refused"
hh_http ac10 POST "/probe/direct?ref=ac10&routingKey=nobody.bound"
[ "${HH_STATUS:0:1}" = 5 ] && hh_pass "AC10 unroutable surfaced ($HH_STATUS)" || hh_fail "AC10 status $HH_STATUS"
hh_http ac14 POST "/probe/direct?ref=ac14&header=x-auth-token"
[ "${HH_STATUS:0:1}" = 5 ] && hh_pass "AC14 a credential header is refused ($HH_STATUS)" || hh_fail "AC14 status $HH_STATUS"
sleep 3
[ "$(count_rows hh_received "ref = 'ac14'")" = 0 ] && hh_pass "AC14 nothing was sent" || hh_fail "AC14 the message went out"

hh_step "AC9 duplicate delivery: a naive consumer applies twice, the documented pattern once"
post_ok ac9-naive "/probe/direct?ref=ac9&routingKey=order.naive&times=2&eventId=ac9-naive-id"
post_ok ac9-deduped "/probe/direct?ref=ac9&routingKey=order.deduped&times=2&eventId=ac9-deduped-id"
sleep 6
NAIVE=$(count_rows hh_effect "event_id = 'ac9-naive-id'"); DEDUPED=$(count_rows hh_effect "event_id = 'ac9-deduped-id'")
echo "ac9 naive=$NAIVE deduped=$DEDUPED" >> "$HH_EVIDENCE/run-identity.txt"
[ "$NAIVE" = 2 ] && hh_pass "AC9 the naive consumer applied it twice (the documented risk)" || hh_fail "AC9 naive applied $NAIVE"
[ "$DEDUPED" = 1 ] && hh_pass "AC9 the pattern applied it once" || hh_fail "AC9 the pattern applied $DEDUPED"

hh_step "AC5 broker down: direct publish fails, the outbox absorbs, the consumer reconnects by itself"
[ "$(toxi POST /proxies/rabbit '{"enabled":false}')" = 200 ] || hh_fail "could not disable the RabbitMQ proxy"
hh_http ac5-direct POST "/probe/direct?ref=ac5-direct"
[ "${HH_STATUS:0:1}" = 5 ] && hh_pass "AC5 a direct publish fails, as documented ($HH_STATUS)" || hh_fail "AC5 direct status $HH_STATUS"
for i in 1 2 3 4 5; do post_ok "ac5-$i" "/probe/orders?ref=ac5-outbox-$i"; done
sleep 10
[ "$(toxi POST /proxies/rabbit '{"enabled":true}')" = 200 ] || hh_fail "could not re-enable the RabbitMQ proxy"
wait_delivered ac5-outbox 5 120 && hh_pass "AC5 the consumer reconnected and everything arrived" || hh_fail "AC5 not delivered after recovery"

hh_step "AC11 SIGTERM with messages in flight: the handler finishes, exit 143, none lost, none dead-lettered"
post_ok ac11 "/probe/direct?ref=ac11&routingKey=order.slow&times=6"
sleep 4
HH_APP_NAME=first hh_app_stop_graceful
IN_FLIGHT=$(delivered_distinct ac11)
echo "ac11 finished_before_stop=$IN_FLIGHT" >> "$HH_EVIDENCE/run-identity.txt"
[ "${IN_FLIGHT:-0}" -ge 1 ] && hh_pass "AC11 the handler in flight finished ($IN_FLIGHT)" || hh_fail "AC11 nothing finished"
[ "$(queue_messages "$Q_SLOW.dlq")" = 0 ] && hh_pass "AC11 the stop dead-lettered nothing" || hh_fail "AC11 DLQ holds $(queue_messages "$Q_SLOW.dlq")"

hh_step "AC7 broker down at startup: the app starts and is ready, and consumes once the broker arrives"
[ "$(toxi POST /proxies/rabbit '{"enabled":false}')" = 200 ] || hh_fail "could not disable the RabbitMQ proxy"
HH_APP_NAME=second hh_app_start "${APP_ENV[@]}" PROBE_INSTANCE=second
hh_wait_ready 300
hh_pass "AC7 ready with the broker down"
hh_http ac7-live GET /actuator/health/readiness; hh_expect_status 200
post_ok ac7-held "/probe/orders?ref=ac7-held"
[ "$(toxi POST /proxies/rabbit '{"enabled":true}')" = 200 ] || hh_fail "could not re-enable the RabbitMQ proxy"
wait_delivered ac7-held 1 120 && hh_pass "AC7 it consumes once the broker arrives" || hh_fail "AC7 nothing consumed after the broker arrived"
wait_delivered ac11 6 120 || hh_fail "AC11 the restart did not deliver the rest"
assert_all_delivered AC11 ac11 6 1

HH_APP_NAME=second hh_app_stop_graceful
if grep -rqE "$MARKER" "$HH_EVIDENCE"/*.log; then hh_fail "AC3 the payload reached a log"
else hh_pass "AC3 the payload is in no log"; fi

if grep -qF -e "$OWNER_PW" -e "$RUNTIME_PW" -e "$SYSTEM_PW" -e "$RABBIT_PW" -r "$HH_EVIDENCE"; then hh_fail "a password reached the evidence"
else hh_pass "no password in the evidence"; fi
