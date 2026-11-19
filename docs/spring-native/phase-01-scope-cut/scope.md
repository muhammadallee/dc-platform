# Phase 1 — Scope cut: scope

Plan v2 §10, Phase 1. Start commit: `085d752` (`develop`, merge of PR #7, tag `native/phase-00-closed`). Branch
`native/phase-01-scope-cut`, PR #8.

## What this phase must do

Make the reactor match plan v2 §2 without changing what the kept capabilities do, except where they were linked to cut
code. The acceptance criteria are the plan's Phase 1 steps 1–11; each is listed with its result in
`closure.md` once the phase closes.

## Module manifest

The reactor had 120 modules. After this phase it has 51.

| Band | Count | Modules |
|---|---|---|
| Kept | 43 | `build/*` (6), `core/*` (3), `errors/*` (3), `logging/*` (3), `observability/*` (2), `data/*` (4), `messaging/*` except Kafka (11), `test/platform-test-api`, `test/platform-starter-test`, `test/tck/platform-tck-messaging`, `tooling/*` (4), `examples/example-minimal`, `-golden-path`, `-event-driven`, `docs/platform-docs` |
| Parked | 8 | `openapi/*` (2), `restclient/*` (3), `resilience/*` (3): built and tested, `maven.deploy.skip=true`, not in `platform-bom` |
| Cut | 69 | `security/*` (7), `validation/*` (3), `cache/*` (4), `redis/*` (2), `scheduling/*` (2), `locking/*` (7), `events/*` (3), `audit/*` (7), `idempotency/*` (3), `ratelimit/*` (6), `storage/*` (7), `files/*` (3), `flags/*` (6), Kafka messaging (2), six TCKs, `example-extension-provider` |

The in-memory messaging modules stay until Phase 6 (D137).

## What changes in kept code

| Change | Where | Behaviour after |
|---|---|---|
| JWT auditor link removed | `platform-data-jpa-autoconfigure` | The auditor always records `"system"` |
| Principal source removed | `platform-messaging-outbox-autoconfigure` | No principal stored unless the service supplies a `PrincipalSource` |
| Token relay removed | `platform-restclient-autoconfigure` (parked) | No bearer token forwarded |
| Kafka transport removed | `platform-messaging-autoconfigure` | RabbitMQ or in-memory only |
| Test containers trimmed | `platform-test-api` | `Containers.postgres()` only |
| Bans lifted | `platform-service-parent` | No ban on `spring-kafka`, the S3 SDK, Resilience4j or springdoc |
| Archetype | `platform-service-archetype` | No security, validation, OpenAPI or REST-client starter; `spring-boot-starter-validation`; `/actuator/platform` outside `prod` only |

## Consumer runs: port plan and drop ledger

Each kept v3 run is re-run on this phase's code with its JWT, platform-constraint and token-relay parts removed (plan
rule 14). Every dropped or changed assertion gets one row below, and a run passes at its v3 count minus its rows.

| Run | v3 count | Order | Notes |
|---|---|---|---|
| Data (`phase-03-data-access.sh`) | 92 | 1 | Auditor now `"system"`; the Prometheus scrape no longer needs a token |
| Outbox (`phase-04-outbox.sh`) | 106 | 2 | Principal column no longer filled |
| RabbitMQ (`phase-05-rabbit.sh`) | 63 | 3 | Kafka gone from messaging auto-configuration |
| C1 (outbox harness as C1, plus RabbitMQ AC1 and AC5) | 106 + 2 | 4 | |
| REST client (`phase-06-rest.sh`) | 182 | 5 | Parked: generated with `features=none`, starter added from the installed repository with an explicit version (a harness step for a parked module) |
| Core + errors (`phase-01-core-errors.sh`) | 92 | 6 | Generated with `restclient` in v3; its outbound-failure assertions move to Phase 9 |
| Logging + observability (`phase-02-logging-observability.sh`) | 76 | 7 | |

### Drop ledger

**Dropped** assertions lower the run's floor; **changed** ones keep the count but test something else.

| Run | Assertion | Kind | Why |
|---|---|---|---|
| Data | `ac11-anon`: anonymous Prometheus scrape refused (401) | Dropped | No web security in iteration 1; ND13 moves management to its own port in Phase 3 |
| Outbox | AC16: relay request status, relayed event delivered, relayed event in the outbox table (3) | Dropped | The in-process events relay went with the events module |
| Outbox | AC12: the message carries the JWT subject | Changed | Now: no principal source is configured, so the message carries no principal; a JWT-shaped bearer is still sent and must reach no row |
| REST client | AC1: the token is relayed | Changed | Now: the inbound bearer is **not** forwarded (the relay was removed) |
| REST client | AC12: the token is the same on every attempt | Changed | Now: no attempt carries an Authorization header |
| REST client | "no token in the evidence" | Changed | Checks the JWT-shaped bearer the run sends instead of an issued token |
| Core + errors | AC13: 401 for a missing token (status, contract, type and instance: 3) | Dropped | JWT security is cut |
| Core + errors | AC13: malformed, expired, untrusted token, each status, contract and challenge (9) | Dropped | JWT security is cut |
| Core + errors | AC13: a valid token gets 200 (1); 403 status and contract (2) | Dropped | JWT security and `@PreAuthorize` are cut |
| Core + errors | "no bearer token in the log" | Changed | Checks a JWT-shaped bearer the run sends |
| Logging + observability | AC9: the seven unexposed endpoints, "with a token" 404 (7) | Dropped | Without security they duplicate the anonymous checks |
| Logging + observability | AC9: `/actuator/platform` with a token 200, and its capability JSON (2); `/actuator/info` with a token (1) | Dropped | `/actuator/platform` is not exposed in `prod` now; info needs no token |
| Logging + observability | AC9: the seven unexposed endpoints anonymous; `/actuator/platform` anonymous; `/actuator/info` anonymous | Changed | 401 became 404, 404 and 200: exposure is now decided by the exposure list alone |
| Logging + observability | `ac7-anon`: anonymous scrape refused (401) | Dropped | As for data |

### Results

All runs on branch `native/phase-01-scope-cut`, repository `~/.m2-hardening/native-01` (a fresh install of this branch;
no cut module in it). Evidence: [`evidence/`](evidence/).

| Run | v3 count | Dropped | Floor | Result |
|---|---|---|---|---|
| Core + errors | 92 | 15 | 77 | **77 PASS** |
| Logging + observability | 76 | 11 | 65 | **65 PASS** |
| Data | 92 | 1 | 91 | **91 PASS** |
| Outbox | 106 | 3 | 103 | **103 PASS** |
| RabbitMQ | 63 | 0 | 63 | **63 PASS** |
| C1 (outbox run as C1, plus RabbitMQ AC1 and AC5) | 106 + 2 | 3 | 103 + 2 | **PASS** (the outbox and RabbitMQ runs above, same commit) |
| REST client (parked) | 182 | 0 | 182 | **182 PASS** |

Two runs failed their first attempt on a defect in the port itself, not in the platform: the outbox run stopped when a
query for the now-empty principal returned no row under `set -e`, and the logging run sent no Authorization header to a
probe endpoint that reads one. Both were fixed in the harness and passed on the next run. Neither is a flaky pass: the
harness changed between the two attempts.
