# Commands (all executed during hardening phase 5)

Run from the repository root with JDK 25, Maven 3.9 and Docker. A consumer run needs about 4 GB of free memory and takes about 18 minutes.

## Build the platform under test into an isolated repository

```bash
export JAVA_HOME=<jdk-25>; export PATH="$JAVA_HOME/bin:$PATH"
bash tooling/hardening/phase-05-repo.sh
```

After a revert, run `mvn -o clean` first: a stale `jacoco.exec` fails the coverage check (HI-0003).

## Consumer run (one command)

```bash
HH_MAVEN_REPO=~/.m2-hardening/phase-05 bash tooling/hardening/scenarios/phase-05-rabbit.sh
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
rm -rf ~/dc-hardening-runs/phase-05 ~/.m2-hardening/phase-05
```
