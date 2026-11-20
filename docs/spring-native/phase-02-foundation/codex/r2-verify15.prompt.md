# Codex verification 15 — Spring-native Phase 2

Your verification 14 (`codex/r2-verify14.out.md`) found no escape within the pinned-constructor contract. It listed one
pre-existing gap and one false positive that change introduced. Both are fixed in `2737a57`, in
`ErrorCodeSourceUniquenessTest.java` only:

1. **Increment and decrement.** A prefix or postfix `++` or `--` on an unresolved `code`, or on a
   `DataAccessFailure`'s `code` field, is reported as a write.
2. **Annotation arguments.** An annotation's `code = …` argument is an element value, not a field write, so it is no
   longer reported. A whole-code literal there still counts under the literal rule. A fixture puts such an annotation
   on the audited enum, and it passes.

The rest of the contract (class javadoc) is unchanged from verification 14. The errors module has 106 tests, and all
pass.

**What I'm asking for:**

- Answer **ACCEPTED** or **NOT RESOLVED** for the code gate as a whole (round-1 finding 8, round-2 P2-1 and P3-1),
  within this contract.
- If NOT RESOLVED, list **every** escape you can construct within the contract in this one answer. Mark each one
  either "introduced by this change" or "pre-existing".
- Then: did this change introduce a new defect? Say "none" if none.

Review read-only.
