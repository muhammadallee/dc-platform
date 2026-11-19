# Phase 2 — Codex round 2

Gate D (plan §8). Base `516f54c`, reviewed `c209736`. Prompt: [`codex/r2-review.prompt.md`](codex/r2-review.prompt.md);
Codex's answer, verbatim: [`codex/r2-review.out.md`](codex/r2-review.out.md).

Codex: "Nine round-1 findings are resolved; **8 and 11 remain open**. I found no new Critical or High defect." It found
no further regression in Spring's mapped exception paths, the copied localisation, status normalisation, reserved-name
removal, the MDC scopes (including Micrometer's reverse restore order), the capability overrides or the filter back-off.

Claude agrees with every finding; there is no dispute to record.

| # | Severity | Finding | Fix | Proof |
|---|---|---|---|---|
| r1 #8, P2-1, P3-1 | Medium | The code gate trusted a local's initialiser after a reassignment, and recognised factory calls only on the identifier `factory` (`this.factory.create(…)` escaped) | Reassigned locals never resolve; a factory call is any `create`/`decorate` on a name declared as `ProblemDetailFactory`, however qualified | Negative fixtures for both in `theScannerSeesEveryFormattingAndRejectsEveryUncheckedCode` |
| r1 #11, P2-6 | Low | The sample's tagged snippet was never compiled: discovery covered `modules` and `book` only | Discovery covers `samples`; the test asserts one snippet from each tree is collected | `UsageSnippetCompileTest` |
| P2-2 | Medium | A failed `lab-check` such as `false && echo x` passed: bash exempts the left of `&&` from errexit | Every block's status is checked after it, and every line of a check on its own | Runner fixtures: `false && …`, `! true`, `test 1 = 2` each fail at their step, the next step never runs |
| P2-3 | Medium | The lab accepted a wrong HTTP status | The lab captures `%{http_code}` and checks it and the body's `status` | Lab passes the runner (`evidence/lab-first-service-run.txt`) |
| P2-4 | Medium | Cleanup sent a signal and reported success without confirming the process was gone | The lab waits for its app to exit and checks; the runner waits, escalates to `kill -9`, fails the run if anything survives, and prints the verdict after cleanup | Lab run; earlier surviving-process fixture |
| P2-5 | Medium | A constraint violation after the response was committed vanished without a log line | Logged once at ERROR on the request's trace; nothing written | `aCommittedResponseGetsNothingAndTheViolationIsLoggedOnce` |
| P3-2 | Medium | C8's pool-cleanup proof read the worker through the decorated executor, which could hide leftovers | Read through the raw pool, on the same worker thread | `asyncWorkSeesTheCallersTraceAndPoolThreadsAreCleanAfterwards` |
| P3 (C15) | — | The proof named only the default-off half | Both halves named in `scope.md` | — |

**Verification:** `mvn -o -T1 -pl core/platform-core-autoconfigure,errors/platform-errors-autoconfigure,docs/platform-docs install`
→ core 73, errors 104, docs 35, no failures, no skips (`evidence-raw/gateD-r2-fixes-1.log`, `gateD-r2-docs.log`). The
first-service lab passes the runner. The constraint-advice log line is a material change after round 2, so a
verification pass follows: [`codex/r2-verify.prompt.md`](codex/r2-verify.prompt.md).

**Verification passes on the code gate** (prompts beside each answer in [`codex/`](codex/)):
- [`r2-verify.out.md`](codex/r2-verify.out.md): items 2–7 accepted; the gate reopened (a reassigned local fell back to a
  same-named constant; a reassigned `code` key skipped the call; a parenthesised receiver).
- [`r2-verify2.out.md`](codex/r2-verify2.out.md): those three fixed; a receiver filter let `ProblemDetail.forStatus(500)`
  calls escape.
- [`r2-verify3.out.md`](codex/r2-verify3.out.md): fixed; a bounded type-parameter receiver escaped.
- [`r2-verify4.out.md`](codex/r2-verify4.out.md): fixed; a block-scoped local of another type leaked its type.
- [`r2-verify5.out.md`](codex/r2-verify5.out.md): the receiver-type fix accepted (every `setProperty` whose key may be
  `code` is checked wherever it is called, with one audited exemption by exact source text); a local declared in an
  inner block kept its value after the block closed.
- [`r2-verify6.out.md`](codex/r2-verify6.out.md): locals follow Java's scopes (`bfa346a`); that fix let a captured
  local's initialiser resolve against an anonymous class's shadowing field.
- [`r2-verify7.out.md`](codex/r2-verify7.out.md): names resolve as Java resolves them (`ed874a0`); a variable named
  like a class was read as the class, and a local class's fields still entered the locals.
- [`r2-verify8.out.md`](codex/r2-verify8.out.md): a variable is never read as a type and class bodies hold fields,
  not locals (`f6c9862`); `this.D.KEY` still escaped, as only the leftmost name was checked.
- Verification 9: every name of a qualified receiver is checked.

