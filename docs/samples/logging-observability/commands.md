# Commands (all executed during hardening phase 2)

Run from the repository root on a machine with JDK 25, Maven 3.9 and Docker. The consumer run needs
about 3 GB of free memory: PostgreSQL, the collector, Toxiproxy and the app JVM.

## 1. Install the platform under test into an isolated repository

```bash
export JAVA_HOME=<jdk-25>; export PATH="$JAVA_HOME/bin:$PATH"
bash tooling/hardening/phase-repo.sh phase-02 \
  core/platform-starter-core,errors/platform-starter-errors,logging/platform-starter-logging,\
observability/platform-starter-observability,openapi/platform-starter-openapi,\
restclient/platform-starter-restclient,security/platform-starter-security,\
messaging/platform-starter-messaging-inmemory,test/platform-test-api
# prints ~/.m2-hardening/phase-02
```

## 2. Generate, build, run and probe a consumer (one command)

```bash
HH_MAVEN_REPO=~/.m2-hardening/phase-02 bash tooling/hardening/scenarios/phase-02-logging-observability.sh
```

What it does:
- generates `hardening-obs` with `-Dfeatures=data,restclient` (exact command in each run's
  `generate.cmd`) in `~/dc-hardening-runs/phase-02/<run>/app/`;
- adds the probe (`tooling/hardening/scenarios/phase-02/probe/ObservabilityProbe.java`), the PostgreSQL
  driver and `flyway-database-postgresql` (HI-0041);
- builds an `eclipse-temurin:25-jre` image;
- starts PostgreSQL, an OpenTelemetry Collector (file exporter) and Toxiproxy in front of the collector
  (images pinned by digest in `run-identity.txt`), plus a loopback JWKS issuer from the installed test kit;
- boots the app in `prod` with OTLP on, sampling 1.0, and runs AC1–AC14. The collector is stopped and
  slowed through Toxiproxy; the database is stopped with `docker stop`;
- boots two more containers: strict MDC mode, and startup with the collector unreachable;
- stops every app with `docker stop` (real SIGTERM) and removes everything it started;
- exits `0` only if every assertion passed and nothing was left behind.

The log checks are `tooling/hardening/scenarios/phase-02/checks.py` (run with `python -I`). Evidence
lands in `~/dc-hardening-runs/phase-02/logging-observability-<timestamp>/evidence/`.

## 3. Targeted platform tests

```bash
mvn -B -T1 -pl logging/platform-logging-api,logging/platform-logging-autoconfigure -am verify
mvn -B -T1 -pl observability/platform-observability-autoconfigure -am verify
```

## 4. Phase 1 consumer against Phase 2 code (regression)

```bash
HH_MAVEN_REPO=~/.m2-hardening/phase-02 bash tooling/hardening/scenarios/phase-01-core-errors.sh
```

## 5. Cleanup

```bash
docker ps -a --filter label=dc-hardening   # must be empty after a run
rm -rf ~/dc-hardening-runs/phase-02 ~/.m2-hardening/phase-02
```
