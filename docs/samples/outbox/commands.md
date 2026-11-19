# Commands (all executed during hardening phase 4)

> **Spring-native:** these commands were re-run in Phase 1 against the cut scope. Runs and counts: [Phase 1 scope](../../spring-native/phase-01-scope-cut/scope.md#results).

Run from the repository root on a machine with JDK 25, Maven 3.9 and Docker. The consumer run needs about 4 GB of free memory
(Oracle, RabbitMQ, up to two app containers) and takes about 25 minutes, the Oracle image's first start (about 6 minutes)
included. Each run keeps about 200 MB under `~/dc-hardening-runs/`: delete old ones, a full disk stops everything.

## 1. Install the platform under test into an isolated repository

```bash
export JAVA_HOME=<jdk-25>; export PATH="$JAVA_HOME/bin:$PATH"
repo=$(bash tooling/hardening/native-repo.sh native-01)   # ~/.m2-hardening/native-01; stop if it fails
```

It installs this checkout into a fresh repository with no older platform artifact in it. The REST client is parked, so the
runs that use it add its starter with an explicit version.

## 2. Generate, build, run and probe a consumer (one command)

```bash
HH_MAVEN_REPO=${repo:?build the repository first} bash tooling/hardening/scenarios/phase-04-outbox.sh
```

What it does:
- generates `hardening-outbox` with `-Dfeatures=data,messaging -Ddatabase=oracle` (exact command in each run's `generate.cmd`);
- adopts the outbox as `outbox.md` says: swaps the sample's in-memory transport for RabbitMQ, adds the outbox
  starter, copies `platform_outbox.sql` out of the jar as `V2`, and adds a probe and a `V3` migration;
- starts Oracle Free, RabbitMQ and Toxiproxy in front of both (digests in `run-identity.txt`);
- creates a schema owner and a runtime user with a DML-only role;
- boots in `prod` and runs the scenarios in `verification.md`, including real `SIGKILL`s and a second instance;
- removes everything it started, and exits `0` only if every assertion passed and nothing was left behind.

Delivery is counted from a table the consumer's handler writes, one row per delivery, so loss and duplicates are facts rather
than inferences. The drainer's retry timings are shortened through its documented properties so the run takes minutes
(`app-env.txt`); every other default stands, batch size included.

## 3. Targeted platform tests

```bash
mvn -B -T1 -pl messaging/platform-messaging-outbox-autoconfigure,messaging/platform-messaging-rabbit -am verify
# Oracle and RabbitMQ container tests; Docker required; Oracle is reusable with testcontainers.reuse.enable=true
mvn -B -T1 -Pdocker -pl messaging/platform-messaging-outbox,messaging/platform-messaging-rabbit -am verify
```

## 4. Earlier phases' consumers against Phase 4 code (regression)

```bash
HH_MAVEN_REPO=${repo:?build the repository first} bash tooling/hardening/scenarios/phase-01-core-errors.sh
HH_MAVEN_REPO=${repo:?build the repository first} bash tooling/hardening/scenarios/phase-02-logging-observability.sh
HH_MAVEN_REPO=${repo:?build the repository first} bash tooling/hardening/scenarios/phase-03-data-access.sh
```

## 5. Cleanup

```bash
docker ps -a --filter label=dc-hardening   # must be empty after a run
rm -rf ~/dc-hardening-runs/phase-04 ~/.m2-hardening/native-01
```
