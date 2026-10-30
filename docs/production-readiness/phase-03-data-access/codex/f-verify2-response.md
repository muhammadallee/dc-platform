# Phase 3 Gate F — implementer response to `f-verify2.out.md`

Verified commit `2bcdded` (HI-0070 configurable read timeouts, the HI-0068 commit veto, the silent-peer
measurement).

1. **Binding — accept.** No change. Bindings run once per pool or `Flyway` bean during startup; the environment
   is not mutated between them in a normal start.
2. **Connection coverage — accept with change.** Agreed: `OracleFlywayConnectionDefaults` promised "before
   anything connects", but Boot's `{vendor}` detection connection comes first. The javadoc now says it applies
   before Flyway connects and names the exception. Migrations through the pool keep the pool's value, as
   `data.md` says.
3. **Commit veto bypassed — agreed (reject accepted).** Spring 7.0.8's `HibernateJpaDialect` turns Hibernate's
   "Unable to commit against JDBC Connection" over a `SQLRecoverableException` into a `RecoverableDataAccessException`
   whose cause is the SQL exception, so the Hibernate message never reaches the classifier and the result was a
   retryable 503. `DataAccessFailure` now also vetoes an `UNAVAILABLE` result when the exception was raised inside
   Spring's `AbstractPlatformTransactionManager.processCommit` (a stack frame anywhere in the chain;
   `JpaTransactionManager` translates inside `doCommit`). Two outcomes stay: a conflict found by the commit-time
   flush is still a 409 (only `UNAVAILABLE` is vetoed), and Hibernate's "Transaction timeout expired" is still a
   503 (the deadline passed before anything was committed; pinned on Oracle in round 1).
   Tests go through the real translation and a real transaction manager (`AbstractPlatformTransactionManager`
   subclass whose `doCommit`/`doRollback` raise the exception, built inside the call as `JpaTransactionManager`
   does): `aCommitThatLosesItsConnectionThroughSpringsTranslationIsNotRetryable` and
   `whatTheCommitPhaseMayStillClassify` (deadline 503, flush conflict 409, the same lost connection on rollback
   503). The first failed without the change (`evidence-raw/f-verify2-red.log`); errors module green with the
   coverage gate, 77 tests (`evidence-raw/f-verify2-green.log`).
4. **Docs — accept with change.** Agreed on all three:
   - the read timeout bounds each wait for the database's answer, not a statement's elapsed time
     (`data.md`, CHANGELOG, `DataJpaProperties` javadoc and metadata, D110 addendum);
   - the CHANGELOG no longer says a separate Flyway connection gets 60 s, and `data.md` no longer says "the same
     timeouts";
   - the `{vendor}` workaround now shows `-Doracle.jdbc.ReadTimeout=600000` and says a JVM property wins over both
     platform values, so the pool gets it too.
   The 608–610 s figure is stated as a measurement of a silent peer with a separate Flyway user, not as a
   general startup deadline.
