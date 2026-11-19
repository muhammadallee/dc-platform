# Phase 1 — Codex review

One round over the scope cut (plan §8 Gate C). Base `085d752`, reviewed `451420b`.
- Prompt: [`codex/r1-review.prompt.md`](codex/r1-review.prompt.md)
- Codex's answer, verbatim: [`codex/r1-review.out.md`](codex/r1-review.out.md)
- Verification passes on the fixes (prompts beside each answer):
  - [`r1-verify.out.md`](codex/r1-verify.out.md): 1, 3, 5 (the rejection) and 6 accepted; 2 and 4 not resolved; two new defects
    in the fix for 2 (path traversal in `native-repo.sh`; a failed build fell back to `~/.m2`)
  - [`r1-verify2.out.md`](codex/r1-verify2.out.md): 2 and both new defects accepted; 4 not resolved (four more pages)
  - [`r1-verify3.out.md`](codex/r1-verify3.out.md): 4 not resolved (three more pages); no new defects
  - [`r1-verify4.out.md`](codex/r1-verify4.out.md): 4 accepted; no new defects. Every finding is settled

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

**Verification:** the full reactor build passed in one run after the first fixes (`mvn -o -T1 install`, every test).
Later fixes touched only docs and scripts: the docs module (33 tests, link check, snippet compile) and `mkdocs build
--strict` passed after each. `native-repo.sh` built a repository end to end (50 platform artifacts, none cut) and rejects
a slug with a path in it before touching anything.

**How finding 4 converged:** each verification named pages the previous sweep had missed. After the third, every live
page under `docs/book`, `docs/modules`, `docs/concepts`, `docs/runbooks` and `docs/*.md` was searched for the names of
cut APIs and capabilities; each hit was fixed or bannered, and the dated records (`chassis-audit.md`, `reviews/`,
`upgrade/`) were left as records.
