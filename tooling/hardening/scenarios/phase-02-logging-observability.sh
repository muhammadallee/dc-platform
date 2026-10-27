#!/usr/bin/env bash
# Hardening phase 2 (logging + observability), Gate F: an archetype-generated consumer (prod profile, real
# PostgreSQL, real OpenTelemetry Collector behind Toxiproxy) proves the consumer-level acceptance criteria of
# docs/production-readiness/phase-02-logging-observability/scope.md (AC1-AC5, AC7-AC12, AC14). Fixtures,
# not certified here: core, errors, security (JWKS issuer), data-jpa + PostgreSQL, restclient.
#
#   HH_MAVEN_REPO=~/.m2-hardening/phase-02 bash tooling/hardening/scenarios/phase-02-logging-observability.sh
#
# The only change to the generated project is PostgreSQL support for a prod deployment: the JDBC driver
# its pom.xml asks for ("add its JDBC driver here") and Flyway's PostgreSQL module, which Flyway 10+ also
# needs (without it startup fails with "Unsupported Database"; HI-0041, phase 3).
source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKS=(python -I "$(hh_native "$HERE/phase-02/checks.py")")
POSTGRES_IMAGE="${HH_POSTGRES_IMAGE:-postgres:17-alpine}"
COLLECTOR_IMAGE="${HH_COLLECTOR_IMAGE:-otel/opentelemetry-collector-contrib:0.115.1}"
TOXIPROXY_IMAGE="${HH_TOXIPROXY_IMAGE:-ghcr.io/shopify/toxiproxy:2.9.0}"
SERVICE=hardening-obs

hex() { python -I -c "import secrets,sys; print(secrets.token_hex(int(sys.argv[1])))" "$1"; }
tp() { printf '00-%s-%s-01' "$1" "$(hex 8)"; }
check() { # label check-args… : runs a checks.py check, records its JSON summary as the assertion text
  local label=$1; shift
  local out="$HH_EVIDENCE/check-$label.json"
  if "${CHECKS[@]}" "$@" > "$out" 2>>"$HH_EVIDENCE/harness.log"; then hh_pass "$label $(cat "$out")"
  else hh_fail "$label $(cat "$out")"; fi
}
snapshot_log() { docker logs "$HH_APP_CONTAINER" > "$HH_EVIDENCE/$1" 2>&1 || true; }
pin() { docker image inspect --format '{{index .RepoDigests 0}}' "$1" 2>/dev/null || echo "$1 (no digest)"; }
toxi() { curl -s -o /dev/null -w '%{http_code}' -X "$1" "$TOXI_API$2" ${3:+-H 'Content-Type: application/json' --data "$3"}; }
since_warns() { # otel export warnings/errors in the app log since a line count
  docker logs "$HH_APP_CONTAINER" 2>&1 | tail -n +"$(( $1 + 1 ))" \
      | grep -E '"level":"(WARN|ERROR)"' | grep -ciE 'export|otlp|opentelemetry' || true
}
log_lines() { docker logs "$HH_APP_CONTAINER" 2>&1 | wc -l; }
mem_mib() { docker stats --no-stream --format '{{.MemUsage}}' "$HH_APP_CONTAINER" \
    | python -I -c "import re,sys; v,u=re.match(r'([\d.]+)(\w+)',sys.stdin.read()).groups(); print(int(float(v)*{'KiB':1/1024,'MiB':1,'GiB':1024}[u]))"; }

hh_init phase-02 logging-observability

hh_step "generate a consumer (data + restclient), add the probe and the PostgreSQL driver"
hh_generate hardening-obs data,restclient
hh_add_sources "$HERE/phase-02/probe" ae/gov/dubaicustoms/hardening/probe
python -I - "$(hh_native "$HH_APP_DIR/pom.xml")" <<'EOF'
import sys
path = sys.argv[1]
pom = open(path, encoding='utf-8').read()
driver = ''.join('    <dependency>\n      <groupId>%s</groupId>\n      <artifactId>%s</artifactId>\n'
                 '      <scope>runtime</scope>\n    </dependency>\n' % ga
                 for ga in (('org.postgresql', 'postgresql'), ('org.flywaydb', 'flyway-database-postgresql'))) \
    + '  </dependencies>'
