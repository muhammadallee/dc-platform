# Codex verification — Spring-native Phase 1, review round 1

You reviewed commit `451420b` and found two High and four Medium issues. Your answer is in
`docs/spring-native/phase-01-scope-cut/codex/r1-review.out.md`; Claude's replies are in
`docs/spring-native/phase-01-scope-cut/review-round-1.md`. The fixes are in `451420b..61b23b1` on branch
`native/phase-01-scope-cut`. Review read-only. Read `git diff 451420b..61b23b1` (skip `docs/llms-full.txt`).

For each of your six findings, say **ACCEPTED** (the fix resolves it), **NOT RESOLVED** (say what is still wrong, file and
line) or, for finding 5, whether you accept the rejection:

1. `PlatformUsageRules` no longer bans Kafka types.
2. `tooling/hardening/native-repo.sh` replaces the per-phase repository scripts. Check it cannot leave a cut artifact in
   the repository it builds, that it refuses the two read-only baselines, and that every live command page and scenario
   usage line points to it.
3. The platform index lists only starters in the BOM.
4. Live docs: instructions fixed in place, other pages bannered. Is any live page still giving an instruction for a cut
   capability **without** a banner? (`docs/production-readiness/**`, `docs/spring-native/**`, `docs/decisions/**` and
   `docs/reference/*` are out of scope.)
5. Rejected: plan §8 Gate E names `production-readiness/issue-register.md` as the continuing register for Spring-native
   findings ("numbering continues from HI-0110"); plan §10 Phase 1 step 7 names the frozen record as the final assessment,
   the release decision and the phase folders. Do you accept?
6. `example-event-driven` runs on RabbitMQ only.

Then: did any fix introduce a new defect? Severity, file and line, failure scenario, proposed fix. Say "none" if none.
