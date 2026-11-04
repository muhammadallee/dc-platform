# Commands (all executed during hardening phase 1)

Run from the repository root on a machine with JDK 25, Maven 3.9 and Docker.

## 1. Install the platform under test into an isolated repository

```bash
export JAVA_HOME=<jdk-25>; export PATH="$JAVA_HOME/bin:$PATH"
bash tooling/hardening/phase-repo.sh phase-01 \
  core/platform-starter-core,errors/platform-starter-errors,security/platform-starter-security,\
restclient/platform-starter-restclient,observability/platform-starter-observability,\
logging/platform-starter-logging,test/platform-starter-test,openapi/platform-starter-openapi,\
validation/platform-starter-validation
# prints ~/.m2-hardening/phase-01
```

## 2. Generate, build, run and probe a consumer (one command)

```bash
HH_MAVEN_REPO=~/.m2-hardening/phase-01 bash tooling/hardening/scenarios/phase-01-core-errors.sh
```

What it does:
- `mvn org.apache.maven.plugins:maven-archetype-plugin:3.1.2:generate -DarchetypeCatalog=local
  -DarchetypeGroupId=ae.gov.dubaicustoms.platform -DarchetypeArtifactId=platform-service-archetype
  -DarchetypeVersion=1.0.0-SNAPSHOT -DgroupId=ae.gov.dubaicustoms.hardening -DartifactId=hardening-core
  -Dpackage=ae.gov.dubaicustoms.hardening -Dfeatures=restclient -DinteractiveMode=false`
  in `~/dc-hardening-runs/phase-01/<run>/app/`
- adds the public-API probe (`tooling/hardening/scenarios/phase-01/probe/HardeningProbe.java`)
- `mvn package -DskipTests`, builds an `eclipse-temurin:25-jre` image, starts it on a private Docker network
- starts a loopback JWKS issuer (`GateSupport idp`, from the installed test kit) reachable from the
  container at `host.docker.internal`
- runs every scenario, stops the app with `docker stop` (real SIGTERM), removes everything it started
- exits `0` only if every assertion passed and nothing was left behind

Evidence lands in `~/dc-hardening-runs/phase-01/core-errors-<timestamp>/evidence/` (`assertions.tsv`,
`run-identity.txt`, `http/*.body`, `app.log`).

## 3. Targeted platform tests

```bash
mvn -B -pl core/platform-core-api,core/platform-core-autoconfigure,errors/platform-errors-autoconfigure,\
security/platform-security-autoconfigure,restclient/platform-restclient-autoconfigure,examples/example-minimal \
  -am verify
```

## 4. Cleanup

```bash
docker ps -a --filter label=dc-hardening   # must be empty after a run
rm -rf ~/dc-hardening-runs/phase-01 ~/.m2-hardening/phase-01
```