assert pom.count('</dependencies>') >= 1
head, tail = pom.rsplit('</dependencies>', 1)
open(path, 'w', encoding='utf-8').write(head.rstrip() + '\n' + driver + tail)
EOF
cp "$HH_APP_DIR/pom.xml" "$HH_EVIDENCE/generated-pom-with-driver.xml"
hh_build_app

hh_step "loopback JWKS issuer (installed test kit) for real bearer tokens"
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
TOKEN=$(cat "$IDP_DIR/token-valid")
AUTH=(-H "Authorization: Bearer $TOKEN")

hh_step "infrastructure: PostgreSQL, OpenTelemetry Collector (file exporter), Toxiproxy in front of the collector"
hh_infra postgres "$POSTGRES_IMAGE" -e POSTGRES_DB=app -e POSTGRES_USER=app -e POSTGRES_PASSWORD=app-pw-hardening
COLLECTOR_CONFIG='receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
exporters:
  file/traces:
    path: /traces.json
  file/metrics:
    path: /metrics.json
service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [file/traces]
    metrics:
      receivers: [otlp]
      exporters: [file/metrics]'
# Root inside the collector so the file exporter can write at / (docker cp reads it back at the end).
MSYS_NO_PATHCONV=1 hh_infra otel "$COLLECTOR_IMAGE" --user 0 -e "OTEL_CONFIG=$COLLECTOR_CONFIG" -- --config=env:OTEL_CONFIG
hh_infra toxiproxy "$TOXIPROXY_IMAGE" -p 127.0.0.1::8474
TOXI_API="http://127.0.0.1:$(docker port "$HH_RUN_ID-toxiproxy" 8474/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 1 60); do [ "$(toxi GET /version)" = 200 ] && break; sleep 0.5; done
[ "$(toxi POST /proxies '{"name":"otel","listen":"0.0.0.0:4318","upstream":"otel:4318"}')" = 201 ] \
    || hh_abort "could not create the toxiproxy route to the collector"
for _ in $(seq 1 60); do docker exec "$HH_RUN_ID-postgres" pg_isready -U app >/dev/null 2>&1 && break; sleep 1; done
{
  echo "postgres_image=$(pin "$POSTGRES_IMAGE")"
  echo "collector_image=$(pin "$COLLECTOR_IMAGE")"
  echo "toxiproxy_image=$(pin "$TOXIPROXY_IMAGE")"
} >> "$HH_EVIDENCE/run-identity.txt"

APP_ENV=(SPRING_PROFILES_ACTIVE=prod
  SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/app SPRING_DATASOURCE_USERNAME=app
  SPRING_DATASOURCE_PASSWORD=app-pw-hardening SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT=2000
  APP_GREETING_BASE_URL=http://127.0.0.1:8080
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI="$JWKS_FOR_APP"
  MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
  DC_PLATFORM_OBSERVABILITY_OTLP_ENABLED=true
  MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://toxiproxy:4318/v1/traces
  MANAGEMENT_OTLP_METRICS_EXPORT_URL=http://toxiproxy:4318/v1/metrics
  MANAGEMENT_OTLP_METRICS_EXPORT_STEP=5s)

hh_step "boot in prod (JSON logs, OTLP on through the proxy, sampling 1.0)"
hh_app_start "${APP_ENV[@]}"
hh_wait_ready 180
STARTUP_HEALTHY=$HH_READY_SECONDS

T=$(hex 16)
hh_step "AC9 exposure under the security fixture: unexposed 401 anonymous / 404 with a token; platform 401 / 200"
for EP in env beans heapdump metrics configprops loggers threaddump; do
  hh_http "ac9-$EP-anon" GET "/actuator/$EP"; hh_expect_status 401
  hh_http "ac9-$EP-token" GET "/actuator/$EP" "${AUTH[@]}"; hh_expect_status 404
done
hh_http ac9-platform-anon GET /actuator/platform; hh_expect_status 401
hh_http ac9-platform-token GET /actuator/platform "${AUTH[@]}"; hh_expect_status 200
hh_expect_json "capability report names observability ACTIVE" "'observability' in json.dumps(b) and 'ACTIVE' in json.dumps(b)"
hh_http ac9-info-anon GET /actuator/info; hh_expect_status 200

hh_step "AC1 JSON line shape: a request's lines carry its traceId and spanId; ERROR lines a stack_trace"
hh_http ac1-error GET "/probe/hostile?msg=plain-failure-$T" -H "traceparent: $(tp "$T")"
hh_expect_status 500
hh_expect_json "error body on the inbound trace" "b['traceId'] == '$T'"

