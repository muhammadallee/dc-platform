#!/usr/bin/env bash
# Hardening phase 7 (security), Gate F: an archetype-generated consumer (restclient for the relay checks, prod profile) against a scripted
# identity provider, which signs what a real one never would and fails on request, and one real Keycloak for the role mapping. Proves the
# acceptance criteria of docs/production-readiness/phase-07-security/scope.md.
#
#   HH_MAVEN_REPO=~/.m2-hardening/phase-07 bash tooling/hardening/scenarios/phase-07-security.sh
#
# JWKS timings are shortened for the run (cache 8 s, cooldown 2 s, outage tolerance 20 s, read 1 s); the defaults are tested in slices.
#
# Needs about 3 GB of free memory: Keycloak and one app container at a time (-Xmx300m).
source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYCLOAK_IMAGE="${HH_KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.4}"
ISS=http://issuer.test

idp() { curl -s -X "${2:-GET}" "$IDP/$1"; }
mint() { idp "__mint?$1"; }
mode() { [ "$(idp "__mode?m=$1" POST)" = ok ] || hh_abort "could not set the IdP to $1"; }
keys() { [ "$(idp "__keys?set=$1" POST)" = ok ] || hh_abort "could not set the keys to $1"; }
fetches() { idp __fetches; }
auth() { printf 'Authorization: Bearer %s' "$1"; }
ALL_TOKENS="$(mktemp)"
remember() { printf '%s\n' "$1" >> "$ALL_TOKENS"; printf '%s' "$1"; }
call() { # name path token [expected-status]
  hh_http "$1" GET "$2" -H "$(auth "$(remember "$3")")"
  [ -z "${4:-}" ] || hh_expect_status "$4"
}
refused() { # name token: a refusal, identical to all others but for instance, timestamp and trace
  call "$1" /probe/me "$2" 401
  hh_expect_json "exact generic body" "b['code']=='DC-SEC-0401' and b['detail']=='Full authentication is required to access this resource' and b['retryable'] is False"
  if [ "$(grep -ci '^www-authenticate: Bearer.*error' <<<"$(curl -s -D - -o /dev/null -H "$(auth "$2")" "$HH_BASE/probe/me")")" = 0 ]; then hh_pass "$1: no reason in the challenge"
  else hh_fail "$1: the challenge gives a reason"; fi
}
unavailable() { # name token
  call "$1" /probe/me "$2" 503
  hh_expect_json "exact generic body, retryable" "b['code']=='DC-SEC-0503' and b['detail']=='Authentication is temporarily unavailable' and b['retryable'] is True"
}
script() { curl -s -o /dev/null -X POST "$STUB/__script" -H 'Content-Type: application/json' --data "$1"; curl -s -o /dev/null -X POST "$STUB/__reset"; }
stub_eval() { curl -s "$STUB/__requests" | python -I -c "import json,sys; d=json.load(sys.stdin); r=d['requests']; print($1)"; }
refuse_start() { # name expected-text env...: must exit non-zero, with the text in its log
  local name=$1 text=$2; shift 2
  HH_APP_NAME=$name hh_app_start "${BASE_ENV[@]}" "$@"
  HH_APP_NAME=$name hh_wait_exit 120
  if [ "$HH_EXIT_CODE" != 0 ] && grep -qF "$text" "$HH_EVIDENCE/$name.log"; then hh_pass "AC15 $name: refused in ${HH_EXIT_SECONDS}s, naming '$text'"
  else hh_fail "AC15 $name: exit $HH_EXIT_CODE, log lacks '$text'"; fi
}

hh_init phase-07 security

hh_step "generate a consumer (restclient), with the probe"
hh_generate hardening-security restclient
hh_add_sources "$HERE/phase-07/probe" ae/gov/dubaicustoms/hardening/probe
hh_build_app
if grep -q platform-starter-security "$HH_EVIDENCE/dependency-tree.txt"; then hh_pass "dependency tree has platform-starter-security"
else hh_fail "dependency tree lacks platform-starter-security"; fi

