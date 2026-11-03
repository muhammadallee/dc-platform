#!/usr/bin/env bash
# Hardening phase 7, Gate G: composition checkpoint C2. An archetype-generated consumer with security, REST client, Oracle data and
# observability together, in prod, running the scenarios of plan §9. Composition failures found here are defects of Phase 7.
#
#   HH_MAVEN_REPO=~/.m2-hardening/phase-07 bash tooling/hardening/scenarios/phase-07-c2.sh
#
# Needs about 4 GB of free memory: Oracle, Toxiproxy and one app container (-Xmx400m).
source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORACLE_IMAGE="${HH_ORACLE_IMAGE:-gvenzl/oracle-free:23-slim-faststart}"
TOXIPROXY_IMAGE="${HH_TOXIPROXY_IMAGE:-ghcr.io/shopify/toxiproxy:2.9.0}"
ISS=http://issuer.test
pw() { python -I -c "import secrets; print('Hh' + secrets.token_hex(8))"; }
SYSTEM_PW=$(pw); APP_PW=$(pw); APP_USER=hhc2

toxi() { curl -s -o /dev/null -w '%{http_code}' -X "$1" "$TOXI_API$2" ${3:+-H 'Content-Type: application/json' --data "$3"}; }
idp() { curl -s -X "${2:-GET}" "$IDP/$1"; }
mint() { idp "__mint?$1"; }
script() { curl -s -o /dev/null -X POST "$STUB/__script" -H 'Content-Type: application/json' --data "$1"; curl -s -o /dev/null -X POST "$STUB/__reset"; }
stub_eval() { curl -s "$STUB/__requests" | python -I -c "import json,sys; d=json.load(sys.stdin); r=d['requests']; print($1)"; }
expect_count() {
  local got; got=$(stub_eval 'len(r)')
  if [ "$got" = "$2" ]; then hh_pass "$1: $2 request(s) reached the downstream"; else hh_fail "$1: $got request(s), expected $2"; fi
}
expect_mapped() { # name status retryable marker
  hh_expect_status "$2"
  hh_expect_json "$1 generic body, retryable=$3" "b.get('code')=='DC-RCLIENT-0500' and b.get('retryable')==$3 and b.get('detail')=='An unexpected error occurred.'"
  if grep -qF "$4" <<<"$HH_BODY"; then hh_fail "$1: the downstream body reached the response"; else hh_pass "$1: no downstream body in the response"; fi
  if grep -q 'eyJ' <<<"$HH_BODY"; then hh_fail "$1: a token in the response"; else hh_pass "$1: no token in the response"; fi
}

hh_init phase-07 c2

hh_step "generate a consumer (data, restclient, Oracle), add the probe"
hh_generate hardening-c2 data,restclient -Ddatabase=oracle
hh_add_sources "$HERE/phase-07/c2-probe" ae/gov/dubaicustoms/hardening/probe
hh_add_migrations "$HERE/phase-07/c2-migration"
hh_build_app
for GA in platform-starter-security platform-starter-restclient platform-starter-data-jpa-oracle platform-starter-observability; do
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

hh_step "Oracle Free, Toxiproxy in front of its listener"
hh_infra oracle "$ORACLE_IMAGE" -e "ORACLE_PASSWORD=$SYSTEM_PW"
ORA="$HH_RUN_ID-oracle"
ORACLE_STARTED=$(date +%s)
until [ "$(docker logs "$ORA" 2>&1 | grep -c 'DATABASE IS READY TO USE')" -gt 0 ]; do
  [ "$(docker inspect -f '{{.State.Running}}' "$ORA")" = true ] || hh_abort "Oracle container exited"
  [ $(( $(date +%s) - ORACLE_STARTED )) -lt 1200 ] || hh_abort "Oracle not ready within 20 minutes"
  sleep 5
