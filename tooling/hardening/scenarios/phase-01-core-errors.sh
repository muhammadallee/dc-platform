#!/usr/bin/env bash
# Hardening phase 1 (core + errors), Gate F: an archetype-generated consumer, run in a container, proves
# the correlation and error contracts of docs/production-readiness/phase-01-core-errors/scope.md
# (AC1-AC13, AC18) over real HTTP. Fixtures, not certified here: logging, observability (tracing bridge),
# security, openapi, validation, restclient (the archetype baseline + the `restclient` feature).
#
#   HH_MAVEN_REPO=~/.m2-hardening/phase-01 bash tooling/hardening/scenarios/phase-01-core-errors.sh
source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKS=(python -I "$(hh_native "$HERE/phase-01/checks.py")")

hex() { python -I -c "import secrets,sys; print(secrets.token_hex(int(sys.argv[1])))" "$1"; }
tp() { printf '00-%s-%s-%s' "$1" "$(hex 8)" "${2:-01}"; }
header_absent() { # name: the last response's headers must not contain it
  if grep -qi "^$1:" "$HH_EVIDENCE/http/$HH_LAST_HTTP.headers"; then hh_fail "$HH_LAST_HTTP: response carries $1"
  else hh_pass "$HH_LAST_HTTP: no $1 header"; fi
}

hh_init phase-01 core-errors

hh_step "generate a consumer (restclient feature as the outbound fixture) and add the probe"
hh_generate hardening-core restclient
hh_add_sources "$HERE/phase-01/probe" ae/gov/dubaicustoms/hardening/probe
hh_build_app

hh_step "boot (sampling 1.0, core autoconfigure package at DEBUG — env relaxed binding lowercases class names — so the rejected-traceparent marker is visible)"
hh_app_start SPRING_PROFILES_ACTIVE=default MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0 \
    LOGGING_LEVEL_AE_GOV_DUBAICUSTOMS_PLATFORM_CORE_AUTOCONFIGURE=DEBUG
hh_wait_ready 180
HH_STARTUP_HEALTHY=$HH_READY_SECONDS

T=$(hex 16)
hh_step "AC1 valid traceparent is continued; RequestContext, MDC and @Async carry it"
hh_http ac1-valid GET /probe/context -H "traceparent: $(tp "$T")" -D "$HH_EVIDENCE/http/ac1-valid.headers"
hh_expect_status 200
hh_expect_json "context == inbound trace" "b['context'] == '$T' and b['mdcTraceId'] == '$T'"
hh_expect_json "spanId in MDC" "len(b['mdcSpanId']) == 16"
hh_expect_json "@Async sees the trace" "b['async']['context'] == '$T' and b['async']['mdcTraceId'] == '$T'"
hh_expect_json "@Async ran on another thread" "b['async']['thread'] != b['thread']"
hh_expect_json "no correlationId MDC key" "'mdcCorrelationId' not in b"
header_absent x-correlation-id

hh_step "AC2 missing traceparent starts a new trace"
hh_http ac2-missing GET /probe/context
hh_expect_status 200
hh_expect_json "new 32-hex trace" "len(b['context']) == 32 and b['context'] == b['mdcTraceId'] and b['context'] != '$T'"

hh_step "AC3 malformed / oversized traceparent starts a new trace"
BAD="00-zzzz-INJECTEDVALUE-01"
hh_http ac3-malformed GET /probe/context -H "traceparent: $BAD"
hh_expect_status 200
hh_expect_json "new trace for malformed" "len(b['context']) == 32 and 'INJECTED' not in b['context']"
OVERSIZE="00-$T-$(printf 'a%.0s' $(seq 1 300))-01"
hh_http ac3-oversized GET /probe/context -H "traceparent: $OVERSIZE"
hh_expect_status 200
hh_expect_json "new trace for oversized" "len(b['context']) == 32 and b['context'] != '$T'"

hh_step "AC4 a stray X-Correlation-Id is ignored"
STRAY=$(hex 16)
hh_http ac4-stray GET /probe/context -H "traceparent: $(tp "$T")" -H "X-Correlation-Id: $STRAY" \
    -D "$HH_EVIDENCE/http/ac4-stray.headers"
hh_expect_status 200
hh_expect_json "trace unaffected by the stray header" "b['context'] == '$T'"
header_absent x-correlation-id

hh_step "AC5 the platform REST client propagates traceparent only"
hh_http ac5-outbound GET /probe/outbound -H "traceparent: $(tp "$T")" -H "X-Correlation-Id: $STRAY"
hh_expect_status 200
hh_expect_json "downstream traceparent has the same trace" "b['downstream']['traceparent'].startswith('00-$T-')"
hh_expect_json "no correlation header downstream" "'x-correlation-id' not in b['downstream']"

hh_step "AC10 malformed JSON → 400 DC-CORE-0410 without parser internals"
hh_http ac10-malformed POST /probe/applicants -H "traceparent: $(tp "$T")" -H "Content-Type: application/json" \
    --data '{"name": [oops'
hh_expect_status 400
hh_expect_json "code + fixed detail + traceId" \
    "b['code'] == 'DC-CORE-0410' and b['detail'] == 'The request body is malformed or unreadable.' and b['traceId'] == '$T'"