hh_step "scripted identity provider and downstream, on the host"
NIMBUS=$(find "$HH_MAVEN_REPO/com/nimbusds/nimbus-jose-jwt" -name 'nimbus-jose-jwt-*.jar' | sort -V | tail -1)
[ -n "$NIMBUS" ] || hh_abort "no nimbus jar in $HH_MAVEN_REPO"
IDP_PORT_FILE="$HH_RUN_DIR/idp-port"
java -cp "$(hh_native "$NIMBUS")" "$(hh_native "$HERE/phase-07/Idp.java")" "$(hh_native "$IDP_PORT_FILE")" "$ISS" > "$HH_EVIDENCE/idp.log" 2>&1 &
HH_PIDS+=($!)
for _ in $(seq 1 120); do [ -s "$IDP_PORT_FILE" ] && break; sleep 0.5; done
[ -s "$IDP_PORT_FILE" ] || hh_abort "the IdP did not start (idp.log)"
IDP="http://127.0.0.1:$(cat "$IDP_PORT_FILE")"
JWKS_FOR_APP="http://host.docker.internal:$(cat "$IDP_PORT_FILE")/jwks"
STUB_PORT_FILE="$HH_RUN_DIR/stub-port"
python -I "$(hh_native "$HERE/phase-06/stub.py")" "$(hh_native "$STUB_PORT_FILE")" > "$HH_EVIDENCE/stub.log" 2>&1 &
HH_PIDS+=($!)
for _ in $(seq 1 60); do [ -s "$STUB_PORT_FILE" ] && break; sleep 0.5; done
[ -s "$STUB_PORT_FILE" ] || hh_abort "the stub did not start (stub.log)"
STUB="http://127.0.0.1:$(cat "$STUB_PORT_FILE")"

