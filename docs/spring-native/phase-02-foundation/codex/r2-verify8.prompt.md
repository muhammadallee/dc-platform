# Codex verification 8 — Spring-native Phase 2

Your verification 7 (`codex/r2-verify7.out.md`) found two escapes; both are fixed in `f6c9862`, touching only
`ErrorCodeSourceUniquenessTest.java`:

1. `D.KEY` with a variable `D`: `Type.NAME` now resolves only when the leftmost name is neither a local (including
   names declared later or out of scope) nor any variable declared anywhere in the scanned sources. Accepted limit,
   written beside the code: a field inherited from a type outside the scanned sources and named exactly like a scanned
   class. Platform code cannot resolve a code without `Type.NAME` (`PlatformExceptionHandler.VALIDATION_FAILED`,
   `Codes.INTEGRITY_CONFLICT`).
2. Fields of a local or anonymous class entering `locals`: a class body resets the method depth, so its fields are
   fields (unknown unless a literal constant), never locals.

Fixtures cover both of your cases. The gate's contract is the entry points in the class javadoc (`setProperty` on any
receiver, `factory.create`/`decorate`, `respond`, `DataAccessFailure`). Review read-only.

Answer **ACCEPTED** or **NOT RESOLVED** (file, line, what is wrong) for the code gate as a whole (round-1 finding 8,
round-2 P2-1 and P3-1), then: did this fix introduce a new defect? Say "none" if none.
