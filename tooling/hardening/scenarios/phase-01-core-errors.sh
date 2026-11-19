#!/usr/bin/env bash
# Hardening phase 1 (core + errors), Gate F: an archetype-generated consumer, run in a container, proves
# the correlation and error contracts of docs/production-readiness/phase-01-core-errors/scope.md
# (AC1-AC13, AC18) over real HTTP. Fixtures, not certified here: logging, observability (tracing bridge),
# restclient (parked; its starter is added by the harness).
#
# Spring-native Phase 1 port: JWT security is cut, so the JWKS issuer, the AC13 token checks (401 for
# missing/malformed/expired/untrusted tokens, the valid-token 200, the 403) and the probe's @PreAuthorize
# controller are gone (15 assertions). The REST client is parked: the service is generated with no features
# and its starter added with an explicit version, so the outbound checks stay. A JWT-shaped bearer is still
# sent on one request so "no bearer token in the log" keeps its meaning. See
# docs/spring-native/phase-01-scope-cut/scope.md.
#
# Spring-native Phase 2 port (docs/spring-native/phase-02-foundation/scope.md): the probe uses Spring types only (the
# trace from Micrometer's Tracer, ErrorResponseException with a code property, an ErrorResponse.Interceptor, Boot's
# RestClient.Builder), so the parked REST-client starter is no longer added. A grep fails the run if the probe or the
# generated service's main code imports a platform type (AC23). New: AC19 (the interceptor sees the originating
# exception), AC20 (a service's own code and detail), AC21 (@ResponseStatus). Floor 77.
#
#   repo=$(bash tooling/hardening/native-repo.sh native-NN) && HH_MAVEN_REPO=$repo bash tooling/hardening/scenarios/phase-01-core-errors.sh
source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKS=(python -I "$(hh_native "$HERE/phase-01/checks.py")")

hex() { python -I -c "import secrets,sys; print(secrets.token_hex(int(sys.argv[1])))" "$1"; }
tp() { printf '00-%s-%s-%s' "$1" "$(hex 8)" "${2:-01}"; }
markers() { # number of rejected-traceparent markers the app has logged so far
  docker logs "$HH_APP_CONTAINER" 2>&1 | grep -c "rejected inbound traceparent" || true
}
expect_marker_delta() { # expected-delta before-count label
  local now; now=$(markers)
  if [ $((now - $2)) -eq "$1" ]; then hh_pass "$3: exactly $1 rejection marker(s) for this request"
  else hh_fail "$3: expected $1 rejection marker(s), got $((now - $2))"; fi
}
header_absent() { # name: the last response's headers must not contain it
  if grep -qi "^$1:" "$HH_EVIDENCE/http/$HH_LAST_HTTP.headers"; then hh_fail "$HH_LAST_HTTP: response carries $1"
  else hh_pass "$HH_LAST_HTTP: no $1 header"; fi
}

hh_init phase-01 core-errors

hh_step "generate a consumer and add the probe"
hh_generate hardening-core -
hh_add_sources "$HERE/phase-01/probe" ae/gov/dubaicustoms/hardening/probe

hh_step "AC23 the service's main code imports no platform type (Spring types only)"
PLATFORM_IMPORTS=$(grep -rlE '^import (static )?ae\.gov\.dubaicustoms\.platform\.' "$HH_APP_DIR/src/main/java" || true)
if [ -z "$PLATFORM_IMPORTS" ]; then hh_pass "AC23 no platform import in src/main/java"
else hh_fail "AC23 platform imports in: $PLATFORM_IMPORTS"; fi
hh_build_app

# A JWT-shaped value: nothing validates it now, and it must never reach the log.
TOKEN="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJoYXJuZXNzLWNvcmUifQ.Y29yZS1zYW1wbGUtbm90LXJlYWw"

hh_step "boot (sampling 1.0, core autoconfigure package at DEBUG — env relaxed binding lowercases class names — so the rejected-traceparent marker is visible)"
hh_app_start SPRING_PROFILES_ACTIVE=default MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0 \
    LOGGING_LEVEL_AE_GOV_DUBAICUSTOMS_PLATFORM_CORE_AUTOCONFIGURE=DEBUG
hh_wait_ready 180
HH_STARTUP_HEALTHY=$HH_READY_SECONDS

