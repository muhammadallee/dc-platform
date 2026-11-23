# Codex review, round 2 — Spring-native Phase 4 (data)

You are the independent reviewer for a Spring Boot 4.1.2 platform ("chassis") in this repository. Review read-only.

**Base commit:** `e9f9b80` (`develop`, start of Phase 4). **Reviewed commit:** `1d9e66e` (branch `native/phase-04-data`).

Round 1 is closed (`docs/spring-native/phase-04-data/review-round-1.md`). This round reviews the **whole phase** as it
stands, documentation included, against `docs/spring-native/phase-04-data/scope.md` and plan §7 and §10 ("Phase 4 —
Data (Spring Data JPA)"). Treat every claim, in the scope and in the docs alike, as a claim to check.

## How to read the change

- `git diff e9f9b80..1d9e66e`. Skip `docs/llms*.txt`, `docs/reference/*`, `docs/spring-native/phase-04-data/evidence/**`
  and `docs/spring-native/phase-04-data/codex/**`.
- **Code:** `logging/platform-logging-autoconfigure` (`internal/PlatformValueMasker.java` and its tests) and
  `data/platform-data-jpa-autoconfigure` (main and test).
- **Consumer run:** `tooling/hardening/scenarios/phase-03-data-access.sh` and `phase-03/probe/`.
- **Live documentation this phase wrote or changed:**
  - `docs/book/chapters/08-data.md` (rewritten), `docs/labs/data/index.md` (new), `docs/samples/data-access/*`;
  - `docs/modules/logging.md`, `docs/modules/data.md`, `docs/decisions/decision-log.md` (D142), `CHANGELOG.md`.
  - `docs/samples/data-access/verification.md` is rewritten at closure with the final consumer runs; do not review it.
- The lab is run by `tooling/labs/run-lab.sh data`: every `lab-step` block in order, every `lab-check` must exit 0.
- You may read any file in the repository, and the Spring, Hibernate and Boot jars in `~/.m2`, to check how they behave.

## The questions

1. **Do the round 1 fixes hold?** Any remaining way the masker runs application code it should not, loads a JPA value,
   or masks less than before the phase; any regression the fixes introduced.
2. **Are the `CARRIED` guarantees provided and proven?** For every row of scope §6, the named test or consumer check.
3. **Does the live documentation match the implementation?** Every default, code, status, property, bean name, command,
   port and behaviour the chapter, the sample and the lab state. Would every lab step and check pass as written, and
   would a check pass for the wrong reason?
4. **Rule 12 and contract 12:** does any service-facing path, the probe, the lab or a doc example need a platform type?

## Output

For each finding: severity (Critical / High / Medium / Low, as defined in plan §6), file and line, a concrete failure
scenario, your reasoning, and a proposed fix. List **every** finding you can establish in this one answer. Say
explicitly when you find nothing for a question. Do not summarise the change back.