P=SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT
BASE_ENV=(SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS=-Xmx300m MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
  APP_GREETING_BASEURL=http://localhost:9 "PROBE_BASEURL=http://host.docker.internal:$(cat "$STUB_PORT_FILE")")
ID_ENV=("${P}_ISSUER_URI=$ISS" "${P}_JWK_SET_URI=$JWKS_FOR_APP" "${P}_AUDIENCES=svc" DC_PLATFORM_SECURITY_JWT_ALLOWINSECUREJWKS=true)
APP_ENV=("${BASE_ENV[@]}" "${ID_ENV[@]}" SPRING_APPLICATION_NAME=orders
  DC_PLATFORM_SECURITY_JWT_JWKS_CACHETTL=8s DC_PLATFORM_SECURITY_JWT_JWKS_REFRESHCOOLDOWN=2s
  DC_PLATFORM_SECURITY_JWT_JWKS_OUTAGETOLERANCE=20s DC_PLATFORM_SECURITY_JWT_JWKS_READTIMEOUT=1s
  DC_PLATFORM_SECURITY_JWT_JWKS_CONNECTTIMEOUT=1s DC_PLATFORM_RESTCLIENT_TOKENRELAY_CLIENTS=relayed
  "DC_PLATFORM_SECURITY_PERMITPATHS=/actuator/health/liveness,/actuator/health/readiness,/public/**")
printf '%s\n' "${APP_ENV[@]}" > "$HH_EVIDENCE/app-env.txt"

hh_step "AC22 start with the provider down, then serve when it returns"
mode fail
HH_APP_NAME=idp-down hh_app_start "${APP_ENV[@]}"
HH_APP_NAME=idp-down hh_wait_ready 180
DOWN_READY=$HH_READY_SECONDS
VALID=$(remember "$(mint '')")
if [ "$(fetches)" = 0 ]; then hh_pass "AC22 nothing fetched at startup"; else hh_fail "AC22 $(fetches) fetches at startup"; fi
unavailable ac22-cold "$VALID"
mode ok; sleep 3
call ac22-back /probe/me "$VALID" 200
HH_APP_NAME=idp-down hh_app_stop_graceful
docker logs "$HH_RUN_ID-idp-down" > "$HH_EVIDENCE/idp-down.stdout" 2>/dev/null || true
if grep -q 'could not fetch the JWKS' "$HH_EVIDENCE/idp-down.stdout"; then hh_pass "§7.4 a WARN says the keys cannot be fetched"; else hh_fail "no WARN on a failed fetch"; fi

hh_step "boot in prod with the provider healthy"
hh_app_start "${APP_ENV[@]}"
hh_wait_ready 180
echo "ready_healthy=${HH_READY_SECONDS}s ready_idp_down=${DOWN_READY}s" >> "$HH_EVIDENCE/run-identity.txt"

hh_step "AC1 valid token"
call ac1 /probe/me "$VALID" 200
hh_expect_json "principal read back" "b['subject']=='alice'"
hh_expect_json "no subject or claim in MDC" "set(b['mdc'])<={'traceId','spanId'}"

hh_step "AC2 expiry and not-before"
refused ac2-expired "$(mint 'exp=-120')"
refused ac2-future "$(mint 'nbf=120')"
call ac2-skew-exp /probe/me "$(mint 'exp=-5')" 200
call ac2-skew-nbf /probe/me "$(mint 'nbf=5')" 200

hh_step "AC3 issuer, audience, required claims"
refused ac3-iss "$(mint 'iss=http://evil.test')"
refused ac3-noiss "$(mint 'iss=')"
refused ac3-aud "$(mint 'aud=other')"
refused ac3-noaud "$(mint 'aud=')"
refused ac3-noexp "$(mint 'exp=')"
refused ac3-nosub "$(mint 'sub=')"

hh_step "AC4 signatures and algorithms"
refused ac4-forged "$(mint 'forged=true')"
refused ac4-none "$(mint 'alg=none')"
refused ac4-hs256 "$(mint 'alg=HS256')"
refused ac4-rs512 "$(mint 'alg=RS512')"

hh_step "AC5 unknown kid: refused, and cannot hammer the provider"
call ac5-warm /probe/me "$VALID" 200
sleep 3
F0=$(fetches); T0=$(date +%s)
STRANGER=$(mint 'kid=stranger')
refused ac5-unknown "$STRANGER"
for I in $(seq 1 20); do curl -s -o /dev/null -H "$(auth "$(remember "$STRANGER")")" "$HH_BASE/probe/me"; done
F1=$(fetches); T1=$(( $(date +%s) - T0 ))
# At most 2 per cooldown (2 s here), so per elapsed window; unlimited, it would be 21.
ALLOWED=$(( 2 * (T1 / 2 + 1) ))
if [ $((F1 - F0)) -le "$ALLOWED" ] && [ $((F1 - F0)) -lt 21 ]; then hh_pass "AC5 21 unknown-kid requests in ${T1}s cost $((F1 - F0)) fetches, at most $ALLOWED"
else hh_fail "AC5 $((F1 - F0)) fetches in ${T1}s, at most $ALLOWED"; fi

hh_step "AC9 no token, garbage"
hh_http ac9-none GET /probe/me; hh_expect_status 401
hh_expect_json "same refusal" "b['code']=='DC-SEC-0401' and b['detail']=='Full authentication is required to access this resource'"
refused ac9-garbage not-a-jwt
hh_http ac9-query GET "/probe/me?access_token=$VALID"; hh_expect_status 401

hh_step "AC6 provider down: known keys work, a fresh key is a 503, and it recovers"
call ac6-warm /probe/me "$VALID" 200
mode fail
sleep 9
call ac6-known /probe/me "$VALID" 200
K2=$(mint 'kid=k2')
unavailable ac6-fresh "$K2"
sleep 3
unavailable ac6-html-prep "$K2"
mode html
sleep 3
unavailable ac6-html "$K2"
mode redirect
sleep 3
unavailable ac6-redirect "$K2"
mode ok
sleep 3

hh_step "AC8 rotation: a new key works with no restart, a removed key stops after the cache"
keys k1,k2
call ac8-new /probe/me "$K2" 200
keys k2
sleep 11
refused ac8-removed "$VALID"
call ac8-still /probe/me "$K2" 200
keys k1,k2
sleep 11
call ac8-back /probe/me "$VALID" 200

hh_step "AC7 provider hung: a bounded answer"
mode hang
sleep 9
STARTED=$(date +%s)
hh_http ac7-fresh GET /probe/me -H "$(auth "$(remember "$(mint 'kid=stranger')")")"
TOOK=$(( $(date +%s) - STARTED ))
if [ "$TOOK" -le 6 ] && { [ "$HH_STATUS" = 503 ] || [ "$HH_STATUS" = 401 ]; }; then hh_pass "AC7 refused in ${TOOK}s ($HH_STATUS)"
else hh_fail "AC7 $HH_STATUS after ${TOOK}s"; fi
mode ok
sleep 3

hh_step "AC10 permissions: 401, 403, 200, and another client's role"
hh_http ac10-anon GET /probe/clerk; hh_expect_status 401
call ac10-none /probe/clerk "$(mint '')" 403
hh_expect_json "403 body, exactly generic" "b['code']=='DC-SEC-0403' and b['detail']=='The current principal lacks the required authority'"
call ac10-realm /probe/clerk "$(mint 'claims=%7B%22realm_access%22%3A%7B%22roles%22%3A%5B%22clerk%22%5D%7D%7D')" 200
call ac10-plain /probe/clerk "$(mint 'claims=%7B%22roles%22%3A%5B%22clerk%22%5D%7D')" 200
call ac10-client /probe/approver "$(mint 'claims=%7B%22resource_access%22%3A%7B%22orders%22%3A%7B%22roles%22%3A%5B%22approver%22%5D%7D%7D%7D')" 200
call ac10-other-client /probe/approver "$(mint 'claims=%7B%22resource_access%22%3A%7B%22billing%22%3A%7B%22roles%22%3A%5B%22approver%22%5D%7D%7D%7D')" 403
call ac10-scope /probe/scoped "$(mint 'claims=%7B%22scope%22%3A%22orders.read%20other%22%7D')" 200
call ac10-noscope /probe/scoped "$(mint '')" 403
call ac10-shape /probe/me "$(mint 'claims=%7B%22scp%22%3A%5B123%5D%2C%22realm_access%22%3A%22clerk%22%7D')" 200

hh_step "AC12, AC13 exposure, anonymous and with a token"
for EP in health/liveness health/readiness; do
  hh_http "ac12-$(tr '/' '-' <<<"$EP")" GET "/actuator/$EP"; hh_expect_status 200
done
for EP in health health/dependencies info prometheus platform env beans; do
  N=$(tr '/' '-' <<<"$EP")
  hh_http "ac12-$N-anon" GET "/actuator/$EP"; hh_expect_status 401
done
for EP in health health/dependencies info prometheus platform; do
  N=$(tr '/' '-' <<<"$EP")
  call "ac12-$N-token" "/actuator/$EP" "$VALID" 200
done
call ac12-env-token /actuator/env "$VALID" 404
for EP in /v3/api-docs /swagger-ui.html /swagger-ui/index.html; do
  hh_http "ac13-$(tr -d '/.' <<<"$EP")" GET "$EP"; hh_expect_status 401
done

hh_step "AC14 public by permit-paths and by SecurityCustomizer, and nothing else"
hh_http ac14-public GET /public/ping; hh_expect_status 200
hh_http ac14-custom GET /custom/ping; hh_expect_status 200
hh_http ac14-closed GET /closed/ping; hh_expect_status 401
hh_http ac14-dots GET '/public/..%2fclosed/ping' --path-as-is
if [ "$HH_STATUS" = 400 ] || [ "$HH_STATUS" = 401 ]; then hh_pass "AC14 encoded traversal refused ($HH_STATUS)"; else hh_fail "AC14 traversal: $HH_STATUS"; fi

hh_step "AC17 the principal over @Async, and two principals with no bleed"
call ac17-async /probe/async "$(mint 'sub=asyncer')" 200
hh_expect_json "same principal on the worker" "b=={'here':'asyncer','there':'asyncer'}"
A=$(remember "$(mint 'sub=principal-a')"); B=$(remember "$(mint 'sub=principal-b')")
BLEED=$(HH_BASE="$HH_BASE" A="$A" B="$B" python -I "$(hh_native "$HERE/phase-07/bleed.py")")
if [ "$BLEED" = "200 0" ]; then hh_pass "AC17 200 concurrent, two principals, no bleed"; else hh_fail "AC17 $BLEED"; fi
hh_http ac17-after GET /probe/me; hh_expect_status 401

hh_step "AC19 relay off by default, on by name"
script '{"default":{"status":200,"body":"hello"}}'
call ac19-plain /probe/call/plain "$VALID" 200
if [ "$(stub_eval "r[-1]['auth'] is None")" = True ]; then hh_pass "AC19 an unnamed client carries no token"; else hh_fail "AC19 an unnamed client carried a token"; fi
call ac19-relayed /probe/call/relayed "$VALID" 200
if [ "$(stub_eval "r[-1]['auth'] is not None")" = True ]; then hh_pass "AC19 a named client carries it"; else hh_fail "AC19 a named client did not"; fi

hh_step "AC20 a bare token in a malformed body stays out of the log (HI-0099)"
MARKER=hhBareSecretValue$(date +%s)
script "{\"default\":{\"status\":200,\"body\":\"{\\\"a\\\": $MARKER}\",\"headers\":{\"Content-Type\":\"application/json\"}}}"
call ac20 /probe/parse "$VALID" 500
if grep -q "$MARKER" <<<"$HH_BODY"; then hh_fail "AC20 in the body"; else hh_pass "AC20 not in the body"; fi

hh_step "AC21 SIGTERM with an authenticated request in flight"
curl -s -o "$HH_EVIDENCE/ac21.body" -w '%{http_code}' --max-time 40 -H "$(auth "$VALID")" "$HH_BASE/probe/slow?ms=3000" > "$HH_EVIDENCE/ac21.status" &
SLOW=$!
sleep 1
hh_app_stop_graceful
wait "$SLOW" || true
if [ "$(cat "$HH_EVIDENCE/ac21.status")" = 200 ] && [ "$(cat "$HH_EVIDENCE/ac21.body")" = finished ]; then hh_pass "AC21 the request finished"
else hh_fail "AC21 $(cat "$HH_EVIDENCE/ac21.status")"; fi

hh_step "AC18, AC20: every token and the marker absent from the logs"
docker logs "$HH_RUN_ID-app" > "$HH_EVIDENCE/app-all.log" 2>&1 || true
cat "$HH_EVIDENCE/idp-down.log" >> "$HH_EVIDENCE/app-all.log"
if grep -q "$MARKER" "$HH_EVIDENCE/app-all.log"; then hh_fail "AC20 the marker reached a log"; else hh_pass "AC20 the marker is in no log"; fi
HITS=$(sort -u "$ALL_TOKENS" | grep -cFf - "$HH_EVIDENCE/app-all.log" || true)
if [ "$HITS" = 0 ]; then hh_pass "AC18 none of the $(sort -u "$ALL_TOKENS" | wc -l) tokens used is in a log"; else hh_fail "AC18 $HITS log lines hold a token"; fi
if grep -qE 'eyJ[A-Za-z0-9_-]{8,}\.' "$HH_EVIDENCE/app-all.log"; then hh_fail "AC18 something JWT-shaped in a log"; else hh_pass "AC18 nothing JWT-shaped in a log"; fi
if grep -qE '"(subject|principal|sub)":"(alice|principal-|asyncer)' "$HH_EVIDENCE/app-all.log"; then hh_fail "the principal in a log field"; else hh_pass "no principal in a log field"; fi

hh_step "AC12 a separate management port stays closed"
HH_APP_NAME=mgmt hh_app_start "${APP_ENV[@]}" SERVER_PORT=8081 MANAGEMENT_SERVER_PORT=8080
HH_APP_NAME=mgmt hh_wait_ready 180
hh_http mgmt-ready GET /actuator/health/readiness; hh_expect_status 200
for EP in health info prometheus; do
  hh_http "mgmt-$EP-anon" GET "/actuator/$EP"; hh_expect_status 401
done
call mgmt-info-token /actuator/info "$VALID" 200
HH_APP_NAME=mgmt hh_app_stop_graceful

hh_step "AC16 a full consumer chain replaces the platform's"
HH_APP_NAME=fullchain hh_app_start "${BASE_ENV[@]}" PROBE_CHAIN=full
HH_APP_NAME=fullchain hh_wait_ready 180
hh_http full-open GET /closed/ping; hh_expect_status 200
hh_http full-denied GET /public/ping; hh_expect_status 403
HH_APP_NAME=fullchain hh_app_stop_graceful

hh_step "AC15 setups that must not start"
refuse_start no-issuer issuer-uri "${P}_JWK_SET_URI=$JWKS_FOR_APP" "${P}_AUDIENCES=svc" DC_PLATFORM_SECURITY_JWT_ALLOWINSECUREJWKS=true
refuse_start no-audience audiences "${P}_ISSUER_URI=$ISS" "${P}_JWK_SET_URI=$JWKS_FOR_APP" DC_PLATFORM_SECURITY_JWT_ALLOWINSECUREJWKS=true
refuse_start no-jwks jwk-set-uri "${P}_ISSUER_URI=$ISS" "${P}_AUDIENCES=svc"
refuse_start alg-none jws-algorithms "${ID_ENV[@]}" "${P}_JWS_ALGORITHMS=none"
refuse_start alg-hs256 jws-algorithms "${ID_ENV[@]}" "${P}_JWS_ALGORITHMS=HS256"
refuse_start big-skew clock-skew "${ID_ENV[@]}" DC_PLATFORM_SECURITY_JWT_CLOCKSKEW=6m
refuse_start http-jwks https "${P}_ISSUER_URI=$ISS" "${P}_JWK_SET_URI=$JWKS_FOR_APP" "${P}_AUDIENCES=svc"
refuse_start narrow-chain 'does not match every request' PROBE_CHAIN=narrow
refuse_start wildcard 'opens every path' "${ID_ENV[@]}" PROBE_WILDCARD=true
refuse_start wide-permit 'opens every path' "${ID_ENV[@]}" 'DC_PLATFORM_SECURITY_PERMITPATHS=/**'

hh_step "AC11 a real Keycloak token"
hh_infra keycloak "$KEYCLOAK_IMAGE" -p 127.0.0.1::8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=hhadmin -e KC_BOOTSTRAP_ADMIN_PASSWORD=hh-test-only \
    -e KC_HOSTNAME=http://keycloak:8080 -e KC_HTTP_ENABLED=true -e KC_HOSTNAME_STRICT=false -e JAVA_OPTS_KC_HEAP=-Xmx512m \
    -v "$(hh_native "$HERE/phase-07/keycloak-realm.json"):/opt/keycloak/data/import/realm.json:ro" -- start-dev --import-realm
KC="http://127.0.0.1:$(docker port "$HH_RUN_ID-keycloak" 8080/tcp | head -1 | sed 's/.*://')"
KC_UP=no
for _ in $(seq 1 180); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$KC/realms/hh/.well-known/openid-configuration")" = 200 ] && { KC_UP=yes; break; }
  sleep 1