hh_step "AC2 log injection: CR/LF, quotes and forged JSON stay inside one event"
M1="INJ1-$(hex 4)"
FORGED=$'line-one\r\nline-two "quoted" {"level":"ERROR","message":"FORGED"} '"$M1"
hh_http ac2-message GET /probe/log --get --data-urlencode "msg=$FORGED"
hh_expect_status 200
M2="INJ2-$(hex 4)"
hh_http ac2-exception GET /probe/hostile --get --data-urlencode $'msg=boom\n{"level":"INFO","message":"FORGED"}\r\n'"$M2"
hh_expect_status 500

hh_step "AC3 consumer MDC passes through by default, credential-named keys masked"
TENANT_DEFAULT="t-$(hex 3)"
hh_http ac3-default GET "/probe/mdc?tenant=$TENANT_DEFAULT"; hh_expect_status 200

hh_step "AC5 redaction in message, structured arguments, MDC and exceptions; negatives kept"
JWT_IN_TEXT=$TOKEN
BASIC_B64=$(printf 'alice:%s' "s3cr3t-$(hex 3)" | base64 | tr -d '\n')
PASSWORD="Pw-$(hex 5)"
PASSWORD_B64=$(printf '%s' "$PASSWORD" | base64 | tr -d '\n') # kv("secret", password bytes) would print this
EID="784-1990-1234567-1"
PAN=4111111111111111
EMAIL="alice.$(hex 2)@example.ae"
PHONE="+971 50 123 4567"
MDC_TOKEN="mdc-session-$(hex 6)"
NEG_EPOCH=1761500000000; NEG_ORDER=1234567890; NEG_TRACE=$(hex 16)
NEG_UUID=$(python -I -c "import uuid; print(uuid.uuid4())"); NEG_TS=2026-10-27T05:00:00Z
hh_http ac5-secrets GET /probe/secrets "${AUTH[@]}" --get \
    --data-urlencode "jwt=$JWT_IN_TEXT" --data-urlencode "basic=$BASIC_B64" --data-urlencode "password=$PASSWORD" \
    --data-urlencode "eid=$EID" --data-urlencode "pan=$PAN" --data-urlencode "email=$EMAIL" \
    --data-urlencode "phone=$PHONE" --data-urlencode "mdcToken=$MDC_TOKEN" \
    --data-urlencode "epoch=$NEG_EPOCH" --data-urlencode "order=$NEG_ORDER" --data-urlencode "trace=$NEG_TRACE" \
    --data-urlencode "uuid=$NEG_UUID" --data-urlencode "ts=$NEG_TS"
hh_expect_status 200
HOSTILE_PW="Hx-$(hex 5)"
hh_http ac5-exception GET /probe/hostile --get --data-urlencode "msg=db login password=$HOSTILE_PW token=$TOKEN"
hh_expect_status 500

hh_step "AC7 Prometheus scrape: 401 anonymous, 200 with a token; common tags; no ID-like label values"
hh_http ac7-anon GET /actuator/prometheus; hh_expect_status 401
hh_http ac7-scrape GET /actuator/prometheus "${AUTH[@]}"; hh_expect_status 200
check ac7-scrape scrape "$(hh_native "$HH_EVIDENCE/http/ac7-scrape.body")" "$SERVICE"

hh_step "AC8 400 outbound calls to literal URIs: at most 100 client uri series, one WARN; unmatched server paths collapse"
WARN0=$(docker logs "$HH_APP_CONTAINER" 2>&1 | grep -c "Reached the maximum number of" || true)
hh_http ac8-fanout GET "/probe/fanout?n=400"; hh_expect_status 200
for i in $(seq 1 30); do curl -s -o /dev/null "$HH_BASE/probe/nope-$i-$(hex 4)"; done
hh_http ac8-scrape GET /actuator/prometheus "${AUTH[@]}"; hh_expect_status 200
check ac8-uri-series uri-series "$(hh_native "$HH_EVIDENCE/http/ac8-scrape.body")" 100
WARN1=$(docker logs "$HH_APP_CONTAINER" 2>&1 | grep -c "Reached the maximum number of" || true)
if [ $((WARN1 - WARN0)) -eq 1 ]; then hh_pass "AC8 exactly one uri-cap WARN"
else hh_fail "AC8 uri-cap WARN lines: $((WARN1 - WARN0)) (expected 1)"; fi

