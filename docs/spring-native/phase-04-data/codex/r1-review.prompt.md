# Codex review — Spring-native Phase 4 (data)

You are the independent reviewer for a Spring Boot 4.1.2 platform ("chassis") in this repository. Review read-only.

**Base commit:** `e9f9b80` (`develop`, start of Phase 4). **Reviewed commit:** `98191f2` (branch `native/phase-04-data`).

## What the phase must do

Read `docs/spring-native/plan.md` §4 (rules, especially 12 and 14), §7 (the quality bar, contracts 1–14) and the section
"Phase 4 — Data (Spring Data JPA)". The phase's scope is `docs/spring-native/phase-04-data/scope.md`. Its **carry-over
table (§6) is a requirement**: every `CARRIED` row must still hold, proven by the named test or consumer check; the
`RETIRED` row must really be retired. Treat every claim in `scope.md` as a claim to check, not as a fact.

The data capability was certified on Oracle in v3 (`docs/production-readiness/phase-03-data-access/`), and the phase
keeps its design. Its changes:
- **Logging (HI-0167, decision D142 in `docs/decisions/decision-log.md`):** `PlatformValueMasker` writes an `@Entity`
  instance or a Hibernate proxy as `[entity <Type>]` and an unloaded Hibernate collection as `[not loaded]`, recognised by
  class and interface name; a map key that is not a simple value is walked like a value. Evidence before and after:
  `docs/spring-native/phase-04-data/evidence/gateA-lazy-loading-spike.txt`, `gateB-hi0167-fixed.txt`.
- **Auditing (HI-0168):** the platform's `AuditorAware` backs off by type and `@EnableJpaAuditing` no longer names it.
- **Tests:** a zero-config acceptance test (contract 14) and a service-auditor slice test.
- **Consumer run:** `tooling/hardening/scenarios/phase-03-data-access.sh` and its probe gain AC17 and AC18, and the
  recovery poll follows actuator to its own port (HI-0169).

## How to read the change

- `git diff e9f9b80..98191f2`. Skip `docs/llms*.txt`, `docs/reference/*` and `docs/spring-native/phase-04-data/evidence/**`
  except the two spike files named above.
- The main code: `logging/platform-logging-autoconfigure` (`internal/PlatformValueMasker.java`,
  `internal/JpaValueMaskingTest.java`) and `data/platform-data-jpa-autoconfigure` (main and test).
- You may read any file in the repository, and the Spring Data, Hibernate and Boot jars in `~/.m2`, to check how they
  behave.
- **Docs are Gate H, not this round:** book chapter 8 and the data sample still describe v3 and will be rewritten. Only
  report a docs problem in a file this diff changes.

## The questions

1. **Is any guarantee lost?** For each `CARRIED` row, does the code still provide it, and does the named test or
   consumer check prove it?
2. **HI-0167.** Can logging a JPA value still run SQL or initialise anything through the platform's masker? Consider
   every path a value takes: a key-value pair, an MDC value, Boot's member processor, a message argument (the formatter's
   part; Logback's own rendering before the encoder is documented as HI-0157), nesting in maps, lists, arrays, records,
   beans and map keys; Hibernate's other collection and proxy types; bytecode-enhanced entities; entities mapped in XML
   only; Spring Data projections and other proxies. Does the change lose anything the masker used to mask?
3. **HI-0168.** With the auditor taken by type: two auditors, an auditor with a JPA dependency, a service that enables
   auditing itself, the kill switch, a non-`String` auditor.
4. **What new risk is missing?** Include the consumer-run changes and their assertions: can AC17 or AC18 pass while the
   guarantee fails?
5. **Rule 12 and contract 12:** does any service-facing path need a platform type?

## Output

For each finding: severity (Critical / High / Medium / Low, as defined in plan §6), file and line, a concrete failure
scenario, your reasoning, and a proposed fix. List **every** finding you can establish in this one answer. Say
explicitly when you find nothing for a question. Do not summarise the change back.
