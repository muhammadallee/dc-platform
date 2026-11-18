# Codex review — Spring-native realignment, Phase 0 closure

You are the independent reviewer for a Spring Boot 4.1.2 platform ("chassis") in this repository. Review read-only.

**Base commit:** `6666618` (start of Phase 0 on `develop`). **Reviewed commit:** `67521ae`.
Run `git diff 6666618..67521ae --stat` to see the change set; it is documentation, spike tests and harness scaffolding only.

## What the phase is supposed to deliver (requirements)

Read `docs/spring-native/plan.md` (plan v2), especially §2 (scope bands with module lists), §3 (phases), §9 (Phase 0) and
§10 Phase 1. Then read:
- `docs/spring-native/decisions.md` (the owner's answers);
- `docs/spring-native/phase-00-baseline/spikes.md` and the spike tests under `docs/spring-native/phase-00-baseline/spikes/`;
- `docs/spring-native/phase-00-baseline/removal-map.md`, `docs/spring-native/expected-removals.md`;
- `docs/spring-native/phase-00-baseline/inventory.md`.

You may read any file in the repository, including module POMs and sources, to check claims.

## Questions

1. **Are the replacements real?** For each row of the removal map, does the named Spring type actually provide what the
   platform type provided, on Spring Boot 4.1.2 / Spring Framework 7 / Spring Security 7? Do the spike tests prove what
   `spikes.md` says they prove (look for tests that pass for the wrong reason)?
2. **Does anything kept depend on something cut?** Plan §2.5 lists the module directories Phase 1 deletes and §2.4 the
   parked ones. Check the POMs and Java sources of every kept module (core, errors, logging, observability, data,
   messaging rabbit and outbox, test support, build, tooling, archetype, examples, docs/platform-docs) for compile, runtime,
   optional, test-scope or auto-configuration-ordering references to cut modules or their packages. List each with file
   and line.
3. **Is any guarantee left without a home?** The previous initiative's issue register is
   `docs/production-readiness/issue-register.md` and its contracts are in `docs/production-readiness/plan-v3.md` §7. Is there
   a `FIXED` or `ACCEPTED` behaviour in a kept capability that plan v2 neither carries into a phase nor explicitly drops?
4. **Is the plan executable as written?** Contradictions between sections, phases that depend on something not yet built,
   gates that cannot pass (for example a consumer-count floor that the scope cut makes impossible), wrong module names.

## Output

For each finding: severity (Critical / High / Medium / Low, where Critical and High mean the plan would lose a proven
guarantee, break the build, or ship something unsafe), the file and line, a concrete failure scenario, your reasoning, and
a proposed fix. If you find nothing for a question, say so explicitly. Do not summarise the documents back.