T=$(hex 16)
hh_step "AC1 valid traceparent is continued; the Tracer, the MDC and @Async carry it"
M0=$(markers)
hh_http ac1-valid GET /probe/context -H "traceparent: $(tp "$T")" -D "$HH_EVIDENCE/http/ac1-valid.headers"
hh_expect_status 200
hh_expect_json "context == inbound trace" "b['context'] == '$T' and b['mdcTraceId'] == '$T'"
hh_expect_json "spanId in MDC" "len(b['mdcSpanId']) == 16"
hh_expect_json "@Async sees the trace" "b['async']['context'] == '$T' and b['async']['mdcTraceId'] == '$T'"
hh_expect_json "@Async ran on another thread" "b['async']['thread'] != b['thread']"
hh_expect_json "no correlationId MDC key" "'mdcCorrelationId' not in b"
header_absent x-correlation-id
expect_marker_delta 0 "$M0" ac1-valid

hh_step "AC2 missing traceparent starts a new trace"
hh_http ac2-missing GET /probe/context
hh_expect_status 200
hh_expect_json "new 32-hex trace" "len(b['context']) == 32 and b['context'] == b['mdcTraceId'] and b['context'] != '$T'"
hh_http ac2-missing-error GET /probe/boom
hh_expect_status 500
hh_expect_json "new trace reaches the error body" "len(b['traceId']) == 32 and b['traceId'] != '$T'"
AC2_ERROR_TRACE=$(python -I -c "import json,sys; print(json.load(open(sys.argv[1]))['traceId'])" \
    "$(hh_native "$HH_EVIDENCE/http/ac2-missing-error.body")")

hh_step "AC3 malformed / oversized traceparent starts a new trace"
BAD="00-zzzz-INJECTEDVALUE-01"
M0=$(markers)
hh_http ac3-malformed GET /probe/context -H "traceparent: $BAD"
hh_expect_status 200
hh_expect_json "new trace for malformed" "len(b['context']) == 32 and 'INJECTED' not in b['context']"
expect_marker_delta 1 "$M0" ac3-malformed
OVERSIZE_TAIL=$(printf 'a%.0s' $(seq 1 300))
OVERSIZE="00-$T-$OVERSIZE_TAIL-01"
M0=$(markers)
hh_http ac3-oversized GET /probe/context -H "traceparent: $OVERSIZE"
hh_expect_status 200
hh_expect_json "new trace for oversized" "len(b['context']) == 32 and b['context'] != '$T'"
expect_marker_delta 1 "$M0" ac3-oversized
ZERO="00-00000000000000000000000000000000-$(hex 8)-01"
M0=$(markers)
hh_http ac3-zero GET /probe/context -H "traceparent: $ZERO"
hh_expect_json "new trace for an all-zero trace id" "len(b['context']) == 32 and b['context'] != '0' * 32"
expect_marker_delta 1 "$M0" ac3-zero

hh_step "AC4 a stray X-Correlation-Id is ignored"
STRAY=$(hex 16)
hh_http ac4-stray GET /probe/context -H "traceparent: $(tp "$T")" -H "X-Correlation-Id: $STRAY" \
    -D "$HH_EVIDENCE/http/ac4-stray.headers"
hh_expect_status 200
hh_expect_json "trace unaffected by the stray header" "b['context'] == '$T'"
header_absent x-correlation-id

hh_step "AC5 Boot's RestClient.Builder propagates traceparent only"
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

hh_step "AC12 a service's 5xx ErrorResponseException → its code, generic body, detail only in the log"
hh_http ac12-platform GET /probe/platform-failure -H "traceparent: $(tp "$T")"
hh_expect_status 500
hh_expect_json "own code, generic detail, trace" \
    "b['code'] == 'DC-PROBE-0500' and b['detail'] == 'An unexpected error occurred.' and b['traceId'] == '$T'"
hh_expect_json "message not in the body" "'pricing-7' not in json.dumps(b)"

hh_step "async MVC: Callable success and failing CompletableFuture keep the trace on redispatch"
hh_http async-ok GET /probe/async-ok -H "traceparent: $(tp "$T")"
hh_expect_status 200
hh_expect_json "async handler sees the trace" "b['context'] == '$T' and b['mdcTraceId'] == '$T'"
hh_http async-fail GET /probe/async-fail -H "traceparent: $(tp "$T")"
hh_expect_status 500
hh_expect_json "async failure body carries the trace" \
    "b['code'] == 'DC-CORE-0500' and b['traceId'] == '$T' and 'secret-host' not in json.dumps(b)"

