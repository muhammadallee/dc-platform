# Commands

All were run during this phase, with JDK 25 and Docker running. Set `JAVA_HOME` to JDK 25 first.

## Targeted tests

```bash
mvn -o -T1 -pl security/platform-security-autoconfigure,security/platform-security-authz-autoconfigure,restclient/platform-restclient-autoconfigure -am install
```

Should end `BUILD SUCCESS`: 80 tests in security's auto-configuration module, 23 in authz's, 108 in the REST client's. Run three times in a row, they passed each time with no reruns.

## The consumer run

Builds its own Maven repository, generates a fresh service, and runs it against a scripted identity provider and a real Keycloak. About 10 minutes and 2 GB of memory.

```bash
mvn -o clean
bash tooling/hardening/phase-07-repo.sh
HH_MAVEN_REPO=~/.m2-hardening/phase-07 bash tooling/hardening/scenarios/phase-07-security.sh
```

Should end `HARNESS PASS — 170 passed, 0 failed`. Evidence lands in `~/dc-hardening-runs/phase-07/`. Containers are removed afterwards: `docker ps --filter label=dc-hardening` should list none.

`mvn -o clean` first: stale coverage data from a filtered test run breaks the build of the repository (HI-0003).

## Composition checkpoint C2

Security, the REST client, Oracle and observability in one service. Needs the Oracle Free image, about 15 minutes.

```bash
HH_MAVEN_REPO=~/.m2-hardening/phase-07 bash tooling/hardening/scenarios/phase-07-c2.sh
```

Should end `HARNESS PASS — 61 passed, 0 failed`.

## Regressions and golden path

```bash
for s in phase-01-core-errors phase-02-logging-observability phase-03-data-access phase-04-outbox phase-05-rabbit phase-06-rest; do
  HH_MAVEN_REPO=~/.m2-hardening/phase-07 bash tooling/hardening/scenarios/$s.sh
done
bash tooling/scripts/golden-path.sh
```

## Cleanup

```bash
rm -rf ~/.m2-hardening/phase-07 ~/dc-hardening-runs/phase-07
```
