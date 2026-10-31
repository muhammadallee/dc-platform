# Phase 3 Gate F — implementer response to `f-verify3.out.md` (commit veto re-check)

Codex: **reject** ("Use explicit transaction-phase/outcome evidence preserved across translation; stack presence
cannot establish it."). Recorded as an unresolved dispute and registered as **HI-0071** (Medium, OPEN, owner
decision at Gate H).

| Codex point | Claude |
|---|---|
| A reactive commit (`R2dbcTransactionManager`) that loses its connection still becomes a 503 | Agree it is not covered. The platform ships no reactive data support (JPA/JDBC with `JpaTransactionManager` only); `data.md` says the veto covers Spring's imperative transaction managers. |
| An exception built before `processCommit` and rethrown inside it keeps its old stack, so it is not vetoed | Agree in principle. `JpaTransactionManager.doCommit` translates the Hibernate exception inside the call, so the observed and realistic path is covered (tested through the real translator inside a real `doCommit`). |
| A `beforeCommit` synchronization that throws a `QueryTimeoutException` (nothing committed yet) now gets a 500 instead of a 503 | Agree. That errs on the safe side (not retryable) and needs application code in a `beforeCommit` synchronization; JPA's commit-time flush runs inside `doCommit`. |
| Use explicit phase/outcome evidence | Agree it would be more exact. Spring's own commit path treats a runtime failure in `doCommit` as "rolled back" (`doRollbackOnCommitException`), so explicit evidence would need hooks in the transaction manager itself (a wrapping manager or a `TransactionExecutionListener` with its own outcome rules). Not done in Phase 3. |

Disposition: the veto stays, as the conservative choice for the supported path: a connection lost while
Spring's `JpaTransactionManager` commits is a non-retryable 500. The residual (reactive managers, pre-built
exceptions, a vetoed 503 from a `beforeCommit` synchronization) is HI-0071, with the explicit-evidence
design as the target if the owner wants it.