hh_step "async timeout (DeferredResult never completed) → 503, retryable, on the trace"
hh_http async-timeout GET /probe/async-timeout -H "traceparent: $(tp "$T")"
hh_expect_status 503
hh_expect_json "503 contract" "b['traceId'] == '$T' and b['retryable'] is True and b['detail'] == 'An unexpected error occurred.'"

hh_step "a direct request to the error path still has an instance"
hh_http direct-error GET /error -H "traceparent: $(tp "$T")" -H "Authorization: Bearer $TOKEN"
hh_expect_json "direct /error contract" "b['instance'] == '/error' and b['traceId'] == '$T'"

hh_step "servlet error dispatch (filter sendError) is a problem body with the trace, for JSON and HTML clients"
for ACCEPT in application/json text/html; do
  slug=$(printf '%s' "$ACCEPT" | tr -c 'a-z' '_')
  hh_http "error-dispatch-$slug" GET /probe/duplicate -H "traceparent: $(tp "$T")" -H "Accept: $ACCEPT" \
      -D "$HH_EVIDENCE/http/error-dispatch-$slug.headers"
  hh_expect_status 409
  hh_expect_json "409 contract ($ACCEPT)" \
      "b['code'] == 'DC-CORE-0499' and b['traceId'] == '$T' and b['instance'] == '/probe/duplicate' and b['status'] == 409"
  if grep -qi '^content-type: application/problem+json' "$HH_EVIDENCE/http/error-dispatch-$slug.headers"; then
    hh_pass "error-dispatch-$slug is application/problem+json"
  else hh_fail "error-dispatch-$slug is not application/problem+json"; fi
done

hh_step "AC13 a framework 404 carries the full contract (the 401/403 checks went with JWT security)"
hh_http framework-404 GET /probe/does-not-exist -H "traceparent: $(tp "$T")"
hh_expect_status 404
hh_expect_json "404 contract" "b['code'] == 'DC-CORE-0499' and b['traceId'] == '$T'"

hh_step "AC20 a service's own code and 4xx detail reach the body unchanged"
hh_http ac20-service GET /probe/service-error -H "traceparent: $(tp "$T")"
hh_expect_status 409
hh_expect_json "service code, detail and type" \
    "b['code'] == 'PROBE-0409' and b['detail'] == 'applicant 7 is already registered' and b['type'] == 'urn:dc:problem:PROBE-0409'"
hh_expect_json "platform extensions on a service error" "b['traceId'] == '$T' and b['retryable'] is False and 'timestamp' in b"

hh_step "AC21 an exception annotated @ResponseStatus(CONFLICT) is a 409, not a 500"
hh_http ac21-annotated GET /probe/annotated -H "traceparent: $(tp "$T")"
hh_expect_status 409
hh_expect_json "409 contract with the reason" \
    "b['code'] == 'DC-CORE-0499' and b['detail'] == 'order already shipped' and b['traceId'] == '$T'"

hh_step "AC19 the service's ErrorResponse.Interceptor sees the originating exception on every path"
hh_http ac19-boom GET /probe/boom -H "traceparent: $(tp "$T")"
hh_expect_json "unexpected exception" "b.get('seenBy') == 'probe:IllegalStateException'"
hh_http ac19-service GET /probe/service-error -H "traceparent: $(tp "$T")"
hh_expect_json "service ErrorResponseException" "b.get('seenBy') == 'probe:ErrorResponseException'"
hh_http ac19-annotated GET /probe/annotated -H "traceparent: $(tp "$T")"
hh_expect_json "@ResponseStatus exception" "b.get('seenBy') == 'probe:AlreadyShipped'"
hh_http ac19-validation POST /probe/applicants -H "traceparent: $(tp "$T")" -H "Content-Type: application/json" \
    --data '{"name":""}'
hh_expect_json "validation failure" "b.get('seenBy') == 'probe:MethodArgumentNotValidException'"
hh_http ac19-404 GET /probe/does-not-exist -H "traceparent: $(tp "$T")"
hh_expect_json "framework 404" "b.get('seenBy') == 'probe:NoResourceFoundException'"
hh_http ac19-dispatch GET /probe/duplicate -H "traceparent: $(tp "$T")"
hh_expect_json "servlet error dispatch (sendError: no exception)" "b.get('seenBy') == 'probe:none'"

