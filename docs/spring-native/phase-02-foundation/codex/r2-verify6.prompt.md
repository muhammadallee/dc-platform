# Codex verification 6 — Spring-native Phase 2

Your verification 5 (`codex/r2-verify5.out.md`) found that a local declared in an inner block kept its value after the
block closed, so `{ String KEY = "detail"; } body.setProperty(KEY, …)` skipped the check. The fix is `bfa346a`, touching
only `ErrorCodeSourceUniquenessTest.java`. A name declared anywhere in a method is a local for the whole method: inside
its scope it is its initialiser; before its declaration or after its scope closes (block, loop, try, catch, lambda,
switch, case, if, local or anonymous class) it is unknown, and never a same-named constant. Methods of local and
anonymous classes keep the captured locals around them. Fixtures cover your case, a loop variable, a use before the
declaration, a captured local in an anonymous class, and an in-scope local that must still resolve. Review read-only.

Answer **ACCEPTED** or **NOT RESOLVED** (file, line, what is wrong) for the code gate as a whole (round-1 finding 8,
round-2 P2-1 and P3-1), then: did this fix introduce a new defect? Say "none" if none.
