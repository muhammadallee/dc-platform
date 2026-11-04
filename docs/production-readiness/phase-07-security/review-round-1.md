# Phase 7 — review round 1

Codex reviewed `c7373c6..a56d14d`: **REJECT, 20 findings** ([output](codex/r1-review.out.md)). No concrete defect in ordinary signature verification, `none` and HMAC rejection, or context propagation.

| # | Codex | Finding | Claude | Outcome |
|---|---|---|---|---|
| 1 | High | `beforeName` named a class that does not exist, so Boot's lax decoder could win on ordering | Agree | Fixed; a test loads the real name |
| 2 | High | Scalar and indexed list properties were merged, so an override kept the old audience | Agree | Fixed: Boot's binder |
| 3 | High | A customizer's `requestMatchers("/**").permitAll()` opens everything | Agree | Fixed: the built chain is asked about an unnamed path, and startup fails. `permit-paths=/**` too |
| 4 | High | An unrelated bean named `requiresPermissionAdvisor` switched enforcement off | Agree | Fixed: no back-off, a clash fails startup. **My first fix, a by-type condition, matched Spring's own advisor and disabled enforcement; `PlatformAuthzWebTest` caught it** |
| 5 | High | A redirect from an allowed JWKS URL skipped the URL check | Agree | Fixed: redirects are failed fetches |
| 6 | Medium | RSA keys under 2048 bits accepted | Agree | Fixed: ignored |
| 7 | Medium | `sub: 123` becomes a string; a huge `nbf` overflows in Nimbus | Partly: real, but needs a token signed by the trusted IdP, so nothing an attacker can reach. Low | HI-0100, residual |
| 8 | Medium | The limiter allows two fetches per interval | Partly: harmless; the scope was wrong | Scope corrected: at most 2 |
| 9 | Medium | A 200 with HTML counted as a good fetch: 401, not 503, no WARN | Agree | Fixed: a response with no keys is a failed fetch |
| 10 | Medium | A dripping response held the refresh indefinitely; zero meant no timeout | Agree | Fixed: a budget on the whole fetch; non-positive durations fail startup |
| 11 | Medium | `scp: [123]` was a 500 | Agree | Fixed |
| 12 | Medium | An `IOException` from a non-default converter kept its text | Agree | Fixed: any failure from a converter |
| 13 | Medium | A customizer could replace the wrapped converters | Agree | Fixed: wrapping runs after the customizers |
| 14 | Medium | `getOutputStream` fails after a filter took the writer | Agree | Fixed |
| 15 | Medium | Composite and child registries double-counted | Agree | Fixed: the primary registry |
| 16 | Medium | The relay properties differ from the scope | Partly: the list is the better design | Scope corrected |
| 17 | Medium | `GreetingClientTest` asserts a relay that is now off | Agree | Fixed |
| 18 | Medium | The golden path's later launches lacked issuer and audience | Agree | Fixed |
| 19 | Low | Parser position dropped | Partly: it would need Jackson in this module | Scope corrected |
| 20 | Low | No CHANGELOG entry | Agree | Fixed |

Tests after the fixes: security 69, restclient 108, authz 19, 0 failures (`evidence-raw/r1a.log`).
