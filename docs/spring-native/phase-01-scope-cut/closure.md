# Phase 1 — Closure

**Verdict: PASS.** Branch `native/phase-01-scope-cut` (PR #8), base `085d752`. Reactor 120 → 51 modules: 43 kept, 8 parked,
69 cut. Restore point: tag `pre-spring-native`.

## The plan's steps

| # | Step | Result |
|---|---|---|
| 1 | Delete the cut modules, their BOM and dependency entries; drop third-party versions only they used | Done. 69 modules gone; AWS SDK and OpenFeature versions removed; springdoc kept for parked OpenAPI. `check-bom` passes and found one older error (`platform-docs` managed but never deployed), fixed |
| 2 | Remove the optional JWT links from data, outbox and the REST client | Done. JPA auditor always `"system"`; outbox records a principal only from a service's `PrincipalSource`; token relay deleted. Tests assert the fallbacks |
| 3 | Park REST client, resilience and OpenAPI | Done. `maven.deploy.skip=true`, out of the BOM and archetype, "Coming in iteration 2" on their pages. Since the review, the platform index (MCP server, skill) leaves them out too |
| 4 | Archetype | Done. No security, OpenAPI or REST-client starter, no `restclient` feature; `spring-boot-starter-validation`; in-memory messaging stays the default until Phase 6 (D137) |
| 5 | Examples | Done. `example-extension-provider` deleted; golden path pruned; `example-event-driven` on RabbitMQ only (fixed after the review) |
| 6 | Rules (ADR-011) | Done. `CLAUDE.md` rules 4, 5, 7, 9; ADR-011; ArchUnit allowlist; conformance rule no longer bans Kafka (fixed after the review) |
| 7 | Docs | Done. 14 module pages, chapters 05 and 09–13, `samples/security` deleted; nav and compatibility appendix updated; reference regenerated. The closed record got only the banner and tag links. Live pages that still describe cut capabilities carry a "not yet updated" banner (HI-0110) |
| 8 | Compose file | Done. RabbitMQ and Oracle Free (`mem_limit 2g`) only |
| 9 | HI-0109 (`develop` push builds) | Not reproduced: the push build of `085d752` passed with no change. Kept open until three green `develop` push builds in a row |
| 10 | Verify | Done. See below |
| 11 | Codex review | Done. See below |

## Verification

| Check | Result |
|---|---|
| Full `verify` | Passed; CI green on `451420b`. After the review fixes, the local full build passed in one run; later fixes were docs and scripts only (docs module, `mkdocs --strict`) |
| Golden path | 6/6 (was 9; the three `restclient` combinations went with the parked feature) |
| Smoke matrix | 3/3 |
| Trivy, two generated services | 0 vulnerabilities |
| Consumer runs, at their floors | Core + errors 77/77, logging + observability 65/65, data 91/91, outbox 103/103, RabbitMQ 63/63, C1 pass, REST client 182/182. Every drop is in the ledger in [`scope.md`](scope.md#drop-ledger); Codex reconciled it with no unlisted drop |

## Codex

One round, then four verification passes until every finding settled: [`review-round-1.md`](review-round-1.md). Two High and four
Medium findings: five fixed, one rejected with Codex's agreement (the issue register is the plan's continuing register).
The fixes for finding 2 introduced two defects, both caught by verification and fixed. The docs finding took four
passes: each named pages the previous sweep missed, until a search of every live docs folder for cut API names left
none unfixed or unbannered (fourth pass: accepted, no new defects). Issue register: HI-0113 to HI-0118.

## Carried forward

| Item | Where it is handled |
|---|---|
| Book, concept and sample pages still describing cut capabilities (bannered) | HI-0110: each capability phase rewrites its chapter; the rest by Phase 7 |
| Generated properties and feature references still list parked modules | HI-0111: Phase 7 |
| Default `dependencies` health group names `redis` and `restclient` | HI-0112: Phase 3 |
| Actuator endpoints open on the application port (no web security) | ND13: Phase 3 moves management to port 8081 |
| `TestTokens` / `TestJwtIssuer` stay in the test kit for services that add Spring Security themselves | Decided in Phase 8 (REST API, basic auth) |
| HI-0109 | Closes after three consecutive green `develop` push builds |
