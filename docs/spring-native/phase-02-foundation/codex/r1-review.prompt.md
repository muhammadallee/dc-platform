# Codex review — Spring-native Phase 2 (foundation: core + errors)

You are the independent reviewer for a Spring Boot 4.1.2 platform ("chassis") in this repository. Review read-only.

**Base commit:** `516f54c` (`develop`, start of Phase 2). **Reviewed commit:** `c7d0bcf` (branch `native/phase-02-foundation`).

## What the phase must do

Read `docs/spring-native/plan.md` §4 (rules, especially 12 and 13), §7 (the quality bar) and §10 "Phase 2 — Foundation".
The phase's scope is `docs/spring-native/phase-02-foundation/scope.md`. Its **carry-over table (§6) is a requirement**:
every `CARRIED` row must still hold, proven by the named test; every `RETIRED` row must really be covered by Spring or
by the named replacement. Treat every claim in `scope.md` as a claim to check, not as a fact.

## How to read the change

- Deleted modules (`core/platform-core-api`, `errors/platform-errors-api`, `data/platform-data-api`):
  `git diff --name-only --diff-filter=D 516f54c..c7d0bcf`. Read what they did at `516f54c` where you need to compare.
- Everything else: `git diff --diff-filter=d 516f54c..c7d0bcf`. Skip generated and evidence files: `docs/reference/*`,
  `docs/llms*.txt`, `tooling/platform-skill/skill/**`, `docs/spring-native/phase-02-foundation/evidence/**`.
- The main code: `core/platform-core-autoconfigure` and `errors/platform-errors-autoconfigure` (main and test). Also the
  adapted modules (messaging api and outbox, the parked REST client, data JPA, logging tests, build-tools enforcer and
  ArchUnit rules), the examples, the archetype, the consumer probe and scenario
  (`tooling/hardening/scenarios/phase-01-core-errors.sh`, `phase-01/probe/HardeningProbe.java`), and the docs.
- You may read any file in the repository, and Spring's sources or jars in `~/.m2` to check how Spring behaves.

## The questions

1. **Is any guarantee lost?** For each `CARRIED` row in `scope.md` §6, does the new code still provide it on every path
   the old code did (REQUEST, ASYNC and ERROR dispatch; framework exceptions; the servlet error page; executors and
   caller-runs; concurrent requests)? Does the named test actually prove it?
2. **Is any `RETIRED` row not really covered** by Spring or by its stated replacement?
3. **What new risk is missing?** The design moves service code to Spring types: `ErrorResponseException` with a `code`
   property, `ErrorResponse.Interceptor` through `WebMvcConfigurer`, Micrometer `Tracer`, a platform
   `ContextPropagatingTaskDecorator` that clears missing values. Look for: ordering or back-off interactions with Boot's
   own beans and properties; bodies or exceptions shared between requests; anything a service can now do that leaks
   internals into a response or a log; anything that breaks when tracing, validation or Spring MVC is absent; anything
   in the adapted modules (messaging, outbox, REST client, data) that changed behaviour beyond compiling.
4. **Rule 12 and contract 12:** does any service-facing path still need a platform type? Does the consumer probe, the
   archetype or an example import one?
5. **Docs:** does a live page tell a reader to use a removed type or describe behaviour the code does not have?

## Output

For each finding: severity (Critical / High / Medium / Low, as defined in plan §6), file and line, a concrete failure
scenario, your reasoning, and a proposed fix. Say explicitly when you find nothing for a question. Do not summarise the
change back.
