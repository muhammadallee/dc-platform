# Phase 3 Gate F — implementer response to `f-verify.out.md`

Verified commit `764dc14` (fixes `06d835a` HI-0068, `27e8303` HI-0069). Both verdicts were
"accept with change"; both changes are made.

## HI-0068 — narrowed, and a commit veto added

- **Rollback message regardless of cause:** agreed. Hibernate 7.4's `AbstractLogicalConnectionImplementor` uses
  "Unable to rollback against JDBC Connection" for any `SQLException` from rollback. The rule now applies only
  when the direct cause is Hikari's closed-connection exception (`SQLException("Connection is closed")`, no
  SQLState: `ProxyConnection$ClosedConnection` in HikariCP 7.0.2, checked by `javap`), which is the observed
  case. Any other cause classifies as usual: an 08006 cause is still a 503 by SQLState, and an unrelated
  permanent error is unclassified (500).
- **Commit exclusion incomplete:** agreed, and pre-existing. The walk continued below "Unable to commit
  against JDBC Connection", so an 08006 or `SQLRecoverableException` cause made a failed commit a retryable
  503. The classifier now stops there and returns no classification (generic, non-retryable 500): the
  outcome is unknown. "Nothing was committed" now describes only the observed rollback case.
- **Tests:** `DataAccessFailureTest.aRollbackThatFailsOnABrokenConnectionIsUnavailable` adds a rollback over an
  08006 cause (503) and over an unrelated `HY000` error (unclassified);
  `aFailedCommitIsNeverRetryableWhateverItsCause` covers a closed connection, a `SQLRecoverableException`
  (08006) and a plain 08006 cause. Both failed before the change (`evidence-raw/f-verify-red.log`); the errors
  module is green with the coverage gate (75 tests, `evidence-raw/f-verify-green.log`).

## HI-0069 — default reverted; scope amendment A2

Agreed: an `error` default for `org.hibernate.orm.jdbc.error` silences the only record of a failure that
application code catches and swallows, and of batch or async paths with no handler. Losing that is worse than
two extra WARN lines on a handled request. The default and `JdbcErrorLoggingSliceTest` are removed (data-jpa
is back to its pre-`27e8303` code, 56 unit/slice tests green, `evidence-raw/f-verify-data.log`).

Instead:
- scope amendment **A2**: "logged once on the request's trace" means the platform's own WARN. Hibernate's
  two statement lines precede it and stay on by default. This is proposed for owner acceptance at Gate H;
- `data.md` and the CHANGELOG say so and name the logger to set to `error` for services that want only the
  platform's line;
- the consumer check is now exact: one platform WARN on each violation's trace carrying the code and the ORA-
  detail, and no WARN from any logger other than the handler and `org.hibernate.orm.jdbc.error`.

The logger name and the EPP-before-logging order were confirmed by the review; with the default gone they
are no longer load-bearing.
