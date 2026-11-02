# Commands

All were run during this phase, with JDK 25 and Docker running. Set `JAVA_HOME` to JDK 25 first.

## Targeted tests

```bash
mvn -o -T1 -pl restclient/platform-starter-restclient,resilience/platform-starter-resilience -am verify
```

Should end `BUILD SUCCESS`: 102 tests in the REST client's auto-configuration module, 42 in its API, 22 in resilience's.

## Timing tests, three in a row, no retries

```bash
mvn -o -T1 -pl restclient/platform-restclient-autoconfigure,resilience/platform-resilience-autoconfigure -am test \
  -Dtest='ResilienceBehaviourTest,RestClientRetryInteractionTest,DefaultPlatformRestClientFactoryTest,RemoteCallExceptionTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.rerunFailingTestsCount=0 -Djacoco.skip=true
```

## The consumer run

Builds its own Maven repository, generates a fresh service, and calls a scripted downstream through Toxiproxy. About 5 minutes and 2 GB of memory.

```bash
bash tooling/hardening/phase-06-repo.sh
HH_MAVEN_REPO=~/.m2-hardening/phase-06 bash tooling/hardening/scenarios/phase-06-rest.sh
```

Should end `HARNESS PASS — 182 passed, 0 failed`. Evidence lands in `~/dc-hardening-runs/phase-06/`. Containers are removed afterwards: `docker ps --filter label=dc-hardening` should list none.

## Regressions and golden path

```bash
HH_MAVEN_REPO=~/.m2-hardening/phase-06 bash tooling/hardening/scenarios/phase-01-core-errors.sh
HH_MAVEN_REPO=~/.m2-hardening/phase-06 bash tooling/hardening/scenarios/phase-02-logging-observability.sh
bash tooling/scripts/golden-path.sh
```

## Cleanup

```bash
rm -rf ~/.m2-hardening/phase-06 ~/dc-hardening-runs/phase-06
```