done
[ "$KC_UP" = yes ] || hh_abort "Keycloak did not start"
KC_TOKEN=$(curl -s "$KC/realms/hh/protocol/openid-connect/token" -d grant_type=password -d client_id=hardening-security \
    -d username=kc-alice -d password=hh-test-only -d scope=openid | python -I -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
[ -n "$KC_TOKEN" ] || hh_abort "no Keycloak token"
remember "$KC_TOKEN" > /dev/null
KC_ENV=("${BASE_ENV[@]}" "${P}_ISSUER_URI=http://keycloak:8080/realms/hh" "${P}_JWK_SET_URI=http://keycloak:8080/realms/hh/protocol/openid-connect/certs"
  "${P}_AUDIENCES=svc" DC_PLATFORM_SECURITY_JWT_ALLOWINSECUREJWKS=true SPRING_APPLICATION_NAME=hardening-security)
HH_APP_NAME=kc hh_app_start "${KC_ENV[@]}"
HH_APP_NAME=kc hh_wait_ready 180
call ac11-me /probe/me "$KC_TOKEN" 200
hh_expect_json "subject and both kinds of role" "b['subject'] and 'ROLE_clerk' in b['roles'] and 'ROLE_approver' in b['roles']"
hh_expect_json "another client's role grants nothing" "'ROLE_admin' not in b['roles']"
call ac11-clerk /probe/clerk "$KC_TOKEN" 200
call ac11-approver /probe/approver "$KC_TOKEN" 200
HH_APP_NAME=kc hh_app_stop_graceful
HH_APP_NAME=kc-aud hh_app_start "${BASE_ENV[@]}" "${P}_ISSUER_URI=http://keycloak:8080/realms/hh" \
    "${P}_JWK_SET_URI=http://keycloak:8080/realms/hh/protocol/openid-connect/certs" "${P}_AUDIENCES=someone-else" DC_PLATFORM_SECURITY_JWT_ALLOWINSECUREJWKS=true
HH_APP_NAME=kc-aud hh_wait_ready 180
call ac11-aud /probe/me "$KC_TOKEN" 401
HH_APP_NAME=kc-aud hh_app_stop_graceful
docker logs "$HH_RUN_ID-kc" > "$HH_EVIDENCE/kc-all.log" 2>&1 || true
if grep -qF "$KC_TOKEN" "$HH_EVIDENCE/kc-all.log"; then hh_fail "AC18 the Keycloak token in a log"; else hh_pass "AC18 the Keycloak token is in no log"; fi
rm -f "$ALL_TOKENS"
