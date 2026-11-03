#!/usr/bin/env bash
# Rebuilds the phase-07 consumer repository with the full module list, then prints its path.
# A partial list mixes baseline modules with new ones.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
bash tooling/hardening/phase-repo.sh phase-07 "core/platform-starter-core,errors/platform-starter-errors,\
logging/platform-starter-logging,observability/platform-starter-observability,openapi/platform-starter-openapi,\
restclient/platform-starter-restclient,resilience/platform-starter-resilience,security/platform-starter-security,\
security/platform-starter-security-authz,validation/platform-starter-validation,messaging/platform-starter-messaging-inmemory,\
messaging/platform-messaging-test,messaging/platform-messaging-kafka,messaging/platform-starter-messaging-rabbit,\
messaging/platform-starter-messaging-outbox,events/platform-starter-events,data/platform-starter-data-jpa,\
data/platform-starter-data-jpa-oracle,test/platform-test-api"
