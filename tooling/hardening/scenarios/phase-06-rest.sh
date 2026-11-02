#!/usr/bin/env bash
# Hardening phase 6 (REST client and resilience), Gate F: an archetype-generated consumer (restclient, prod profile) calling a
# scripted downstream through Toxiproxy. Proves the consumer-level acceptance criteria of
# docs/production-readiness/phase-06-rest-resilience/scope.md. Fixture, not certified here: security (JWKS).
#
#   HH_MAVEN_REPO=~/.m2-hardening/phase-06 bash tooling/hardening/scenarios/phase-06-rest.sh
#
# Request counts, headers and bytes sent are read from what the downstream recorded, not from the client.
#
# Needs about 2 GB of free memory: Toxiproxy and one app container (-Xmx300m).
source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOXIPROXY_IMAGE="${HH_TOXIPROXY_IMAGE:-ghcr.io/shopify/toxiproxy:2.9.0}"

toxi() { curl -s -o /dev/null -w '%{http_code}' -X "$1" "$TOXI_API$2" ${3:+-H 'Content-Type: application/json' --data "$3"}; }
toxic() { # name type attributes-json: on the way down to the stub, unless it says otherwise
  [ "$(toxi POST /proxies/stub/toxics "{\"name\":\"$1\",\"type\":\"$2\",\"stream\":\"${4:-downstream}\",\"attributes\":$3}")" = 200 ] \
      || hh_abort "could not add toxic $1"
}
untoxic() { [ "$(toxi DELETE "/proxies/stub/toxics/$1")" = 204 ] || hh_abort "could not remove toxic $1"; }
proxy_enabled() { [ "$(toxi POST /proxies/stub "{\"enabled\":$1}")" = 200 ] || hh_abort "could not set the proxy to $1"; }

script() { curl -s -o /dev/null -X POST "$STUB/__script" -H 'Content-Type: application/json' --data "$1"; curl -s -o /dev/null -X POST "$STUB/__reset"; }
# stub_eval <python over r, the list of requests, and s, the bytes sent>
stub_eval() {
  curl -s "$STUB/__requests" | python -I -c "import json,sys; d=json.load(sys.stdin); r=d['requests']; s=d['sent_bytes']; print($1)"
}
stub_count() { stub_eval 'len(r)'; }
expect_count() { # description expected
  local got; got=$(stub_count)
  if [ "$got" = "$2" ]; then hh_pass "$1: $2 request(s) reached the downstream"
  else hh_fail "$1: $got request(s) reached the downstream, expected $2"; fi
}
expect_stub() { # description python-expression
  if [ "$(stub_eval "bool($2)")" = True ]; then hh_pass "$1"; else hh_fail "$1 [$2]: $(stub_eval "$2")"; fi
}
expect_problem() { # status retryable
  hh_expect_status "$1"
  hh_expect_json "code DC-RCLIENT-0500, retryable=$2" "b.get('code')=='DC-RCLIENT-0500' and b.get('retryable')==$2"
  hh_expect_json "exactly the generic detail" "b.get('detail')=='An unexpected error occurred.'"
}
expect_seconds() { # description started max
  local took=$(( $(date +%s) - $2 ))
  if [ "$took" -le "$3" ]; then hh_pass "$1: ${took}s, at most $3 s"; else hh_fail "$1: ${took}s, more than $3 s"; fi
}
scrape() { curl -s --max-time 10 "${AUTH[@]}" "$HH_BASE/actuator/prometheus"; }
metric() { scrape | { grep -E "^$1\{" || true; } | { grep -F "$2" || true; } | { grep -F "${3:-}" || true; } | head -1 | awk '{print $NF}' | cut -d. -f1; }
expect_metric() { # description name filter filter expected
  local got; got=$(metric "$2" "$3" "$4")
  if [ "$got" = "$5" ]; then hh_pass "$1: $5"; else hh_fail "$1: got '${got}', expected $5"; fi
}
trace_header() { printf '00-%s-%s-01' "$1" "00f067aa0ba902b7"; }

hh_init phase-06 rest

hh_step "generate a consumer (restclient)"
hh_generate hardening-rest restclient
hh_add_sources "$HERE/phase-06/probe" ae/gov/dubaicustoms/hardening/probe
hh_build_app
for GA in platform-starter-restclient resilience4j-circuitbreaker resilience4j-bulkhead resilience4j-micrometer; do
  if grep -q "$GA" "$HH_EVIDENCE/dependency-tree.txt"; then hh_pass "dependency tree has $GA"
  else hh_fail "dependency tree lacks $GA"; fi
done

hh_step "loopback JWKS issuer (installed test kit) and the scripted downstream, both on the host"
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
STUB_PORT_FILE="$HH_RUN_DIR/stub-port"
python -I "$(hh_native "$HERE/phase-06/stub.py")" "$(hh_native "$STUB_PORT_FILE")" > "$HH_EVIDENCE/stub.log" 2>&1 &
HH_PIDS+=($!)
for _ in $(seq 1 60); do [ -s "$STUB_PORT_FILE" ] && break; sleep 0.5; done
[ -s "$STUB_PORT_FILE" ] || hh_abort "the stub did not start (stub.log)"
STUB_PORT=$(cat "$STUB_PORT_FILE")
STUB="http://127.0.0.1:$STUB_PORT"

hh_step "Toxiproxy in front of the downstream"
hh_infra toxiproxy "$TOXIPROXY_IMAGE" -p 127.0.0.1::8474 --add-host=host.docker.internal:host-gateway
TOXI_API="http://127.0.0.1:$(docker port "$HH_RUN_ID-toxiproxy" 8474/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 1 60); do [ "$(toxi GET /version)" = 200 ] && break; sleep 0.5; done
[ "$(toxi POST /proxies "{\"name\":\"stub\",\"listen\":\"0.0.0.0:8666\",\"upstream\":\"host.docker.internal:$STUB_PORT\"}")" = 201 ] \
    || hh_abort "could not create the toxiproxy route"

P=dc.platform.restclient
read -r -d '' CONFIG <<JSON || true
{"$P.defaults.read-timeout":"2s","$P.defaults.deadline":"8s",
 "$P.clients.main.circuit-breaker.enabled":false,"$P.clients.main.bulkhead.max-concurrent":60,
 "$P.clients.refused.circuit-breaker.enabled":false,"$P.clients.unknownhost.circuit-breaker.enabled":false,
 "$P.clients.cb.retry.enabled":false,"$P.clients.cb.circuit-breaker.sliding-window-size":4,
 "$P.clients.cb.circuit-breaker.minimum-calls":4,"$P.clients.cb.circuit-breaker.open-duration":"15s",
 "$P.clients.cb.circuit-breaker.half-open-calls":1,"$P.clients.cb.read-timeout":"8s",
 "$P.clients.bh.retry.enabled":false,"$P.clients.bh.circuit-breaker.enabled":false,"$P.clients.bh.bulkhead.max-concurrent":2,
 "$P.clients.bh.read-timeout":"6s",
 "$P.clients.dl.circuit-breaker.enabled":false,"$P.clients.dl.deadline":"1s","$P.clients.dl.read-timeout":"5s",
 "$P.token-relay.clients":"main",
 "management.endpoint.health.show-details":"always"}
JSON
APP_ENV=(SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS=-Xmx300m MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
  PROBE_BASEURL=http://toxiproxy:8666 APP_GREETING_BASEURL=http://toxiproxy:8666
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI="$JWKS_FOR_APP" SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$(cat "$IDP_DIR/issuer-uri")" SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=platform-test DC_PLATFORM_SECURITY_JWT_ALLOWINSECUREJWKS=true
  "SPRING_APPLICATION_JSON=$(printf '%s' "$CONFIG" | tr -d '\n')")
printf '%s\n' "${APP_ENV[@]}" > "$HH_EVIDENCE/app-env.txt"
{ echo "toxiproxy_image=$TOXIPROXY_IMAGE"; } >> "$HH_EVIDENCE/run-identity.txt"

hh_step "boot in prod with the downstream healthy"
hh_app_start "${APP_ENV[@]}"
hh_wait_ready 180
HEALTHY_READY=$HH_READY_SECONDS

hh_step "AC1: a normal call, on one trace, with the token relayed"
script '{"default":{"status":200,"body":"hello"}}'
T=4bf92f3577b34da6a3ce929d0e0e4736
hh_http ac1 GET "/probe/main/get?id=1" "${AUTH[@]}" -H "traceparent: $(trace_header $T)"
hh_expect_status 200
if [ "$HH_BODY" = hello ]; then hh_pass "AC1 the downstream's body came back"; else hh_fail "AC1 body: $HH_BODY"; fi
expect_count "AC1" 1
expect_stub "AC1 traceparent carries the inbound trace" "r[0]['headers'].get('traceparent','').startswith('00-$T-')"
expect_stub "AC1 no custom correlation header" "'x-correlation-id' not in r[0]['headers']"
expect_stub "AC1 the token is relayed" "r[0]['auth'] is not None"

hh_step "AC4: what is retried"
script '{"default":{"status":404,"body":"UPSTREAMMARKER"}}'
hh_http ac4-404 GET "/probe/main/get" "${AUTH[@]}"; expect_problem 500 False; expect_count "AC4 404" 1
if grep -q UPSTREAMMARKER <<<"$HH_BODY"; then hh_fail "AC4 the upstream body reached the caller"; else hh_pass "AC4 the upstream body stays out of the response"; fi
script '{"default":{"status":500}}'
hh_http ac4-500 GET "/probe/main/get" "${AUTH[@]}"; expect_problem 500 False; expect_count "AC4 500" 1
for CODE in 429 502 504; do
  script "{\"default\":{\"status\":$CODE}}"
  hh_http "ac4-$CODE" GET "/probe/main/get" "${AUTH[@]}"; expect_problem 503 True; expect_count "AC4 $CODE" 3
done
script '{"default":{"status":503}}'
T=5bf92f3577b34da6a3ce929d0e0e4737
hh_http ac4-503 GET "/probe/main/get" "${AUTH[@]}" -H "traceparent: $(trace_header $T)"; expect_problem 503 True; expect_count "AC4 503" 3
expect_stub "AC12 the trace is the same on every attempt" "all(q['headers'].get('traceparent','').startswith('00-$T-') for q in r)"
expect_stub "AC12 the token is the same on every attempt" "len({q['auth'] for q in r})==1 and r[0]['auth'] is not None"
script '{"default":{"status":200,"body":"recovered"},"queue":[{"status":503}]}'
hh_http ac4-recover GET "/probe/main/get" "${AUTH[@]}"; hh_expect_status 200; expect_count "AC4 recovery" 2
script '{"default":{"status":200,"body":"later"},"queue":[{"status":503,"headers":{"Retry-After":"2"}}]}'
STARTED=$(date +%s)
hh_http ac6-retry-after GET "/probe/main/get" "${AUTH[@]}"; hh_expect_status 200; expect_count "AC6 Retry-After" 2
if [ $(( $(date +%s) - STARTED )) -ge 1 ]; then hh_pass "AC6 Retry-After was waited for"; else hh_fail "AC6 Retry-After was not waited for"; fi

hh_step "AC2: slow and no response"
script '{"default":{"status":200,"body":"hello"}}'
toxic slow latency '{"latency":3000,"jitter":0}'
STARTED=$(date +%s)
hh_http ac2-slow GET "/probe/main/get" "${AUTH[@]}"; expect_problem 503 True; expect_seconds "AC2 slow, bounded by the deadline" "$STARTED" 10
untoxic slow
toxic silent timeout '{"timeout":0}'
STARTED=$(date +%s)
hh_http ac2-silent GET "/probe/main/get" "${AUTH[@]}"; expect_problem 503 True; expect_seconds "AC2 no response, bounded" "$STARTED" 10
untoxic silent
script '{"default":{"status":200,"body":"hello"}}'
hh_http ac2-after GET "/probe/main/get" "${AUTH[@]}"; hh_expect_status 200

hh_step "AC7: the deadline"
script '{"default":{"status":200,"delay_ms":3000}}'
STARTED=$(date +%s)
hh_http ac7 GET "/probe/dl/get" "${AUTH[@]}"; expect_problem 504 True; expect_seconds "AC7 stopped at the deadline" "$STARTED" 3
script '{"default":{"status":200,"body":"hello"}}'

hh_step "AC3: refused, unknown host"
STARTED=$(date +%s)
hh_http ac3-refused GET "/probe/refused/get" "${AUTH[@]}"; expect_problem 503 True; expect_seconds "AC3 refused" "$STARTED" 9
STARTED=$(date +%s)
hh_http ac3-unknown GET "/probe/unknownhost/get" "${AUTH[@]}"; expect_problem 503 True; expect_seconds "AC3 unknown host" "$STARTED" 9
hh_http ac3-refused-post GET "/probe/refused/write?method=POST" "${AUTH[@]}"; expect_problem 503 True

hh_step "AC10: POST and PATCH are sent exactly once"
for METHOD in POST PATCH; do
  script '{"default":{"status":503}}'
  hh_http "ac10-$METHOD" GET "/probe/main/write?method=$METHOD" "${AUTH[@]}"; expect_problem 500 False; expect_count "AC10 $METHOD on 503" 1
  script '{"default":{"status":503}}'
  hh_http "ac10-$METHOD-key" GET "/probe/main/write?method=$METHOD" "${AUTH[@]}" -H "Idempotency-Key: k-$METHOD"
  expect_problem 500 False; expect_count "AC10 $METHOD on 503, with a key" 1
  expect_stub "AC10 $METHOD key arrives unchanged" "r[0]['headers'].get('idempotency-key')=='k-$METHOD'"
  script '{"default":{"drop":true}}'
  hh_http "ac10-$METHOD-drop" GET "/probe/main/write?method=$METHOD" "${AUTH[@]}" -H "Idempotency-Key: d-$METHOD"
  expect_problem 500 False; expect_count "AC10 $METHOD, connection lost after sending" 1
  script '{"default":{"status":200,"delay_ms":3000}}'
  hh_http "ac10-$METHOD-slow" GET "/probe/main/write?method=$METHOD" "${AUTH[@]}"
  expect_problem 500 False; expect_count "AC10 $METHOD timed out: outcome unknown" 1
  script "{\"default\":{\"status\":307,\"headers\":{\"Location\":\"http://toxiproxy:8666/elsewhere\"}}}"
  hh_http "ac10-$METHOD-307" GET "/probe/main/write?method=$METHOD" "${AUTH[@]}"
  hh_expect_status 200; expect_count "AC10 $METHOD not re-sent by a 307" 1
done
script '{"default":{"status":503}}'
hh_http ac4-put GET "/probe/main/write?method=PUT" "${AUTH[@]}"; expect_problem 503 True; expect_count "AC4 PUT retried" 3

hh_step "AC5: a large error body, and redaction"
script '{"default":{"status":500,"body_size":67108864}}'
STARTED=$(date +%s)
hh_http ac5-large GET "/probe/main/kind" "${AUTH[@]}"; hh_expect_status 200
hh_expect_json "kind RESPONSE, status 500" "b['kind']=='RESPONSE' and b['status']==500"
hh_expect_json "the snippet is at most 1 KB" "len(b['snippet'])<=1024"
expect_seconds "AC5 returned without reading it all" "$STARTED" 5
sleep 1
expect_stub "AC5 the rest of the body was not read" "s < 67108864"
MARKER="HHSECRET$$"
script "{\"default\":{\"status\":500,\"body\":\"{\\\"password\\\":\\\"$MARKER\\\",\\\"id\\\":\\\"keep\\\"}\"}}"
hh_http ac5-redact GET "/probe/main/kind" "${AUTH[@]}"; hh_expect_status 200
hh_expect_json "the secret is gone, the rest stays" "'$MARKER' not in b['snippet'] and 'keep' in b['snippet']"
script '{"default":{"status":200,"body":"'"$(printf 'y%.0s' $(seq 1 40))"'","trickle_ms":200}}'
hh_http ac16-string GET "/probe/dl/get" "${AUTH[@]}"; expect_problem 504 True
script '{"default":{"status":200,"headers":{"Content-Type":"application/json"},"body":"{\"a\":\"'"$(printf 'y%.0s' $(seq 1 40))"'\"}","trickle_ms":200}}'
hh_http ac16-json GET "/probe/dl/json" "${AUTH[@]}"
# Through the JSON library: the deadline itself (504), or, where the library wraps it, a plain 500. Never a success.
hh_expect_json "AC16 a body failure inside JSON is a failure, with a matching retryable" \
    "(b.get('status')==504 and b.get('retryable')==True) or (b.get('status')==500 and b.get('retryable')==False)"
hh_expect_json "AC16 with the generic detail" "b.get('detail')=='An unexpected error occurred.'"

hh_step "AC8, AC14: the breaker opens, refuses, recovers; probes stay up"
script '{"default":{"status":503}}'
for I in 1 2 3 4; do hh_http "ac8-fail-$I" GET "/probe/cb/get" "${AUTH[@]}"; expect_problem 503 True; done
expect_count "AC8 retries off for this client" 4
expect_metric "AC8 the breaker's metric says open" resilience4j_circuitbreaker_state 'name="rest-cb"' 'state="open"' 1
hh_http ac14-deps GET /actuator/health/dependencies "${AUTH[@]}"; hh_expect_status 200
hh_expect_json "AC14 the dependencies group shows it open" "b['components']['restclient']['details']['rest-cb']=='OPEN'"
hh_http ac14-ready GET /actuator/health/readiness; hh_expect_status 200; hh_expect_json "AC14 readiness UP" "b['status']=='UP'"
hh_http ac14-live GET /actuator/health/liveness; hh_expect_status 200; hh_expect_json "AC14 liveness UP" "b['status']=='UP'"
hh_http ac8-open GET "/probe/cb/kind" "${AUTH[@]}"; hh_expect_status 200; hh_expect_json "AC8 refused as CIRCUIT_OPEN" "b['kind']=='CIRCUIT_OPEN' and b['retryable']"
hh_http ac8-open-problem GET "/probe/cb/get" "${AUTH[@]}"; expect_problem 503 True
hh_http ac8-open-post GET "/probe/cb/write?method=POST" "${AUTH[@]}"; expect_problem 503 True
expect_count "AC8 an open breaker sent nothing" 4
ATTEMPTS=$(metric platform_restclient_attempts_total 'client="cb"' 'outcome="circuit_open"')
if [ "${ATTEMPTS:-0}" -ge 2 ]; then hh_pass "AC8 attempts counted circuit_open: $ATTEMPTS"; else hh_fail "AC8 attempts metric: '$ATTEMPTS'"; fi
script '{"default":{"status":200,"body":"hello","delay_ms":5000}}'
sleep 16
curl -s -o "$HH_EVIDENCE/ac8-trial.body" -w '%{http_code}' --max-time 30 "${AUTH[@]}" "$HH_BASE/probe/cb/get" > "$HH_EVIDENCE/ac8-trial.status" &
TRIAL_PID=$!
for _ in $(seq 1 40); do [ "$(stub_count)" = 1 ] && break; sleep 0.1; done
expect_metric "AC8 half-open during its trial call" resilience4j_circuitbreaker_state 'name="rest-cb"' 'state="half_open"' 1
wait "$TRIAL_PID" || true
if [ "$(cat "$HH_EVIDENCE/ac8-trial.status")" = 200 ]; then hh_pass "AC8 the trial call succeeded"; else hh_fail "AC8 trial: $(cat "$HH_EVIDENCE/ac8-trial.status")"; fi
expect_metric "AC8 closed again" resilience4j_circuitbreaker_state 'name="rest-cb"' 'state="closed"' 1
hh_http ac8-closed GET /actuator/health/dependencies "${AUTH[@]}"
hh_expect_json "AC8 the group shows it closed" "b['components']['restclient']['details']['rest-cb']=='CLOSED'"

hh_step "AC9: the bulkhead under concurrency"
script '{"default":{"status":200,"body":"hello","delay_ms":3000}}'
mkdir -p "$HH_EVIDENCE/bh"
BH_PIDS=()
for I in 1 2 3 4 5 6; do
  curl -s -o "$HH_EVIDENCE/bh/$I.body" -w '%{http_code} %{time_total}\n' --max-time 30 "${AUTH[@]}" "$HH_BASE/probe/bh/get?id=$I" > "$HH_EVIDENCE/bh/$I.status" &
  BH_PIDS+=($!)
done
wait "${BH_PIDS[@]}"
OK=$(cat "$HH_EVIDENCE"/bh/*.status | grep -c '^200 ' || true)
REFUSED=$(cat "$HH_EVIDENCE"/bh/*.status | grep -c '^503 ' || true)
REFUSAL_BODIES=$(python -I - "$(hh_native "$HH_EVIDENCE/bh")" <<'PY'
import glob, json, sys
good = 0
for path in glob.glob(sys.argv[1] + "/*.status"):
    if open(path).read().startswith("503"):
        b = json.load(open(path[:-6] + "body", encoding="utf-8"))
        good += b.get("code") == "DC-RCLIENT-0500" and b.get("retryable") is True and b.get("detail") == "An unexpected error occurred."
print(good)
PY
)
if [ "$REFUSAL_BODIES" = "$REFUSED" ]; then hh_pass "AC9 each refusal is a generic, retryable ProblemDetail"; else hh_fail "AC9 only $REFUSAL_BODIES of $REFUSED refusals were right"; fi
if [ "$OK" = 2 ] && [ "$REFUSED" = 4 ]; then hh_pass "AC9 2 answered, 4 refused"; else hh_fail "AC9 $OK answered, $REFUSED refused"; fi
FAST=$(awk '$1==503 && $2<1.5{n++} END{print n+0}' "$HH_EVIDENCE"/bh/*.status)
if [ "$FAST" = "$REFUSED" ]; then hh_pass "AC9 the refused did not wait"; else hh_fail "AC9 only $FAST of $REFUSED refusals were fast"; fi
expect_count "AC9 only the permitted were sent" 2
script '{"default":{"status":200,"body":"hello"}}'
hh_http ac9-after GET "/probe/bh/get" "${AUTH[@]}"; hh_expect_status 200

hh_step "AC17: concurrent calls keep separate traces"
script '{"default":{"status":200,"body":"hello","delay_ms":100}}'
TR_PIDS=()
for I in $(seq 10 39); do
  T=$(printf '%032x' "$I")
  curl -s -o /dev/null --max-time 30 "${AUTH[@]}" -H "traceparent: $(trace_header "$T")" "$HH_BASE/probe/main/get?id=$I" &
  TR_PIDS+=($!)
done
wait "${TR_PIDS[@]}"
expect_count "AC17" 30
expect_stub "AC17 every call carried its own trace" "all(q['headers']['traceparent'].split('-')[1]=='%032x' % int(q['path'].split('id=')[1]) for q in r)"
docker logs "$HH_APP_CONTAINER" 2>/dev/null > "$HH_EVIDENCE/app-so-far.stdout"
BLEED=$(python -I -c "
import json,sys
bad=seen=0
for line in open(sys.argv[1],encoding='utf-8',errors='replace'):
    if not line.startswith('{'): continue
    try: e=json.loads(line)
    except ValueError: continue
    m=e.get('message','')
    if m.startswith('probe id=') and 10<=int(m[9:] or 0)<=39:
        seen+=1
        if e.get('traceId')!='%032x'%int(m[9:]): bad+=1
print(seen,bad)" "$(hh_native "$HH_EVIDENCE/app-so-far.stdout")")
if [ "$BLEED" = "30 0" ]; then hh_pass "AC17 no trace bleed in the log: 30 lines, each on its own trace"; else hh_fail "AC17 log lines seen, wrong: $BLEED"; fi

hh_step "§7.8: no secrets or queries in logs, tags"
if grep -q "$MARKER" "$HH_EVIDENCE/app-so-far.stdout"; then hh_fail "AC5 the secret reached a log"; else hh_pass "AC5 the secret is in no log"; fi
scrape > "$HH_EVIDENCE/prometheus.txt"
if grep -E 'http_client_requests' "$HH_EVIDENCE/prometheus.txt" | grep -q 'id='; then hh_fail "a query string reached a metric tag"; else hh_pass "no query string in the client metric's tags"; fi
if grep -q 'client_name="main"' "$HH_EVIDENCE/prometheus.txt"; then hh_pass "http.client.requests tagged by client name"; else hh_fail "no client_name tag"; fi
rm -f "$HH_EVIDENCE/app-so-far.stdout"

hh_step "AC15: SIGTERM during a backoff, then again with a send in flight"
script '{"default":{"status":200,"body":"late","delay_ms":1000},"queue":[{"status":503,"headers":{"Retry-After":"3"}}]}'
curl -s -o "$HH_EVIDENCE/ac15-backoff.body" -w '%{http_code}' --max-time 40 "${AUTH[@]}" "$HH_BASE/probe/main/get?id=900" > "$HH_EVIDENCE/ac15-backoff.status" &
BACKOFF_PID=$!
for _ in $(seq 1 40); do [ "$(stub_count)" = 1 ] && break; sleep 0.1; done
expect_count "AC15 the first attempt arrived" 1
sleep 1 # Its 503 is back by now, and the call is inside its 3 s wait.
hh_app_stop_graceful
wait "$BACKOFF_PID" || true
if [ "$(cat "$HH_EVIDENCE/ac15-backoff.status")" = 200 ] && [ "$(cat "$HH_EVIDENCE/ac15-backoff.body")" = late ]; then
  hh_pass "AC15 the call in its backoff finished, through a second attempt in flight"
else hh_fail "AC15 in-flight call: $(cat "$HH_EVIDENCE/ac15-backoff.status") $(cat "$HH_EVIDENCE/ac15-backoff.body")"; fi

hh_step "§7.11: start with the downstream down, then serve when it returns"
proxy_enabled false
HH_APP_NAME=downstream-down hh_app_start "${APP_ENV[@]}"
hh_wait_ready 180
DOWN_READY=$HH_READY_SECONDS
echo "ready_healthy=${HEALTHY_READY}s ready_downstream_down=${DOWN_READY}s" >> "$HH_EVIDENCE/run-identity.txt"
hh_pass "§7.11 ready in ${DOWN_READY}s with the downstream down, ${HEALTHY_READY}s with it up"
hh_http down GET "/probe/main/get" "${AUTH[@]}"; expect_problem 503 True
proxy_enabled true
script '{"default":{"status":200,"body":"hello"}}'
hh_http back GET "/probe/main/get" "${AUTH[@]}"; hh_expect_status 200
script '{"default":{"status":200,"body":"inflight","delay_ms":4000}}'
curl -s -o "$HH_EVIDENCE/ac15-send.body" -w '%{http_code}' --max-time 40 "${AUTH[@]}" "$HH_BASE/probe/bh/get?id=901" > "$HH_EVIDENCE/ac15-send.status" &
SEND_PID=$!
for _ in $(seq 1 40); do [ "$(stub_count)" = 1 ] && break; sleep 0.1; done
expect_count "AC15 the call is in flight" 1
HH_APP_NAME=downstream-down hh_app_stop_graceful
wait "$SEND_PID" || true
if [ "$(cat "$HH_EVIDENCE/ac15-send.status")" = 200 ] && [ "$(cat "$HH_EVIDENCE/ac15-send.body")" = inflight ]; then
  hh_pass "AC15 the call in flight at SIGTERM finished"
else hh_fail "AC15 in-flight send: $(cat "$HH_EVIDENCE/ac15-send.status") $(cat "$HH_EVIDENCE/ac15-send.body")"; fi

TOKEN_VALUE=$(cat "$IDP_DIR/token-valid")
if grep -qF -e "$TOKEN_VALUE" -r "$HH_EVIDENCE"; then hh_fail "a token reached the evidence"; else hh_pass "no token in the evidence"; fi
