# Phase 0 — Codex review

One round over the spikes, the removal map and plan v2 (plan §9). Base `6666618`, reviewed `67521ae`.
- Prompt: [`codex/r1-review.prompt.md`](codex/r1-review.prompt.md)
- Codex's answer, verbatim: [`codex/r1-review.out.md`](codex/r1-review.out.md)
- Verification pass on the fixes: [`codex/r1-verify.out.md`](codex/r1-verify.out.md): 9 accepted, 1 rejected (a leftover v1
  phase reference), since fixed

Codex: "I would not close Phase 0 yet. There are four High findings below, plus replacement and evidence gaps."
Every finding is in the plan or the spike record. No production code exists yet in this phase, so every fix is a plan
change. The phases named below carry each one out.

| # | Severity | Finding | Claude | Fix |
|---|---|---|---|---|
| 1 | High | Removing the platform validation starter also removes the validator: constraints silently stop being checked | Agree. `errors-autoconfigure` has `jakarta.validation-api` optional and Boot's validation starter only in test scope | Plan §2.5 and Phase 1 step 4: `spring-boot-starter-validation` replaces the platform starter in the archetype and kept examples |
| 2 | High | The "no `BeanPostProcessor`" rule conflicts with `OracleConnectionDefaults`, the proven fix for HI-0061/0063/0070 | Agree | Rule 12: a recorded exception for that one processor (adds driver timeouts only where unset, never overwrites). Phase 4 proves it for Boot-created, user-created and late-URL pools |
| 3 | High | A client interceptor cannot stop an outer `@Retryable` from repeating a POST | Agree. The v3 defaults exclude `RemoteCallException` from method retries for this reason | Phase 9: the same exclusion for Spring's exception types on every retry mechanism, plus an exactly-one-call test with a lost response. `spikes.md` S4 corrected |
| 4 | High | Deleting `docs/samples/security` breaks links in the closed hardening record, which Phase 1 forbade editing | Agree | Phase 1 step 7: links to deleted files may be rewritten to the same file at tag `pre-spring-native`; the surrounding text stays word for word |
| 5 | Medium | `RequestContextHolder` does not replace `RequestContext` (HI-0016, HI-0023) | Agree | Removal map corrected; Phase 2 moves the isolation into an internal accessor and ports its tests first |
| 6 | Medium | A second `@RestControllerAdvice` does not replace `ProblemDetailCustomizer` (HI-0014) | Agree | Removal map: no proven replacement. Phase 2 Gate A names a composing mechanism, or keeps a narrow hook with an ND decision |
| 7 | Medium | Boot's `RestClientCustomizer` has no client name | Agree | Phase 9: per-client settings through HTTP service groups; two-downstream isolation test |
| 8 | Medium | S3 passes even if the read timeout is ignored | Agree | `spikes.md` S3 narrowed; Phase 9 Gate A adds a slow-server test |
| 9 | Medium | The dependency inventory describes the v1 cut | Agree | `inventory.md` marked superseded and pointed at Codex's v2 reference list; Phase 1 Gate A regenerates it |
| 10 | Low | Facts from v1: `security/*` has 7 modules; `example-event-driven` uses messaging, not Kafka/events; HI-0099 is a REST-client leak, not an open-path check; old phase numbers in `spikes.md` | Agree | All corrected. HI-0099 now carried in Phase 9. The verification pass rejected one leftover (a v1 phase reference in S6); fixed after it |

**Also taken from Codex's answers to the four questions:**
- **Phase 6:** plain `convertAndSend` does not wait for a publisher confirm, so the bounded confirm-and-return wait in
  `RabbitEventTransport` must be kept.
- **Phase 2:** the parked REST client is adapted when `PlatformException` goes.
- **Phase 1 input:** Codex's file-and-line list of every kept module's reference to a cut module (question 2) is the
  starting point for Phase 1 Gate A.

**What Codex found nothing on:**
- **Question 3:** no `FIXED` or `ACCEPTED` guarantee is left wholly without a home.
- **Question 4:** no impossible consumer-count floor.
