# Phase 6 — REST Client + Resilience: closure

**Verdict: PASS, with residuals awaiting the owner's acceptance** (below). Earlier owner decisions: HD2 (POST and PATCH never retried), HC7 (no users, so breaking changes need only a CHANGELOG entry).

| Item | Value |
|---|---|
| Phase start / final code commit | `1162d99` / `78636c0` (consumer 3×, Phases 1 and 2 regressions, golden path and downstream compile on `78636c0`) |
| Modules changed | core-api, errors-autoconfigure, observability-autoconfigure, restclient-api, -autoconfigure, starter-restclient, resilience-api, -autoconfigure, archetype, hardening harness, docs |

## Gates

| Gate | Result | Evidence |
|---|---|---|
| A scope + baseline | a 32 MB error body allocated 96 MB; the JDK sent a GET twice; a timeout was an unmapped 500 | [`scope.md`](scope.md) |
| B implement + targeted tests | D126–D130 | `evidence-raw/` |
| C round 1 | REJECT, 21 findings: 19 fixed, 2 with residuals | [`review-round-1.md`](review-round-1.md) |
| D round 2 and verification | REJECT, 9 defects and 9 test gaps; pass 1 REJECT, 10; pass 2 REJECT, 6. **The fixes for pass 2 were not re-reviewed** | [`review-round-2.md`](review-round-2.md) |
| E register | G6-01 to G6-06 closed; HI-0094 to HI-0099 | [`../issue-register.md`](../issue-register.md) |
| F consumer | three closing runs **182/182** each on `78636c0` | [`consumer-run.md`](consumer-run.md), [`evidence/gate-f-closing-1..3`](evidence/) |
| F regressions (§3.10) | Phase 1 **92/92**, Phase 2 **75/75**, since core, errors and observability changed | [`evidence/`](evidence/) |
| Flakiness (§3.11) | the timing tests, 3 consecutive runs, no reruns | `evidence-raw/flaky-1..3.log` |
| Targeted tests | restclient-autoconfigure 102, restclient-api 42, resilience-autoconfigure 22, errors 78, observability 37, core 58; 0 failures, coverage gates met | `evidence-raw/final-verify.log` |
| Downstream (§3.9) | `-amd test-compile`: 93 modules, BUILD SUCCESS, sha256 `55c4c251c990cc5e…` | `evidence-raw/downstream-final.log` |
| Golden path | **9/9** on `78636c0` | [`evidence/golden-path-summary-78636c0.tsv`](evidence/golden-path-summary-78636c0.tsv) |
| H samples + operations | written | [`../../samples/rest-resilience/`](../../samples/rest-resilience/) |
| CI (PR #2) | to be confirmed on the closure commit before tagging | GitHub Actions |

Phases 3, 4 and 5 consumers were not rerun: nothing they certified changed except `PlatformException` gaining a method with the old behaviour, and they compile. Phase 8 reruns everything.

## §7 contracts

| § | Result |
|---|---|
| 7.1 override | `PlatformRestClientFactory` backs off, tested |
| 7.2 default snapshot | `theDefaultsAreWhatTheDocumentationSays` |
| 7.3 kill switch | `DEFAULT_ON`; capability and per-feature switches tested; the deadline has none |
| 7.4 outage posture | slow, silent, refused, unknown host, failing; the response recorded |
| 7.5 health | liveness and readiness unaffected; `dependencies` shows breakers |
| 7.6 propagation | one trace across attempts; concurrent calls separate |
| 7.7 error surface | 503, 504 or 500 by the rule in R2; exact generic detail |
| 7.8 redaction | snippet best effort; no query, fragment or URL credentials in tags; no transport message in exceptions. HI-0095, HI-0099 |
| 7.9 observability | one observation per call; `platform.restclient.attempts`; low-cardinality tags |
| 7.10 graceful shutdown | a call in a backoff and one in flight answered, exit 143; transport termination not asserted |
| 7.11 startup | no connection at startup, by construction; times recorded both ways |

## Consumer-visible changes

See CHANGELOG `## [Unreleased]`, phase 6.

## Owner acceptance (PENDING)

1. **HI-0099:** a JSON library's message for a malformed **success** body can quote a bad token into the error log. **Codex rates this High, Claude Medium**: the owner decides.
2. **HI-0096:** `net.properties` is checked at context start, so a live edit without a JVM restart is missed. **Codex rated this High, Claude Low**.
3. **HI-0094:** request bodies are held in memory, and the deadline starts after a caller's interceptors.
4. **HI-0095:** an encoded or oddly named secret, or personal data, can remain in a snippet.
5. **HI-0097:** two user composite registries sharing an exporter double-count attempts; closed registries are retained.
6. **HI-0098:** a reset within 2 ms of the deadline is reported as the deadline.

## Open (non-blocking)

- Not exercised: TLS, HTTP/2, load, the `@Retry` aspect. Inbound deadline propagation deferred.
- Parked by the owner: HI-0071, HI-0079, HI-0086. To review at the end of all phases: HI-0088 (D125).
