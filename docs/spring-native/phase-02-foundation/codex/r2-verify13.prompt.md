# Codex verification 13 — Spring-native Phase 2

Your verification 12 (`codex/r2-verify12.out.md`) listed three constructor-discovery gaps and one regression, the
string-truncating erasure. All are addressed in `dbb00db`, touching only `ErrorCodeSourceUniquenessTest.java`:

1. **Constructor invocations.** Every invocation of a constructor of a type named `DataAccessFailure` is checked at its
   parameter named `code`. That covers `new`, enum constants, `this(…)` and `super(…)`, all resolved by javac's
   constructor element. Every assignment to such a type's `code` field, and its initialiser, is checked as an entry
   point. The real constructor's `code` parameter is an audited forwarding parameter, exact signature at position 1.
2. **References and subclasses.** A `DataAccessFailure::new` reference is reported, and so is an anonymous subclass.
3. **Type names.** Unresolved type names see through type arguments and type annotations.
4. **Erasure.** Signatures use `Types.erasure`; a fixture with `<T extends String> … T code` stays accepted.

A fixture covers each case. The contract (class javadoc) and its accepted limits are unchanged.

**What I'm asking for:**

- Answer **ACCEPTED** or **NOT RESOLVED** for the code gate as a whole (round-1 finding 8, round-2 P2-1 and P3-1),
  within this contract.
- If NOT RESOLVED, list **every** escape you can construct within the contract in this one answer.
- Then: did this change introduce a new defect? Say "none" if none.

Review read-only.
