# Phase 7 — review round 2

Codex reviewed `c7373c6..fa863c5`: **REJECT, 12 findings and 4 test gaps** ([output](codex/r2-review.out.md)). It confirmed 13 of the round 1 fixes and called 3 incomplete. The run was delayed two days by Codex's usage limit: the first attempt, on `5f0eea3`, stopped with no output.

| # | Codex | Finding | Claude | Outcome |
|---|---|---|---|---|
| 1 | High | A matcher that throws on the check request, such as Boot's `EndpointRequest` with no servlet context, made the check a pass | Agree | Fixed: the real servlet context, and a matcher that still throws fails startup |
| 2 | High | `(POST, "/**").permitAll()` passed, since only GET was checked | Agree | Fixed: every method |
| 3 | Medium | `permit-paths=/*` was taken for everything | Agree | Fixed: the check path is two segments deep |
| 4 | Medium | A weak RSA modulus padded with zero bytes passed the size check | Agree | Fixed: the modulus's bit length |
| 5 | Medium | `{"keys":[null]}` was a 401, not an outage | Agree | Fixed |
| 6 | Medium | Outage tolerance counted from the fetch, not beyond the TTL | Agree | Fixed: tolerance plus TTL; a test at 2.5 s of 1 s + 3 s |
| 7 | Medium | A claim sent as `null` was a 500 | Agree | Fixed: null claims left out of `CurrentUser` |
| 8 | Medium | URL credentials in the fetch WARN | Agree | Fixed: protocol, host and port only |
| 9 | Low | The advisor cannot be replaced by name (§7.1) | Partly: replacing it is the very path that switched enforcement off. The decision is replaceable through `PermissionEvaluatorProvider` | Documented |
| 10 | Low | The writer fallback cannot make the body UTF-8 | Agree | Fixed: non-ASCII escaped, so the body is right in any charset |
| 11 | Low | Module docs still said one fetch per 30 s | Agree | Fixed |
| 12 | Low | The archetype README's command had a stray suffix | Agree | Fixed |
| T1 | Low | The audience precedence test did not call the platform | Agree | Replaced: a command-line audience replaces a configured list, through the app |
| T2 | Low | The ordering test does not interpose an auto-configuration | Partly: the name is now real, and the decoder bean is asserted | Not changed |
| T3 | Low | Provider ordering not tested through enforcement | Partly | Not changed; `PlatformAuthzWebTest` enforces through the advisor |
| T4 | Low | Health details, counters, spans, 1 MiB not asserted | Partly | Added: anonymous readiness shows no details; both counters and no user-derived tag, in the consumer run. Spans: no exporter in the run, stated as a limit |

HI-0100: Codex agrees Low is defensible and that it needs a trusted signature.

Tests after the fixes: security 77, restclient 108, authz 19, 0 failures (`evidence-raw/r2a.log`).

## Verification passes

| Pass | On | Verdict | Outcome |
|---|---|---|---|
| 1 ([out](codex/v1-verify.out.md)) | `84a66ae` | REJECT, 8 (none High) | F1 cached keys can outlive TTL plus tolerance by one more TTL: documented as the bound (scope R2.3, `security.md`). F2 a class permission was a 500 on an inherited method: fixed. F3 a matcher reading the server name failed startup: fuller stand-in request, plus an explicit, logged `skip-open-path-check`. F4 `/*/*` taken for everything: both of two depths must be open. F7 docs: fixed. F5, F6, F8 and the gaps: HI-0102, HI-0101, HI-0100, HI-0103 (`abe70ec`) |
| 2 ([out](codex/v2-verify.out.md)) | `abe70ec` | REJECT, 2 High | Confirmed all pass 1 dispositions and kept the residuals Low. F1 through a JDK proxy, a weaker class permission stood in for the implementing method's own, and a permission only on the implementing method was not enforced at all (the pointcut saw the interface method): both now resolve the most specific method first. F2 a rule that abstains (`access((a, c) -> null)`) on `/**` opened everything and passed the check, since `AuthorizationFilter` lets a null decision through: the check now counts an abstention as open. Tests for all three |
