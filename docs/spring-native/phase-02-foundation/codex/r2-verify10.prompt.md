# Codex verification 10 — Spring-native Phase 2

Your verification 9 (`codex/r2-verify9.out.md`) found an initialiser-block local. Rather than patch another lookup rule,
`24fb384` (touching only `ErrorCodeSourceUniquenessTest.java`) removes the hand-written name resolution altogether.

**The contract** (class javadoc):

- **Keys are never resolved.** Every `setProperty` on any receiver is checked, unless its key is a string literal other
  than `"code"`. Three audited driver-property calls on `java.util.Properties` are exempt by file and exact source text
  (`NOT_A_PROBLEM_DETAIL`).
- **Values** at the entry points (`setProperty`, `factory.create`/`decorate`, `respond`, `DataAccessFailure`) are
  resolved by javac. The sources are attributed (`task.analyze()`), and a name's value is one of two things: javac's
  compile-time constant value, or, for a local never assigned after its declaration (assignments are matched by
  element), its initialiser's values.
- **Unresolved hierarchies.** Inside a class whose superclass or interfaces javac cannot resolve, nothing resolves.
- **Everything else** is unknown and reported, apart from the audited forwarding paths in `FORWARDING`.

**Accepted limits:**

- Entry points other than those listed (for example `getProperties().put`). The contract is the listed entry points.
- A code reached only through the audited forwarding paths, whose upstream is checked.

**What I'm asking for:**

- Answer **ACCEPTED** or **NOT RESOLVED** for the code gate as a whole (round-1 finding 8, round-2 P2-1 and P3-1), within
  this contract.
- If NOT RESOLVED, list **every** escape you can construct within the contract in this one answer (file, line, a
  fixture, why it escapes), not only the first.
- Then: did this change introduce a new defect? Say "none" if none.

Review read-only.
