#!/usr/bin/env bash
# Phase 0 harness self-test at the baseline: proves the harness can generate a consumer app outside
# the repo, run it in a container, probe it, stop it with a real SIGTERM, and clean up — and that a
# failed assertion turns the run red. No capability is certified by this scenario.
#
#   bash tooling/hardening/scenarios/phase-00-smoke.sh
#
# Requires the platform installed in HH_MAVEN_REPO (default ~/.m2/repository) and Docker running.
source "$(dirname "${BASH_SOURCE[0]}")/../lib/harness.sh"

hh_init phase-00 smoke

hh_step "generate and build a feature-less service"
hh_generate hardening-smoke -
hh_build_app

hh_step "boot in a container and wait for readiness"
hh_app_start SPRING_PROFILES_ACTIVE=default
hh_wait_ready 180

hh_step "probe"
hh_http readiness GET /actuator/health/readiness
hh_expect_status 200
hh_expect_json "readiness UP" "b['status'] == 'UP'"
hh_http liveness GET /actuator/health/liveness
hh_expect_status 200
hh_http unauthenticated GET /api/does-not-exist
hh_expect_status 401
if [ "${HH_SELFTEST_FAIL:-0}" = 1 ]; then
  # Negative self-test: a deliberately wrong expectation must make the whole run exit non-zero.
  hh_expect_status 418
fi

hh_step "graceful shutdown on SIGTERM"
hh_app_stop_graceful
