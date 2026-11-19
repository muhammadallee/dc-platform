# Codex verification 9 — Spring-native Phase 2

Your verification 8 (`codex/r2-verify8.out.md`) found that `this.D.KEY` with a field `D` resolved against class
`D`: only the leftmost name was checked. Fixed in `155ba21`, touching only `ErrorCodeSourceUniquenessTest.java`:
`Type.NAME` resolves only when every name of the receiver chain is a plain name and none is `this`, `super`, a
local or any variable declared in the scanned sources; any other receiver is unknown. A fixture covers your case. The
accepted limit and the gate's contract are unchanged from verification 8. Review read-only.

Answer **ACCEPTED** or **NOT RESOLVED** (file, line, what is wrong) for the code gate as a whole (round-1 finding 8,
round-2 P2-1 and P3-1), then: did this fix introduce a new defect? Say "none" if none.
