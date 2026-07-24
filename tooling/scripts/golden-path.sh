#!/usr/bin/env bash
# The DX contract, executable end to end: install the platform, generate a service from the archetype,
# build it, boot it, and probe it. This is the one-command health check for the whole stack (runbooks/
# claude-code-handoff.md). Wire it into CI as a required job.
#
# Time budget: the DX SLA is < 10 minutes wall clock (fresh service in under 10 min). We fail past it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# maven-archetype-plugin is PINNED to 3.1.2 on purpose: 3.2.0+ made archetype:generate fork a
# generate-sources lifecycle (<executePhase>), which requires a project and so fails project-less
# ("The goal you specified requires a project ...") on Maven 3.9.x. 3.1.2 generates project-less cleanly.
ARCHETYPE_PLUGIN="org.apache.maven.plugins:maven-archetype-plugin:3.1.2"
GROUP_ID="ae.gov.dubaicustoms.platform"

START=$(date +%s)
SLA_SECONDS=600
check_sla() {
  local now elapsed
  now=$(date +%s); elapsed=$((now - START))
  if [ "$elapsed" -gt "$SLA_SECONDS" ]; then
    echo "GOLDEN PATH FAILED: exceeded the ${SLA_SECONDS}s DX SLA (took ${elapsed}s)"; exit 1
  fi
}

# The generated service builds against the platform version currently in the reactor.
REV=$(mvn -q -N help:evaluate -Dexpression=revision -DforceStdout)
echo "== Golden path for platform ${REV} (SLA ${SLA_SECONDS}s) =="

echo "== 1/5 Install the platform into the local repo =="
mvn -T1C install -DskipTests -q
check_sla

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

echo "== 2/5 Generate a service from the archetype (features=messaging) =="
( cd "$WORKDIR" && mvn -B -q "${ARCHETYPE_PLUGIN}:generate" \
    -DarchetypeGroupId="${GROUP_ID}" \
    -DarchetypeArtifactId=platform-service-archetype \
    -DarchetypeVersion="${REV}" \
    -DgroupId=com.dc.demo -DartifactId=demo -Dpackage=com.dc.demo \
    -DplatformVersion="${REV}" -Dfeatures=messaging -DinteractiveMode=false )
DEMO="$WORKDIR/demo"

# D3: the generated service must carry CLAUDE.md (agents read it) and the conformance test.
echo "== Assert generated agent + conformance artifacts =="
test -f "$DEMO/CLAUDE.md"      || { echo "GOLDEN PATH FAILED: generated service has no CLAUDE.md"; exit 1; }
grep -rq "PlatformConformanceTest" "$DEMO/src/test" \
    || { echo "GOLDEN PATH FAILED: generated service has no PlatformConformanceTest"; exit 1; }
check_sla

echo "== 3/5 Build the generated service (runs PlatformConformanceTest) =="
( cd "$DEMO" && mvn -q verify )
grep -rq "PlatformConformanceTest" "$DEMO/target/surefire-reports" \
    || { echo "GOLDEN PATH FAILED: PlatformConformanceTest did not run"; exit 1; }
check_sla

echo "== 4/5 Boot the service and probe it =="
# start/stop (not run) so the script stays CI-friendly.
( cd "$DEMO" && mvn -q spring-boot:start )
trap '( cd "$DEMO" && mvn -q spring-boot:stop ) || true; rm -rf "$WORKDIR"' EXIT

curl -sf localhost:8080/actuator/health >/dev/null \
    || { echo "GOLDEN PATH FAILED: /actuator/health not healthy"; exit 1; }
curl -sf localhost:8080/actuator/platform | grep -q messaging \
    || { echo "GOLDEN PATH FAILED: /actuator/platform does not report messaging"; exit 1; }

echo "== 5/5 Stop the service =="
( cd "$DEMO" && mvn -q spring-boot:stop )

ELAPSED=$(($(date +%s) - START))
echo "GOLDEN PATH OK (${ELAPSED}s, SLA ${SLA_SECONDS}s)"
