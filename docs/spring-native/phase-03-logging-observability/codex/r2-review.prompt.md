# Codex review, round 2 — Spring-native Phase 3 (logging + observability)

You are the independent reviewer for a Spring Boot 4.1.2 platform in this repository. Review read-only.

**Base commit:** `a0f58d5` (start of Phase 3). **Reviewed commit:** `0f17c18`. Round 1 is recorded in
`docs/spring-native/phase-03-logging-observability/review-round-1.md`, with every prompt and answer in `codex/`.

## What to check

- **Scope:** `docs/spring-native/phase-03-logging-observability/scope.md`. Its carry-over table (§6) is the requirement,
  and decisions 1, 2 and 4 in §2 are the contract as amended in round 1.
- **Accepted limits:** do not re-raise these unless the code fails to meet them.
  - **Customizer output:** whatever a service customizer's own processors produce is the service's responsibility.
  - **ECS:** under an ECS format, a raw value under one of ECS's five object names is not masked.
  - **Per-value budget:** each argument and key-value pair is bounded by 10,000 values.
  - **Same JVM:** across several starts in one JVM, only a format property holding the platform's own formatter is
    cleared.
  - **stderr:** SLF4J reports a throwing argument's `toString()` itself.
  - **Own Logback file:** a service's own Logback file bypasses masking, with a WARN.
- **The change:** `git diff a0f58d5..0f17c18`. Skip `docs/reference/*`, `docs/llms*.txt` and the evidence folders. The
  main code is in `logging/platform-logging-autoconfigure` and `observability/platform-observability-autoconfigure`.
  Also review:
  - the harness, the ported run and the probe: `tooling/hardening/lib/harness.sh`,
    `scenarios/phase-02-logging-observability.sh` and `phase-02/probe`;
  - the golden-path and smoke scripts, and `@PlatformWebTest`;
  - the docs: the logging and observability module pages, book chapter 3, the sample, and the new lab
    `docs/labs/logging-observability/index.md`.

## The questions

1. Do round 1's fixes hold at `0f17c18`: no regression, and no fix that only moved a problem elsewhere?
2. Is any `CARRIED` row of §6 not provided on every path, or not proven by its named test?
3. Does a live page, the lab or the sample tell a reader something the code does not do?
4. Does any service-facing path, the probe, the archetype or the lab need a platform type?

## Output

List **every** finding in this one answer: severity (Critical / High / Medium / Low), file and line, a concrete
failure scenario, and a fix. Say "none" for a question where you find nothing.
