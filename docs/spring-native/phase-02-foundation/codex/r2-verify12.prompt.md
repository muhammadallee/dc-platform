# Codex verification 12 — Spring-native Phase 2

Your verification 11 (`codex/r2-verify11.out.md`) listed five escape classes. All five are addressed in `d023c5d`,
touching only `ErrorCodeSourceUniquenessTest.java`:

1. **Marker.** `FORWARDED` is a private `Object`, so no literal can equal it.
2. **For-each.** A for-each variable is opaque, so its value is unknown.
3. **Blank finals.** A field resolves only if javac reports it as a constant variable (`getConstantValue()`). javac
   inlines such a field, so it is never read before initialisation and never assigned. Its initialiser chain must still
   end in source literals. Blank finals and other fields are unknown.
4. **Overloads.** A forwarding entry names one exact method: fully qualified type, name and erased parameter types. A
   forwarded parameter must sit at position 1, the argument every call site of that method checks. Call forwarding names
   both the enclosing method's signature and the callee's signature, plus the source text.
5. **Folding.** javac's string folding is off (`-XDallowStringFolding=false`), so `"a" + "b"` stays two source
   literals and each one meets the literal rule.

A fixture covers each case. The contract and its accepted limits are unchanged; the class javadoc states them.

**What I'm asking for:**

- Answer **ACCEPTED** or **NOT RESOLVED** for the code gate as a whole (round-1 finding 8, round-2 P2-1 and P3-1),
  within this contract.
- If NOT RESOLVED, list **every** escape you can construct within the contract in this one answer.
- Then: did this change introduce a new defect? Say "none" if none.

Review read-only.
