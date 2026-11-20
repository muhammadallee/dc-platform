# Codex verification 14 — Spring-native Phase 2

Your verification 13 (`codex/r2-verify13.out.md`) listed five escapes and one crash. Rather than support each new
`DataAccessFailure` shape, the gate now accepts exactly one shape and reports the rest. The change is in
`ErrorCodeSourceUniquenessTest.java` only; see the class javadoc for the full contract.

**`DataAccessFailure` is pinned, and the gate fails closed:**

1. **One declaration.** The only type that may be named `DataAccessFailure` is the audited enum
   `ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.DataAccessFailure`. It must have exactly one constructor:
   `(int status, String code, String detail)`, erased, not varargs, with parameter 1 named `code`. Any other declaration
   of that name is reported, whether it is a class, a record, another package, extra constructors or varargs.
2. **Constructions.** An invocation of that constructor with exactly three arguments (`new`, enum constant, `this(…)`,
   `super(…)`) is checked at argument 1. An invocation of any other `DataAccessFailure` constructor is reported,
   including one from the classpath. The argument count is checked before any argument is read, so the empty-varargs
   crash is gone.
3. **Unresolved constructions.** These are reported, never guessed at:
   - a `new` of a type named `DataAccessFailure`;
   - a `this(…)` in a class of that name;
   - a `super(…)`, qualified or not, in a class whose `extends` clause names it.

   The old argument-1 fallback is gone.
4. **The `code` field.** An assignment to it is checked. A compound assignment is reported. A write to a `code` that
   javac cannot resolve is also reported.
5. **`setProperty`.** A call with other than two arguments is reported, unless its key is a literal other than `"code"`
   or it is in the audited `NOT_A_PROBLEM_DETAIL` list.

A fixture covers each of your verification-13 cases, plus the audited shape, which passes. The platform's own sources
still pass unchanged; the errors module has 106 tests and all pass.

**What I'm asking for:**

- Answer **ACCEPTED** or **NOT RESOLVED** for the code gate as a whole (round-1 finding 8, round-2 P2-1 and P3-1),
  within this contract.
- If NOT RESOLVED, list **every** escape you can construct within the contract in this one answer. Mark each one
  either "introduced by this change" or "pre-existing".
- Then: did this change introduce a new defect, such as a crash or a false positive on the real enum? Say "none" if
  none.

Review read-only.