hh_step "AC8 200 parallel requests x 3 rounds: zero context bleed"
if "${CHECKS[@]}" concurrent "$HH_BASE" 200 3 > "$HH_EVIDENCE/ac8-concurrency.json"; then
  hh_pass "AC8 $(cat "$HH_EVIDENCE/ac8-concurrency.json")"
else
  hh_fail "AC8 $(cat "$HH_EVIDENCE/ac8-concurrency.json")"
fi

hh_step "AC8 200 parallel failing requests x 3 rounds: every error body carries its own trace"
if "${CHECKS[@]}" concurrent-errors "$HH_BASE" 200 3 > "$HH_EVIDENCE/ac8-concurrent-errors.json"; then
  hh_pass "AC8 errors $(cat "$HH_EVIDENCE/ac8-concurrent-errors.json")"
else
  hh_fail "AC8 errors $(cat "$HH_EVIDENCE/ac8-concurrent-errors.json")"
fi

hh_step "AC18 SIGTERM with an in-flight slow request: it completes, shutdown completes"
curl -s -o "$HH_EVIDENCE/http/ac18-inflight.body" -w '%{http_code}' --max-time 60 "$HH_BASE/probe/slow?ms=6000" \
    > "$HH_EVIDENCE/http/ac18-inflight.status" &
INFLIGHT=$!
hh_wait_log "slow request entered" 20 || true
hh_app_stop_graceful
wait "$INFLIGHT" || true
if [ "$(cat "$HH_EVIDENCE/http/ac18-inflight.status")" = 200 ]; then
  hh_pass "in-flight request completed with 200 during graceful shutdown"
else
  hh_fail "in-flight request status $(cat "$HH_EVIDENCE/http/ac18-inflight.status") during graceful shutdown"
fi

hh_step "logs: trace correlation, sanitized rejection marker, no raw hostile input"
if "${CHECKS[@]}" logs "$(hh_native "$HH_EVIDENCE/app.log")" "$T" "INJECTEDVALUE" "$PII" "$OVERSIZE_TAIL" \
    > "$HH_EVIDENCE/logs-check.json"; then
  hh_pass "logs $(cat "$HH_EVIDENCE/logs-check.json")"
else
  hh_fail "logs $(cat "$HH_EVIDENCE/logs-check.json")"
fi
if grep -q "rejected inbound traceparent" "$HH_EVIDENCE/app.log"; then hh_pass "sanitized rejection marker logged"
else hh_fail "no sanitized rejection marker in the log"; fi
TIMEOUT_EVENTS=$(grep -F "error response [DC-CORE-0500] status 503" "$HH_EVIDENCE/app.log" \
    | grep -F "\"traceId\":\"$T\"" | grep -cF '"stack_trace":"' || true)
if [ "$TIMEOUT_EVENTS" -eq 1 ]; then
  hh_pass "async timeout logged exactly once, with its stack trace, on the request's trace"
else hh_fail "async timeout: expected 1 framework-5xx event with a stack trace on $T, got $TIMEOUT_EVENTS"; fi
for NEEDLE in db-secret-host pricing-7.internal; do
  # grep -c reads its whole input: a `| grep -q` would SIGPIPE the producer under pipefail on a big log.
  if [ "$(grep -F "$NEEDLE" "$HH_EVIDENCE/app.log" | grep -cF "\"traceId\":\"$T\"")" -gt 0 ]; then
    hh_pass "AC12 '$NEEDLE' detail is in the log, on the request's trace"
  else
    hh_fail "AC12 '$NEEDLE' detail not found in the log with traceId $T"
    grep -m5 -F "$NEEDLE" "$HH_EVIDENCE/app.log" | cut -c1-400 > "$HH_EVIDENCE/ac12-$NEEDLE-lines.txt"
  fi
done
if [ "$(grep -F "unhandled exception" "$HH_EVIDENCE/app.log" | grep -cF "\"traceId\":\"$AC2_ERROR_TRACE\"")" -gt 0 ]; then
  hh_pass "AC2 the fresh trace of an error body is the trace of its log line"
else
  hh_fail "AC2 no error log line with the error body's trace $AC2_ERROR_TRACE"
  grep -m5 -F "unhandled exception" "$HH_EVIDENCE/app.log" | cut -c1-300 > "$HH_EVIDENCE/ac2-error-lines.txt"
fi
if grep -qF -e "$TOKEN" "$HH_EVIDENCE/app.log"; then hh_fail "a bearer token reached the log"
else hh_pass "no bearer token in the log"; fi

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
