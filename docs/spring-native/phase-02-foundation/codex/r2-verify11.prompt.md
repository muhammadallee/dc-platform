# Codex verification 11 — Spring-native Phase 2

Your verification 10 (`codex/r2-verify10.out.md`) listed six escape classes. All six are addressed in `b5a89af`, touching
only `ErrorCodeSourceUniquenessTest.java`. The fix follows the class javadoc, which states the contract:

1. **Factory receivers.** `create`/`decorate` are checked unless javac resolves them to a method of a type other than
   `ProblemDetailFactory` (which is final). Unresolved calls are checked.
2. **Constructed constants.** javac's constant value is no longer used. Every name is followed to its declaration in the
   scanned sources (a local, or a `final` field), through its initialiser and every assignment to it, and only literals
   end a chain. A code built by concatenation is unknown, so every accepted code is a literal counted for uniqueness.
3. **Unresolved hierarchy.** The guard walks the whole supertype closure. A `CompletionFailure` counts as unresolved.
4. **Method references.** A method reference to an entry point is reported.
5. **Forwarding.** An exemption is now either `Type#method(parameter)` (a parameter never assigned, of that exact method
   of a fully qualified type) or `Type#method:call` (an audited call inside that method). `NOT_A_PROBLEM_DETAIL` is
   keyed by fully qualified type.
6. **Exclusions.** Directory exclusions are anchored at the repository root. `onlyTopLevelDirectoriesAreExcluded`
   proves it.

A fixture covers each of your six cases.

**Accepted limits** (unchanged): entry points other than those listed, and codes reached only through an audited
forwarding path.

**What I'm asking for:**

- Answer **ACCEPTED** or **NOT RESOLVED** for the code gate as a whole (round-1 finding 8, round-2 P2-1 and P3-1),
  within this contract.
- If NOT RESOLVED, list **every** escape you can construct within the contract in this one answer (file, line, a
  fixture, why it escapes).
- Then: did this change introduce a new defect? Say "none" if none.

Review read-only.