hh_expect_json "no parser internals" "'jackson' not in json.dumps(b).lower() and 'Applicant' not in json.dumps(b)"

hh_step "AC11 validation → 400 DC-CORE-0400 with field paths, no rejected values"
PII="not-an-email-PII-4711"
hh_http ac11-validation POST /probe/applicants -H "traceparent: $(tp "$T")" -H "Content-Type: application/json" \
    --data "{\"name\":\"\",\"email\":\"$PII\"}"
hh_expect_status 400
hh_expect_json "code + traceId + retryable=false" "b['code'] == 'DC-CORE-0400' and b['traceId'] == '$T' and b['retryable'] is False"
hh_expect_json "field paths present" "sorted(e['field'] for e in b['errors']) == ['email', 'name']"
hh_expect_json "no rejectedValue, PII not echoed" "all('rejectedValue' not in e for e in b['errors']) and '$PII' not in json.dumps(b)"

hh_step "AC12 unknown exception → 500 generic body; details only in the log"
hh_http ac12-boom GET /probe/boom -H "traceparent: $(tp "$T")"
hh_expect_status 500
hh_expect_json "generic 500" "b['code'] == 'DC-CORE-0500' and b['detail'] == 'An unexpected error occurred.' and b['traceId'] == '$T'"
hh_expect_json "no internals in the body" "'secret-host' not in json.dumps(b) and 'stacktrace' not in b"

hh_step "AC13 401 / framework 404 carry code + traceId"
hh_http ac13-unauthenticated GET /hello -H "traceparent: $(tp "$T")"
hh_expect_status 401
hh_expect_json "401 contract" "b['code'] == 'DC-SEC-0401' and b['traceId'] == '$T' and b['retryable'] is False"
hh_http framework-404 GET /probe/does-not-exist -H "traceparent: $(tp "$T")"
hh_expect_status 404
hh_expect_json "404 contract" "b['code'] == 'DC-CORE-0499' and b['traceId'] == '$T'"

hh_step "AC8 200 parallel requests x 3 rounds: zero context bleed"
if "${CHECKS[@]}" concurrent "$HH_BASE" 200 3 > "$HH_EVIDENCE/ac8-concurrency.json"; then
  hh_pass "AC8 $(cat "$HH_EVIDENCE/ac8-concurrency.json")"
else
  hh_fail "AC8 $(cat "$HH_EVIDENCE/ac8-concurrency.json")"
fi

hh_step "AC18 SIGTERM with an in-flight slow request: it completes, shutdown completes"
curl -s -o "$HH_EVIDENCE/http/ac18-inflight.body" -w '%{http_code}' --max-time 60 "$HH_BASE/probe/slow?ms=6000" \
    > "$HH_EVIDENCE/http/ac18-inflight.status" &
INFLIGHT=$!
sleep 1
hh_app_stop_graceful
wait "$INFLIGHT" || true
if [ "$(cat "$HH_EVIDENCE/http/ac18-inflight.status")" = 200 ]; then
  hh_pass "in-flight request completed with 200 during graceful shutdown"
else
  hh_fail "in-flight request status $(cat "$HH_EVIDENCE/http/ac18-inflight.status") during graceful shutdown"
fi

hh_step "logs: trace correlation, sanitized rejection marker, no raw hostile input"
if "${CHECKS[@]}" logs "$(hh_native "$HH_EVIDENCE/app.log")" "$T" "INJECTEDVALUE" "$PII" > "$HH_EVIDENCE/logs-check.json"; then
  hh_pass "logs $(cat "$HH_EVIDENCE/logs-check.json")"
else
  hh_fail "logs $(cat "$HH_EVIDENCE/logs-check.json")"
fi
if grep -q "rejected inbound traceparent" "$HH_EVIDENCE/app.log"; then hh_pass "sanitized rejection marker logged"
else hh_fail "no sanitized rejection marker in the log"; fi
if grep -q "db-secret-host" "$HH_EVIDENCE/app.log" && grep "db-secret-host" "$HH_EVIDENCE/app.log" | grep -q "$T"; then
  hh_pass "AC12 full exception detail is in the log, on the request's trace"
else
  hh_fail "AC12 exception detail not found in the log with traceId $T"
fi

hh_step "AC6 never-sampled: trace ID still in context, MDC and error bodies"
HH_APP_NAME=unsampled hh_app_start SPRING_PROFILES_ACTIVE=default MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.0
hh_wait_ready 180
U=$(hex 16)
hh_http ac6-unsampled GET /probe/context -H "traceparent: $(tp "$U" 00)"
hh_expect_status 200
hh_expect_json "unsampled trace kept" "b['context'] == '$U' and b['mdcTraceId'] == '$U'"
hh_http ac6-unsampled-new GET /probe/context
hh_expect_json "fresh unsampled trace" "len(b['context']) == 32 and b['context'] == b['mdcTraceId']"
hh_http ac6-unsampled-error GET /probe/boom -H "traceparent: $(tp "$U" 00)"
hh_expect_status 500
hh_expect_json "traceId in an unsampled error body" "b['traceId'] == '$U'"
HH_APP_NAME=unsampled hh_app_stop_graceful

echo "startup_healthy_seconds=$HH_STARTUP_HEALTHY" >> "$HH_EVIDENCE/run-identity.txt"
