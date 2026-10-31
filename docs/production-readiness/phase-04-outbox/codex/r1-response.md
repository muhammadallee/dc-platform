# Phase 4 — Round 1: implementer response to `r1-review.out.md`

Reviewed commit `de67f30`. Fixes are verified by the module tests and, where stated, on real Oracle or RabbitMQ.

| # | Finding | Severity | Response |
|---|---|---|---|
| F1 | `hasResource` does not prove transaction participation; a proxy causes a false refusal | High | **Agree. Fixed.** `canPublish` requires a bound `ConnectionHolder` whose connection is not auto-committing, and resolves `TransactionAwareDataSourceProxy` to its target. Tests: a connection primed under another database's transaction is refused; a proxy works |
| F2 | Publishing in an `afterCommit` callback can lose the event | High | **Agree. Fixed.** Spring has no flag for this phase, so the nearest stack frame decides: `invokeAfterCommit`/`invokeAfterCompletion` refuses, unless a newer `TransactionTemplate` or transactional interceptor frame is nearer (a new transaction in the callback is legitimate). Disabled mode and `TransactionalDispatcher` run immediately in that phase instead of registering a callback that never fires. Same technique as HI-0071; its limits are known and it fails closed |
| F3 | Copying a metered Hikari pool can prevent startup | High | **Agree. Fixed.** No more `copyStateTo`: an allow-list of connection settings. Test: a metered pool as source |
| F4 | A wrapper pool is not isolated | High | **Agree. Fixed.** Only a Hikari pool (copied) or a known-unpooled source (`EmbeddedDatabase`, `AbstractDriverBasedDataSource`) is accepted. Anything else fails startup, naming the `OutboxConnections` bean to define |
| F5 | Oracle bounds are inherited, not applied | High | **Agree. Fixed.** The drainer's pool adds them itself (`read-timeout`, 60 s; connect 10 s), its own 5 s acquisition and 3 s validation timeouts, and a query timeout on the probe. Test: bounds added, an application's value kept |
| F6 | Retention rolls back under `auto-commit=false` | High | **Agree. Fixed.** Auto-commit is not copied, and `deleteSent` sets it explicitly. Test on a no-auto-commit source |
| F7 | Runtime credential rotation | High → **Medium** | **Partly agree.** The platform has no rotation mechanism, and a restart or a custom `OutboxConnections` bean handles it. Documented in `outbox.md`; registered at Gate E |
| F8 | Mandatory checked on a probe only | High | **Agree. Fixed.** Checked on every real message |
| F9 | Connection failures classed as rejection | High | **Agree. Fixed; this one would have parked good events in repeated outages.** Only a returned message or a broker's 404/`NOT_FOUND` is a rejection. Tests for each |
| F10 | The confirm timeout does not bound `send()` | High | **Agree. Fixed.** The publish runs on a virtual thread, and the whole operation shares one deadline. Test: a blocked publish returns within the bound. A publish that gets through after the timeout duplicates the retry, which at-least-once allows; the thread ends when the broker unblocks |
| F11 | One unreadable row stops the table | High | **Agree. Fixed.** Parked at once; the batch continues. Test |
| F12 | Worker death and restart | High | **Agree. Fixed.** Start and stop are synchronized and idempotent, the worker checks its own identity, it survives `LinkageError` and `AssertionError` (test), and `platform.outbox.drainer.running` reports it. It does not catch `VirtualMachineError` |
| F13 | Default shutdown cannot finish a full batch | High | **Agree. Fixed differently:** a stop ends the batch after the send in flight and commits what was sent, so at most one send's time is needed, and the rest stay due. Idle waits end at once. Test |
| F14 | The relay can be absent | High | **Agree; a pre-existing bug. Fixed** with explicit ordering. Test with real auto-configuration |
| F15 | Trace and principal depend on order | Medium | **Agree. Fixed.** Resolved per call through `ObjectProvider` |
| F16 | Optional tracing classes in an unconditional bean | High | **Agree. Fixed.** `TracingOutboxTraceSource`, loaded only under the guarded configuration. Test with the classes filtered out |
| F17 | Restoration scope | Medium | **Partly agree.** Failure WARNs now log inside the restored scope. A Rabbit template observation is off by default and the stored `traceparent` is what is sent, so no tracing scope is opened; a principal context on the drainer thread is not needed by anything it runs. Gate F checks the wire header |
| F18 | Credential refusal gaps | High | **Agree. Fixed.** `pwd` added; only `traceparent`/`tracestate` are taken from the propagator; the merged headers are validated. Tests |
| F19 | Destinations in logs | Medium | **Agree. Fixed.** Log lines name the event id, type and exception class |
| F20 | Durations that remove a bound | Medium | **Agree. Fixed.** Validated in `OutboxSettings`. Test |
| F21 | Gauge freshness and maintenance | Medium | **Agree. Fixed.** Metrics and retention run on their own thread, fail independently, and `platform.outbox.gauges.age` reports staleness |
| F22 | Gauges retain the first drainer | Medium | **Agree. Fixed** for refresh: gauges are removed at stop and re-registered at start. Two drainers in one registry are not a supported shape |
| F23 | Parking announced before commit | Medium | **Agree. Fixed.** Published after the commit. Test |

## Acceptance criteria

- **AC9:** the plan says "max attempts"; the implementation parks on max **rejections**, deliberately, because attempts also count outages and would park good events during a long outage. Recorded as scope amendment A3.
- **AC4:** Codex suggests measuring lock footprint across several batch sizes with LOBs. Added: `OutboxOracleIT` checks claimers with LOB rows at more than one batch size.
- **AC1:** a deferred constraint failing at commit is added to `OutboxOracleIT`.
