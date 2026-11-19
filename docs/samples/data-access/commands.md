# Commands (all executed during hardening phase 3)

> **Spring-native:** these commands were re-run in Phase 1 against the cut scope. Runs and counts: [Phase 1 scope](../../spring-native/phase-01-scope-cut/scope.md#results).

Run from the repository root on a machine with JDK 25, Maven 3.9 and Docker. The consumer run needs about
3 GB of free memory: one Oracle Free container (about 2 GB) and one app container at a time. A cold Oracle
start copies about 3 GB of datafiles and took 6 minutes here.

## 1. Install the platform under test into an isolated repository

```bash
export JAVA_HOME=<jdk-25>; export PATH="$JAVA_HOME/bin:$PATH"
repo=$(bash tooling/hardening/native-repo.sh native-01)   # ~/.m2-hardening/native-01; stop if it fails
```

It installs this checkout into a fresh repository with no older platform artifact in it. The REST client is parked, so the
runs that use it add its starter with an explicit version.

## 2. Generate, build, run and probe a consumer (one command)

```bash
HH_MAVEN_REPO=${repo:?build the repository first} bash tooling/hardening/scenarios/phase-03-data-access.sh
```

What it does:
- generates `hardening-data` with `-Dfeatures=data -Ddatabase=oracle` (exact command in each run's
  `generate.cmd`) in `~/dc-hardening-runs/phase-03/<run>/app/`;
- adds the probe (`tooling/hardening/scenarios/phase-03/probe/`) and one migration
  (`phase-03/migration/V2__hh_probe_tables.sql`); the generated `pom.xml` is not edited;
- builds an `eclipse-temurin:25-jre` image; starts Oracle Free, Toxiproxy in front of its listener and a
  busybox `nc` silent peer (images pinned by digest in `run-identity.txt`);
- creates a schema owner and a runtime user (role with DML grants only) as the DBA account;
- boots the app in `prod` with every timeout at its default and runs AC1, AC3–AC6, AC9, AC11, AC13, AC14
  and the read-timeout backstop: Oracle slow, down and silent through Toxiproxy; pool exhaustion with
  20 parallel transactions; SIGTERM during a transaction; three more starts (slow, down, silent peer);
- removes everything it started and exits `0` only if every assertion passed and nothing was left behind.

The checks are `tooling/hardening/scenarios/phase-03/checks.py` (run with `python -I`). A full run takes
about 30 minutes, 10 of them for the silent-peer start. Evidence lands in
`~/dc-hardening-runs/phase-03/data-access-<timestamp>/evidence/`.

## 3. Targeted platform tests

```bash
mvn -B -T1 -pl errors/platform-errors-autoconfigure,data/platform-data-api,data/platform-data-jpa-autoconfigure -am verify
# Oracle container tests (44), Docker required; the container is reusable with testcontainers.reuse.enable=true
mvn -B -T1 -Pdocker -pl data/platform-data-jpa-autoconfigure verify
```

## 4. Earlier phases' consumers against Phase 3 code (regression)

```bash
HH_MAVEN_REPO=${repo:?build the repository first} bash tooling/hardening/scenarios/phase-01-core-errors.sh
HH_MAVEN_REPO=${repo:?build the repository first} bash tooling/hardening/scenarios/phase-02-logging-observability.sh
```

## 5. Cleanup

```bash
docker ps -a --filter label=dc-hardening   # must be empty after a run
rm -rf ~/dc-hardening-runs/phase-03 ~/.m2-hardening/native-01
```
