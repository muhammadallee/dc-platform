# Phase 4 verification pass 2: HI-0071, HI-0079, HI-0086 fixes

You are reviewing an internal Spring Boot 4.1 / Spring 7 platform. Static review only: no JVM or Docker in your sandbox.
Review the HEAD commit (`git show HEAD`) against the code around it. The owner decided to fix these rather than accept them.

Claims to attack:

1. **HI-0071.** `CommitEvidenceJpaTransactionManager` overrides `doCommit` and adds `CommitOutcomeUnknown` as a suppressed
   exception to what it throws. `DataAccessFailure.raisedDuringCommit` looks for it in the cause chain, with the stack-frame
   check as a fallback for a service's own manager. `PlatformJpaTransactionManagerAutoConfiguration` replaces Boot's bean.
   - Can the marked exception be replaced before classification, so that the evidence is lost and a 503 returns? Consider
     `AbstractPlatformTransactionManager.processCommit`, `rollbackOnCommitFailure`, `TransactionInterceptor`, exception
     translation, and an exception that has suppression disabled.
   - Does the bean differ from Boot's in any way that matters (name, customizers, ordering, conditions, `@DataJpaTest`,
     several `EntityManagerFactory` beans, Hibernate excluded by `spring.autoconfigure.exclude`)?
2. **HI-0086.** `TransactionalDispatcher` and `AfterCommitOutboxPublisher` classify the stack. Directly in `afterCommit`: run
   now. In `TransactionAspectSupport.invokeWithinTransaction` proven new by `currentTransactionStatus().isNewTransaction()`:
   register. Otherwise under a callback: throw.
   - Is `currentTransactionStatus()` always the status of the nearest interceptor frame? Can it throw here?
   - Does the phase check run too late or too early anywhere? Does any platform path now throw in a normal flow (the domain
     event relay, handlers on `AFTER_COMMIT`, the outbox's own use)?
   - A `REQUIRED` method nested in a proven `REQUIRES_NEW` method in a callback is refused. Is that acceptable?
3. **HI-0079.** `outboxSingleTransactionManagerCheck` fails startup with more than one `PlatformTransactionManager`, unless the
   opt-out is set. Is the check bypassable in a way that matters, or can it refuse a legitimate single-manager service?

Accepted already, do not re-litigate: HI-0078; reactive managers are uncovered; a `beforeCommit` 503 under a service's own
manager is over-vetoed (safe side); a `TransactionTemplate` `REQUIRES_NEW` in a callback is refused (loud, safe side).

Report concrete defects only, each with file, line, the inputs that trigger it, and severity. End with a line
`VERDICT: ACCEPT` or `VERDICT: REJECT`. Say so plainly if you find nothing.
