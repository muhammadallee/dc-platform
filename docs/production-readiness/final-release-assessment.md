# Final release assessment

Hardening initiative, plan v3, on `hardening/v1`. Baseline `7b248e9`; final code `aa9c7f8` (2026-11-04), Spring Boot 4.1.2,
JDK 25. Phase 8 results: [`phase-08-final/run-results.md`](phase-08-final/run-results.md). Decision:
[`release-decision.md`](release-decision.md).

## Certified capabilities

Every capability was re-verified in Phase 8 on the final dependency set: its consumer run passed again on Boot 4.1.2 (D133).

| # | Capability | Verdict | Evidence level | Final consumer run |
|---|---|---|---|---|
| 1 | Core + Error Handling | PASS | SLICE + CONSUMER | 92/92 |
| 2 | Logging + Observability | PASS | SLICE + CONSUMER | 76/76 |
| 3 | Data Access (Oracle) | PASS; RAC `BLOCKED` | SLICE + CONTAINER + CONSUMER | 92/92 |
| 4 | Transactional Outbox | PASS with parked issues | SLICE + CONTAINER + CONSUMER | 106/106 |
| 5 | RabbitMQ Messaging | PASS | UNIT + SLICE + CONTAINER + CONSUMER | 63/63 |
| 6 | REST Client + Resilience | PASS | SLICE + CONSUMER | 182/182 |
| 7 | Security | PASS | SLICE + CONSUMER (scripted provider and real Keycloak) | 170/170 |

Together: one generated app with all seven, against Oracle, RabbitMQ and Toxiproxy, ran the C1 and C2 scenarios and the
request → outbox → broker → handler → outbound chain on one trace: **63/63**. Golden path **9/9**. Full reactor: 1,557 tests,
none skipped. In-scope container suites: 13 classes. A service built on the platform has **no known vulnerability** (Trivy
0.68.2, 2026-11-04).

## Not certified

Ships in the BOM, outside this initiative: Kafka messaging; audit (all sinks; **services with regulated audit requirements must
not rely on it for compliance**); idempotency (**no server-side protection against duplicate execution of non-idempotent
requests**); cache (Caffeine and Redis); Redis; locking; rate limiting; multi-tenancy data isolation; storage; files; feature
flags; secrets; OpenAPI; scheduling; validation; native image; schema-per-tenant. PostgreSQL is out for now (HD3); its parity
test passes, and it is not certified.

## Exceptions

None approved under plan §5.2 ([`exceptions.md`](exceptions.md)). Two steps are `BLOCKED`, not skipped:

| Item | Why | Consequence |
|---|---|---|
| Oracle RAC failover (HI-0057, HD4) | No non-production RAC exists | FCF/ONS, Application Continuity and pool behaviour on failover are unverified |
| OpenShift, Phase 8 step 7 (D134) | The chart tool's location, invocation and a cluster were not supplied | Readiness under degradation, termination within the grace period, draining, limits and restarts are unverified in the target platform. Each capability's own shutdown and readiness were verified in containers |

## Open issues

**Parked by the owner, to fix before production use:**

| ID | Severity | What |
|---|---|---|
| HI-0071 | Medium | The commit-phase veto (a connection lost while committing must not become a retryable 503) is recognised by a stack frame; reactive managers and a rethrown exception are not covered |
| HI-0079 | Medium | `auto-commit=false`, a second transaction manager and a read through the outbox's `DataSource` in that manager's transaction: the outbox insert joins no owning transaction |
| HI-0086 | Medium | Inside an `afterCommit` callback, two non-guaranteed paths misbehave (Spring documents this as misuse); the outbox itself refuses loudly |

**Release conditions (D134):**

| ID | Severity | What | When |
|---|---|---|---|
| HI-0108 | Medium | The release pipeline attaches no SBOM and produces no provenance; `release.sh` hard-codes `-T1C` | Before the first real release |
| HI-0106 | Medium | 14 High known vulnerabilities in build and tooling modules (the Maven plugin, the MCP server) and Kafka's compression libraries; none on a service's classpath | Tracked |
| HI-0107 | Low | A service's jar is not reproducible (no `outputTimestamp` in the service parent); two property references in `platform-dependencies` | Tracked |
| HI-0081 | Low | The japicmp gate compares against `RELEASE`, which does not exist until the first release; Phase 8 ran it by hand | Works from the first release on |
| HI-0004 | Low | The book's narrative still describes the old `X-Correlation-Id` model in places | Docs |

**Accepted by the owner:** HI-0078, HI-0087, HI-0088 (confirmed at the end, D134), HI-0093, HI-0094 to HI-0098, HI-0100 to
HI-0103. Each is in the [issue register](issue-register.md) with its mitigation.

## Known limitations

- **Not exercised:** TLS anywhere (the JWKS URL is checked by scheme), HTTP/2, load and soak, Keycloak HA or restarts, more than
  one RabbitMQ node (quorum failover unverified), the `@Retry` aspect, spans (no exporter in the runs).
- **A heuristic, not a proof:** the open-path startup check cannot see a rule that opens a large named area such as `/api/**`.
- **Consumers must take a supported route** to get the patched versions: `platform-service-parent` (the archetype's), or
  `platform-bom` imported with no Spring Boot parent or BOM ahead of it ([compatibility appendix](../book/appendix/d-compatibility.md)).
- **Zipkin is not supported**; traces go over OTLP. The service parent refuses the Zipkin exporter.
- Every breaking change since the baseline is in the CHANGELOG; there were no users (HC7). The one binary break in an API module
  is `EventEnvelope`'s constructor (Phase 5).

## Recommended first-adopter conditions

1. Use `platform-service-parent`, as the archetype does.
2. Run on a single-instance Oracle (not RAC) until RAC is verified, or verify RAC first.
3. Do not depend on the parked outbox paths: one transaction manager, auto-commit on the pool, no outbox or dispatcher work
   inside `afterCommit` callbacks (HI-0079, HI-0086). Treat a 503 after a failed commit with care (HI-0071).
4. Before production on OpenShift: run Phase 8 step 7 with the team's chart tool.
5. Configure the identity provider's issuer, JWKS URL and audience; the service will not start without them.
6. Before cutting the release: HI-0108.
