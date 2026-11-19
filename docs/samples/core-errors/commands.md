# Commands

All run in Spring-native Phase 2, on branch `native/phase-02-foundation`. Run from the repository root with JDK 25,
Maven 3.9 and Docker.

## 1. Build the platform into an isolated repository

```bash
export JAVA_HOME=<jdk-25>; export PATH="$JAVA_HOME/bin:$PATH"
repo=$(bash tooling/hardening/native-repo.sh native-02)   # ~/.m2-hardening/native-02; stop if it fails
```

It installs this checkout into a fresh repository with no older platform artifact in it.

## 2. Generate, build, run and probe a consumer (one command)

```bash
HH_RUNS_ROOT="$HOME/dc-hardening-runs/native-02" \
HH_MAVEN_REPO=${repo:?build the repository first} bash tooling/hardening/scenarios/phase-01-core-errors.sh
```

What it does:
- generates `hardening-core` from the archetype with no feature options;
- fails if the service's main code imports a platform type (AC23), then adds the probe
  (`tooling/hardening/scenarios/phase-01/probe/HardeningProbe.java`, Spring types only);
- `mvn package -DskipTests`, builds an `eclipse-temurin:25-jre` image, starts it on a private Docker network;
- runs every scenario, stops the app with `docker stop` (a real SIGTERM), removes everything it started;
- exits `0` only if every assertion passed and nothing was left behind.

Evidence lands in `~/dc-hardening-runs/native-02/phase-01/core-errors-<timestamp>/evidence/` (`assertions.tsv`,
`run-identity.txt`, `http/*.body`, `app.log`).

## 3. Targeted platform tests

```bash
mvn -o -T1 -pl core/platform-core-autoconfigure,errors/platform-errors-autoconfigure -am verify
```

## 4. The lab

```bash
bash tooling/labs/run-lab.sh first-service
```

## 5. Cleanup

```bash
docker ps -a --filter label=dc-hardening   # must be empty after a run
rm -rf ~/dc-hardening-runs/native-02 ~/.m2-hardening/native-02
```
