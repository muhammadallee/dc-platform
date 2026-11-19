# Commands (all executed during hardening phase 5)

> **Spring-native:** these commands were re-run in Phase 1 against the cut scope. Runs and counts: [Phase 1 scope](../../spring-native/phase-01-scope-cut/scope.md#results).

Run from the repository root with JDK 25, Maven 3.9 and Docker. A consumer run needs about 4 GB of free memory and takes about 18 minutes.

## Build the platform under test into an isolated repository

```bash
export JAVA_HOME=<jdk-25>; export PATH="$JAVA_HOME/bin:$PATH"
bash tooling/hardening/native-repo.sh native-01   # prints ~/.m2-hardening/native-01
```

It installs this checkout into a fresh repository with no older platform artifact in it. The REST client is parked, so the
runs that use it add its starter with an explicit version.

After a revert, run `mvn -o clean` first: a stale `jacoco.exec` fails the coverage check (HI-0003).

## Consumer run (one command)

```bash
HH_MAVEN_REPO=~/.m2-hardening/native-01 bash tooling/hardening/scenarios/phase-05-rabbit.sh
```

It generates a service, starts Oracle, RabbitMQ and Toxiproxy, runs the scenarios in `verification.md`, removes everything it started,
and exits `0` only if every assertion passed.

## Targeted tests

```bash
mvn -B -T1 -Pdocker -pl messaging/platform-messaging-rabbit,messaging/platform-messaging-autoconfigure -am verify
```

## Inspect and replay a DLQ

```bash
export RABBIT_MGMT_URL=http://rabbitmq.internal:15672 RABBIT_USER=ops RABBIT_PASSWORD=...
python tooling/scripts/rabbit-dlq-replay.py inspect dc.orders.order.placed.billing
python tooling/scripts/rabbit-dlq-replay.py replay  dc.orders.order.placed.billing
```

## Cleanup

```bash
docker ps -a --filter label=dc-hardening   # must be empty after a run
rm -rf ~/dc-hardening-runs/phase-05 ~/.m2-hardening/native-01
```