done
hh_infra toxiproxy "$TOXIPROXY_IMAGE" -p 127.0.0.1::8474
TOXI_API="http://127.0.0.1:$(docker port "$HH_RUN_ID-toxiproxy" 8474/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 1 60); do [ "$(toxi GET /version)" = 200 ] && break; sleep 0.5; done
[ "$(toxi POST /proxies '{"name":"oracle","listen":"0.0.0.0:1521","upstream":"oracle:1521"}')" = 201 ] || hh_abort "could not route to Oracle"
docker exec -i "$ORA" sqlplus -s "system/$SYSTEM_PW@//localhost:1521/FREEPDB1" > "$HH_EVIDENCE/oracle-setup.out" 2>&1 <<SQL || hh_abort "Oracle user setup failed"
whenever sqlerror exit failure
create user $APP_USER identified by "$APP_PW" quota unlimited on users;
grant create session, create table, create sequence, create view to $APP_USER;
exit
SQL
P=dc.platform.restclient
read -r -d '' CONFIG <<JSON || true
{"spring.jpa.properties.jakarta.persistence.database-major-version":"19",
 "$P.token-relay.clients":"main,cb","$P.defaults.read-timeout":"3s","$P.defaults.deadline":"8s",
 "$P.clients.main.circuit-breaker.enabled":false,"$P.clients.main.bulkhead.max-concurrent":60,
 "$P.clients.cb.retry.enabled":false,"$P.clients.cb.circuit-breaker.sliding-window-size":4,
 "$P.clients.cb.circuit-breaker.minimum-calls":4,"$P.clients.cb.circuit-breaker.open-duration":"60s",
 "management.endpoint.health.show-details":"always"}
JSON
J=SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT
APP_ENV=(SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS=-Xmx400m MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
  SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//toxiproxy:1521/FREEPDB1 SPRING_DATASOURCE_USERNAME=$APP_USER SPRING_DATASOURCE_PASSWORD=$APP_PW
  "${J}_ISSUER_URI=$ISS" "${J}_JWK_SET_URI=http://host.docker.internal:$(cat "$IDP_PORT_FILE")/jwks" "${J}_AUDIENCES=svc" DC_PLATFORM_SECURITY_JWT_ALLOWINSECUREJWKS=true
  "PROBE_BASEURL=http://host.docker.internal:$(cat "$STUB_PORT_FILE")" "APP_GREETING_BASEURL=http://host.docker.internal:$(cat "$STUB_PORT_FILE")"
  "SPRING_APPLICATION_JSON=$(printf '%s' "$CONFIG" | tr -d '\n')")
printf '%s\n' "${APP_ENV[@]}" | sed -E 's/(PASSWORD=).*/\1****/' > "$HH_EVIDENCE/app-env.txt"
hh_app_start "${APP_ENV[@]}"
hh_wait_ready 300

ALICE=$(mint 'sub=alice-c2'); A=(-H "Authorization: Bearer $ALICE")

hh_step "C2.1 authenticated request, database write, outbound call: one trace, principal in context"
script '{"default":{"status":200,"body":"hello"}}'
T=4bf92f3577b34da6a3ce929d0e0e4736
hh_http chain POST /c2/orders "${A[@]}" -H "traceparent: 00-$T-00f067aa0ba902b7-01"
hh_expect_status 200
hh_expect_json "principal in context before and after the call, trace kept" "b['subject']=='alice-c2' and b['subjectAfter']=='alice-c2' and b['trace']=='$T' and b['downstream']=='hello'"
if [ "$(stub_eval "r[-1]['headers'].get('traceparent','').startswith('00-$T-')")" = True ]; then hh_pass "C2.1 the same trace reached the downstream"; else hh_fail "C2.1 no trace at the downstream"; fi
if [ "$(stub_eval "r[-1]['auth'] is not None")" = True ]; then hh_pass "C2.1 the token was relayed to the named client"; else hh_fail "C2.1 no relay"; fi
hh_http chain-row GET "/c2/trace/$T" "${A[@]}"; hh_expect_status 200
if [ "$HH_BODY" = alice-c2 ]; then hh_pass "C2.1 the row holds the principal and the trace"; else hh_fail "C2.1 row: $HH_BODY"; fi

hh_step "C2.2 downstream 401, 403, 5xx mapped, nothing leaked, 401 not retried"
MARK=hhDownstreamBodyMarker
for STATUS in 401 403 500; do
  script "{\"default\":{\"status\":$STATUS,\"body\":\"$MARK token=abc\"}}"
  hh_http "down-$STATUS" GET /c2/remote/main "${A[@]}"
  expect_mapped "down-$STATUS" 500 False "$MARK"
  expect_count "C2.2 $STATUS not retried" 1
done
script "{\"default\":{\"status\":503,\"body\":\"$MARK\"}}"
hh_http down-503 GET /c2/remote/main "${A[@]}"
expect_mapped down-503 503 True "$MARK"
expect_count "C2.2 503 retried" 3

