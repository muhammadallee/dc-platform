# Phase 1 — Codex review

One round over the scope cut (plan §8 Gate C). Base `085d752`, reviewed `451420b`.
- Prompt: [`codex/r1-review.prompt.md`](codex/r1-review.prompt.md)
- Codex's answer, verbatim: [`codex/r1-review.out.md`](codex/r1-review.out.md)
- Verification pass on the fixes: [`codex/r1-verify.out.md`](codex/r1-verify.out.md)

Codex: "Found two High and four Medium issues." It found no cut dependency in the kept code or build, no defect in the
changed kept behaviour, no release leak of a parked module through the BOM, the deploy or the archetype, and no assertion
dropped or weakened outside the ledger (it reconciled core 15, logging 11, data 1, outbox 3, RabbitMQ 0, REST 0).

| # | Severity | Finding | Claude | Fix |
|---|---|---|---|---|
| 1 | High | The consumer conformance rule still bans `KafkaTemplate` and Kafka listener containers and points to `EventPublisher`, whose Kafka transport is gone. A service that uses Spring Kafka itself fails its `PlatformConformanceTest` | Agree | `PlatformUsageRules` bans only RabbitMQ types; a new test checks Kafka types pass. `docs/modules/dx.md` row updated |
| 2 | High | `phase-04/05/06-repo.sh` select cut modules, so the documented consumer-run commands fail; `phase-repo.sh` copies `~/.m2-baseline`, which still holds cut artifacts | Agree. The Phase 1 runs avoided this by hand (a fresh repository, recorded in `scope.md`), but the committed commands did not | New `tooling/hardening/native-repo.sh`: copies a seed for third-party artifacts, deletes every platform artifact, installs the checkout. Run end to end: 50 platform artifacts, none cut. The four per-phase scripts are deleted; `phase-repo.sh` is marked historical; plan §5.9, the scenario usage lines and the sample command pages use the new script. The three v3 runs that need JWT security (`phase-07-c2`, `phase-07-security`, `phase-08-allcaps`) now stop at once with a "not ported" message |
| 3 | Medium | The platform index, which the MCP server and the skill ship, offers the parked OpenAPI, REST-client and resilience starters | Agree | The index generator keeps only starters listed in the BOM, for capabilities and snippets. `PlatformIndexTest` asserts every released starter is in and no parked one is. Regenerated index, `SKILL.md` and `capabilities.md` list only released starters |
| 4 | Medium | Live pages still give unusable instructions (`Containers.kafka()`, the validation starter, the events starter) and false security claims ("authenticated by default") | Agree | Fixed in place: `testing.md` (Postgres container, TCK table, `TestTokens` wording), `examples.md`, book chapter 2 (Spring's validation starter) and 7 (events starter, Kafka), book index. Every other live book and sample page that still describes cut capabilities carries a "not yet updated for Spring-native" banner pointing to the capability status; each is rewritten in its capability's phase (HI-0110) |
| 5 | Medium | Edits to `production-readiness/issue-register.md` (HI-0109 update, HI-0110 to HI-0112) break the frozen hardening record | **Disagree.** Plan §8 Gate E names that register as the place Spring-native findings go ("numbering continues from HI-0110"). The frozen record is the assessment, the release decision and the phase folders (plan §10 Phase 1 step 7). The review prompt wrongly put the whole `production-readiness/**` tree in scope | None to the register. The register's header now says rows from HI-0109 on come from the Spring-native plan. Later review prompts name the frozen files |
| 6 | Medium | `example-event-driven` keeps an in-memory profile active by default, so the two services started normally cannot reach each other; step 5 says RabbitMQ only | Agree | The Maven profiles are gone: both services depend on the RabbitMQ starter and set the broker in `application.yml`. Tests keep the recording test transport and need no broker (all three pass). `examples.md` shows the RabbitMQ run |

**Codex's other answers, taken as they are:**
- `Containers` has no test of its own. `postgres()` is used by the data module's Docker tests; a dedicated test is not added.
- Prometheus is reachable without authentication on the application port until Phase 3 moves management to port 8081
  (ND13). This is the declared deferral in the ledger, not a new weakening.

**Verification:** the full reactor build passed in one run after the fixes (`mvn -o -T1 install`, every test). The
targeted run of `platform-test-api` and the event-driven example passed first. CI runs on the pushed commit.
