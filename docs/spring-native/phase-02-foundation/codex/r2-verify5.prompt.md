# Codex verification 5 — Spring-native Phase 2

Your verification 4 (`codex/r2-verify4.out.md`) found a block-scope leak in the receiver-type exemption. The fix is the
commit after `0d83a66`, touching only `ErrorCodeSourceUniquenessTest.java`: the type-based exemption is gone. Every
`setProperty` whose key may be `code` is checked wherever it is called; the only exception is an audited list of exact
calls by file and source text (today one: the Oracle driver-properties copy in `OracleFlywayConnectionDefaults`).
Fixtures cover a `java.util.Properties` receiver (now checked) and your block-shadow case. Review read-only.

Answer **ACCEPTED** or **NOT RESOLVED** (file, line, what is wrong) for the code gate as a whole (round-1 finding 8,
round-2 P2-1 and P3-1), then: did this fix introduce a new defect? Say "none" if none.