hh_step "C2.3 breaker open on the downstream while the database is healthy"
script '{"default":{"status":500,"body":"no"}}'
for I in 1 2 3 4; do hh_http "cb-$I" GET /c2/remote/cb "${A[@]}"; done
script '{"default":{"status":200,"body":"hello"}}'
hh_http cb-open GET /c2/remote/cb "${A[@]}"
expect_mapped cb-open 503 True "$MARK"
expect_count "C2.3 an open breaker sends nothing" 0
hh_http cb-db GET /c2/count/alice-c2 "${A[@]}"; hh_expect_status 200
hh_http cb-other POST /c2/orders "${A[@]}"; hh_expect_status 200
hh_http cb-ready GET /actuator/health/readiness; hh_expect_status 200
hh_http cb-deps GET /actuator/health/dependencies "${A[@]}"
hh_expect_json "db UP, the breaker shown open" "b['components']['db']['status']=='UP' and 'OPEN' in json.dumps(b['components']['restclient'])"
hh_http cb-deps-anon GET /actuator/health/dependencies; hh_expect_status 401

hh_step "C2.4 database slow while the downstream is healthy"
[ "$(toxi POST /proxies/oracle/toxics '{"name":"slow","type":"latency","stream":"downstream","attributes":{"latency":700}}')" = 200 ] || hh_abort "no toxic"
STARTED=$(date +%s%N)
hh_http slow-remote GET /c2/remote/main "${A[@]}"; hh_expect_status 200
TOOK=$(( ($(date +%s%N) - STARTED) / 1000000 ))
if [ "$TOOK" -lt 700 ]; then hh_pass "C2.4 an outbound call needs no database: ${TOOK} ms"; else hh_fail "C2.4 outbound call took ${TOOK} ms"; fi
hh_http slow-order POST /c2/orders "${A[@]}"; hh_expect_status 200
hh_http slow-ready GET /actuator/health/readiness; hh_expect_status 200
[ "$(toxi DELETE /proxies/oracle/toxics/slow)" = 204 ] || hh_abort "could not remove the toxic"

hh_step "C2.5 two principals, 200 concurrent chains, no bleed"
script '{"default":{"status":200,"body":"hello"}}'
PA=$(mint 'sub=principal-a'); PB=$(mint 'sub=principal-b')
BLEED=$(HH_BASE="$HH_BASE" A="$PA" B="$PB" python -I "$(hh_native "$HERE/phase-07/bleed-c2.py")")
if [ "$BLEED" = "200 0 0" ]; then hh_pass "C2.5 200 chains, no wrong principal, no repeated trace"; else hh_fail "C2.5 answered, wrong, repeated: $BLEED"; fi
for WHO in principal-a principal-b; do
  hh_http "rows-$WHO" GET "/c2/count/$WHO" "${A[@]}"
  if [ "$HH_BODY" = 100 ]; then hh_pass "C2.5 $WHO owns 100 rows"; else hh_fail "C2.5 $WHO owns $HH_BODY rows"; fi
done
hh_http after-anon POST /c2/orders; hh_expect_status 401
if [ "$(stub_eval "len({q['auth'] for q in r})")" = 2 ]; then hh_pass "C2.5 two different tokens reached the downstream, each principal's own"; else hh_fail "C2.5 tokens at the downstream: $(stub_eval "len({q['auth'] for q in r})")"; fi

hh_step "C2.6 SIGTERM with outbound calls in flight"
script '{"default":{"status":200,"body":"inflight","delay_ms":2500}}'
curl -s -o "$HH_EVIDENCE/term.body" -w '%{http_code}' --max-time 40 -X POST "${A[@]}" "$HH_BASE/c2/orders" > "$HH_EVIDENCE/term.status" &
TERM_PID=$!
for _ in $(seq 1 40); do [ "$(stub_eval 'len(r)')" = 1 ] && break; sleep 0.1; done
expect_count "C2.6 the call is in flight" 1
hh_app_stop_graceful
wait "$TERM_PID" || true
if [ "$(cat "$HH_EVIDENCE/term.status")" = 200 ] && grep -q inflight "$HH_EVIDENCE/term.body"; then hh_pass "C2.6 the in-flight chain finished"; else hh_fail "C2.6 $(cat "$HH_EVIDENCE/term.status")"; fi

hh_step "C2 logs: principal and tokens absent, one trace on the stored line"
LOG="$HH_EVIDENCE/app.stdout.log"
if grep -F "\"traceId\":\"$T\"" "$LOG" | grep -q 'order stored'; then hh_pass "C2 the log line carries the trace"; else hh_fail "C2 no stored line on the trace"; fi
for NEEDLE in alice-c2 principal-a principal-b "$MARK"; do
  if grep -qF "$NEEDLE" "$LOG"; then hh_fail "C2 '$NEEDLE' in the log"; else hh_pass "C2 '$NEEDLE' not in the log"; fi
done
if grep -qE 'eyJ[A-Za-z0-9_-]{8,}\.' "$LOG"; then hh_fail "C2 something JWT-shaped in the log"; else hh_pass "C2 nothing JWT-shaped in the log"; fi
