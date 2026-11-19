# Commands (all executed during hardening phase 1)

> **Spring-native:** these commands were re-run in Phase 1 against the cut scope. Runs and counts: [Phase 1 scope](../../spring-native/phase-01-scope-cut/scope.md#results).

Run from the repository root on a machine with JDK 25, Maven 3.9 and Docker.

## 1. Install the platform under test into an isolated repository

```bash
export JAVA_HOME=<jdk-25>; export PATH="$JAVA_HOME/bin:$PATH"
bash tooling/hardening/native-repo.sh native-01   # prints ~/.m2-hardening/native-01
```

It installs this checkout into a fresh repository with no older platform artifact in it. The REST client is parked, so the
runs that use it add its starter with an explicit version.

## 2. Generate, build, run and probe a consumer (one command)

```bash
HH_MAVEN_REPO=~/.m2-hardening/native-01 bash tooling/hardening/scenarios/phase-01-core-errors.sh
```

What it does:
- `mvn org.apache.maven.plugins:maven-archetype-plugin:3.1.2:generate -DarchetypeCatalog=local
  -DarchetypeGroupId=ae.gov.dubaicustoms.platform -DarchetypeArtifactId=platform-service-archetype
  -DarchetypeVersion=1.0.0-SNAPSHOT -DgroupId=ae.gov.dubaicustoms.hardening -DartifactId=hardening-core
  -Dpackage=ae.gov.dubaicustoms.hardening -Dfeatures=- -DinteractiveMode=false`
  in `~/dc-hardening-runs/phase-01/<run>/app/`
- adds the parked REST-client starter with an explicit version (`phase-06/edit_pom.py`)
- adds the public-API probe (`tooling/hardening/scenarios/phase-01/probe/HardeningProbe.java`)
- `mvn package -DskipTests`, builds an `eclipse-temurin:25-jre` image, starts it on a private Docker network
- runs every scenario, stops the app with `docker stop` (real SIGTERM), removes everything it started
- exits `0` only if every assertion passed and nothing was left behind

Evidence lands in `~/dc-hardening-runs/phase-01/core-errors-<timestamp>/evidence/` (`assertions.tsv`,
`run-identity.txt`, `http/*.body`, `app.log`).

## 3. Targeted platform tests

```bash
mvn -B -pl core/platform-core-api,core/platform-core-autoconfigure,errors/platform-errors-autoconfigure,\
restclient/platform-restclient-autoconfigure,examples/example-minimal \
  -am verify
```

## 4. Cleanup

```bash
docker ps -a --filter label=dc-hardening   # must be empty after a run
rm -rf ~/dc-hardening-runs/phase-01 ~/.m2-hardening/native-01
```