hh_step "AC4 200 parallel requests x 3 rounds: every log line (request and @Async thread) on its own trace"
check ac4-correlated correlated "$HH_BASE" 200 3 "$HH_APP_CONTAINER"

hh_step "AC11 real collector: spans and metrics with service.name; inbound and outbound spans share the trace"
O=$(hex 16)
hh_http ac11-outbound GET /probe/outbound -H "traceparent: $(tp "$O")"; hh_expect_status 200
hh_expect_json "downstream on the same trace" "b['downstream']['traceparent'].startswith('00-$O-')"
sleep 12 # batch span processor (5 s) + metric step (5 s)
COLLECTOR_OUT="$HH_RUN_DIR/collector"; mkdir -p "$COLLECTOR_OUT"
MSYS_NO_PATHCONV=1 docker cp "$HH_RUN_ID-otel:/traces.json" "$(hh_native "$COLLECTOR_OUT/traces.json")" >/dev/null 2>&1 || true
MSYS_NO_PATHCONV=1 docker cp "$HH_RUN_ID-otel:/metrics.json" "$(hh_native "$COLLECTOR_OUT/metrics.json")" >/dev/null 2>&1 || true
check ac11-collector collector "$(hh_native "$COLLECTOR_OUT")" "$SERVICE" "$O"

hh_step "AC10 database stopped: readiness/liveness UP, dependencies DOWN; DB requests fail cleanly, others succeed"
docker stop "$HH_RUN_ID-postgres" >/dev/null
sleep 2
hh_http ac10-readiness-down GET /actuator/health/readiness; hh_expect_status 200
hh_http ac10-liveness-down GET /actuator/health/liveness; hh_expect_status 200
hh_http ac10-dependencies-down GET /actuator/health/dependencies; hh_expect_status 503
hh_expect_json "dependencies DOWN" "b['status'] == 'DOWN'"
D=$(hex 16)
hh_http ac10-db-down GET /probe/db -H "traceparent: $(tp "$D")"
if [ "${HH_STATUS:0:1}" = 5 ]; then hh_pass "ac10-db-down status $HH_STATUS (5xx)"; else hh_fail "ac10-db-down status $HH_STATUS"; fi
hh_expect_json "problem body on the trace, no SQL/JDBC internals" \
    "b['traceId'] == '$D' and 'jdbc' not in json.dumps(b).lower() and 'postgres' not in json.dumps(b).lower()"
hh_http ac10-context-down GET /probe/context; hh_expect_status 200
docker start "$HH_RUN_ID-postgres" >/dev/null
for _ in $(seq 1 60); do docker exec "$HH_RUN_ID-postgres" pg_isready -U app >/dev/null 2>&1 && break; sleep 1; done
RECOVERED=no
for _ in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$HH_BASE/actuator/health/dependencies")" = 200 ] && { RECOVERED=yes; break; }
  sleep 1
done
[ "$RECOVERED" = yes ] && hh_pass "AC10 dependencies UP again after the database returned" \
    || hh_fail "AC10 dependencies not UP within 60 s of the database returning"
hh_http ac10-db-back GET /probe/db; hh_expect_status 200

hh_step "AC12 collector down (proxy disabled) and slow (10 s latency): requests unaffected, memory and warnings bounded"
check ac12-baseline latency "$HH_BASE" 200
LINES0=$(log_lines); MEM0=$(mem_mib)
[ "$(toxi POST /proxies/otel '{"enabled":false}')" = 200 ] || hh_fail "could not disable the collector proxy"
check ac12-collector-down latency "$HH_BASE" 300
sleep 20
check ac12-collector-down-2 latency "$HH_BASE" 300
MEM1=$(mem_mib); WARNS_DOWN=$(since_warns "$LINES0")
[ "$(toxi POST /proxies/otel '{"enabled":true}')" = 200 ] || hh_fail "could not re-enable the collector proxy"
LINES1=$(log_lines)
[ "$(toxi POST /proxies/otel/toxics '{"name":"slow","type":"latency","stream":"upstream","attributes":{"latency":10000}}')" = 200 ] \
    || hh_fail "could not add the latency toxic"
check ac12-collector-slow latency "$HH_BASE" 300
sleep 20
check ac12-collector-slow-2 latency "$HH_BASE" 300
MEM2=$(mem_mib); WARNS_SLOW=$(since_warns "$LINES1")
toxi DELETE /proxies/otel/toxics/slow >/dev/null
echo "memory_mib baseline=$MEM0 after_down=$MEM1 after_slow=$MEM2 warns_down=$WARNS_DOWN warns_slow=$WARNS_SLOW" \
    | tee -a "$HH_EVIDENCE/run-identity.txt" >&2
