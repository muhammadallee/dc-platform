# Codex review — Spring-native Phase 1 (scope cut)

You are the independent reviewer for a Spring Boot 4.1.2 platform ("chassis") in this repository. Review read-only.

**Base commit:** `085d752` (`develop`, start of Phase 1). **Reviewed commit:** `451420b` (branch `native/phase-01-scope-cut`).

## What the phase must do

Read `docs/spring-native/plan.md` §2 (scope bands: kept, parked, cut, with module lists) and §10 "Phase 1 — Scope cut"
(steps 1–11). The phase's own record is `docs/spring-native/phase-01-scope-cut/scope.md`; treat its claims as claims to
check, not as facts.

## How to read the change

- Deleted files (537, the cut modules and their docs): `git diff --name-only --diff-filter=D 085d752..451420b`. You do not
  need to read their content.
- Everything else: `git diff --diff-filter=d 085d752..451420b`. Generated files you can skip: `docs/reference/*`,
  `docs/llms*.txt`, `tooling/platform-skill/skill/**`, `docs/spring-native/phase-01-scope-cut/evidence/**`.
- You may read any file in the repository.

## The question

Does anything that is kept or parked still depend on, document, test, configure or ship anything that was removed? Look
in particular at:

1. **Code and build:** POMs (including optional, test and provided scope), `META-INF/spring/*.imports`, auto-configuration
   ordering by class name, `@ConditionalOnClass` by name, the BOM and `platform-dependencies`, the archetype templates,
   the examples, the golden-path and smoke scripts, the OpenRewrite recipe, the MCP server and skill resources, the CI
   workflows and release scripts.
2. **Behaviour that changed in kept modules** (the JPA auditor, the outbox principal, the parked REST client's token
   relay, messaging without Kafka, `Containers`, the archetype's actuator exposure): is each change safe, complete and
   tested? Did removing security weaken a guarantee a kept capability still needs (for example, an endpoint that was only
   safe because it required a token)?
3. **Parking:** can a parked module still leak into a release (BOM, deploy, archetype, docs presented as shipped)?
   Does `check-bom` now behave correctly for every module?
4. **Docs:** a live page that still tells a reader to use something removed, or a broken link. The closed hardening
   record (`docs/production-readiness/**`) may only gain a "superseded" banner and links rewritten to tag
   `pre-spring-native`; check nothing else changed there.
5. **Consumer runs:** the drop ledger in `scope.md` claims which v3 assertions were dropped or changed and why. Check
   the scenario diffs under `tooling/hardening/scenarios/` against that ledger: is any assertion dropped that is not in
   the ledger, or weakened without being listed?

## Output

For each finding: severity (Critical / High / Medium / Low; Critical and High mean a removed thing still ships or is
needed, a guarantee was lost, or the build or a release would break), file and line, a concrete failure scenario, your
reasoning, and a proposed fix. Say explicitly when you find nothing for a question. Do not summarise the change back.