if [ $((MEM2 - MEM0)) -lt 200 ] && [ $((MEM1 - MEM0)) -lt 200 ]; then hh_pass "AC12 container memory growth < 200 MiB ($MEM0 → $MEM1 → $MEM2)"
else hh_fail "AC12 container memory grew ($MEM0 → $MEM1 → $MEM2 MiB)"; fi
if [ "$WARNS_DOWN" -le 60 ] && [ "$WARNS_SLOW" -le 60 ]; then hh_pass "AC12 export warnings bounded (down $WARNS_DOWN, slow $WARNS_SLOW)"
else hh_fail "AC12 export warnings not bounded (down $WARNS_DOWN, slow $WARNS_SLOW)"; fi

hh_step "AC14 SIGTERM: shutdown lines on stdout, exit 143"
hh_app_stop_graceful
check ac14-json-lines json-lines "$(hh_native "$HH_EVIDENCE/app.log")"
if tail -n 20 "$HH_EVIDENCE/app.log" | grep -q '"message":"Graceful shutdown complete"'; then
  hh_pass "AC14 'Graceful shutdown complete' is a JSON line near the end of stdout"
else hh_fail "AC14 no JSON 'Graceful shutdown complete' line in the last 20 lines"; fi

hh_step "log checks on the full app log (AC1, AC2, AC3, AC5)"
LOG="$(hh_native "$HH_EVIDENCE/app.log")"
check ac1-line-shape line-shape "$LOG" "$T"
check ac2-message one-event "$LOG" "$M1"
check ac2-exception one-event "$LOG" "$M2"
check ac3-default mdc "$LOG" "$TENANT_DEFAULT" default
check ac5-absent absent "$LOG" "$TOKEN" "$BASIC_B64" "$PASSWORD" "$PASSWORD_B64" "\"token\":true" "$EID" "$PAN" "$EMAIL" "$PHONE" "$MDC_TOKEN" \
    "$HOSTILE_PW" "${EID//-/}" "consumer-api-key-$TENANT_DEFAULT"
check ac5-negatives present "$LOG" "$NEG_EPOCH" "$NEG_ORDER" "$NEG_TRACE" "$NEG_UUID" "$NEG_TS"

hh_step "AC3 strict MDC mode: only traceId, spanId and the listed keys"
HH_APP_NAME=strict hh_app_start "${APP_ENV[@]}" DC_PLATFORM_LOGGING_MDC_INCLUDE=tenant
hh_wait_ready 180
TENANT_STRICT="t-$(hex 3)"
hh_http ac3-strict GET "/probe/mdc?tenant=$TENANT_STRICT"; hh_expect_status 200
HH_APP_NAME=strict hh_app_stop_graceful
check ac3-strict mdc "$(hh_native "$HH_EVIDENCE/strict.log")" "$TENANT_STRICT" strict

hh_step "AC12 startup with the collector unreachable: no hang"
[ "$(toxi POST /proxies/otel '{"enabled":false}')" = 200 ] || hh_fail "could not disable the collector proxy"
HH_APP_NAME=nocollector hh_app_start "${APP_ENV[@]}"
hh_wait_ready 180
STARTUP_NO_COLLECTOR=$HH_READY_SECONDS
hh_http ac12-nocollector GET /probe/context; hh_expect_status 200
HH_APP_NAME=nocollector hh_app_stop_graceful
if [ "$STARTUP_NO_COLLECTOR" -le $((STARTUP_HEALTHY + 30)) ]; then
  hh_pass "AC12 startup without a collector ${STARTUP_NO_COLLECTOR}s (healthy ${STARTUP_HEALTHY}s)"
else hh_fail "AC12 startup without a collector ${STARTUP_NO_COLLECTOR}s vs healthy ${STARTUP_HEALTHY}s"; fi

if grep -qF "$TOKEN" "$HH_EVIDENCE"/*.log; then hh_fail "a bearer token reached an app log"; else hh_pass "no bearer token in any app log"; fi
echo "startup_healthy_seconds=$STARTUP_HEALTHY startup_no_collector_seconds=$STARTUP_NO_COLLECTOR" >> "$HH_EVIDENCE/run-identity.txt"
