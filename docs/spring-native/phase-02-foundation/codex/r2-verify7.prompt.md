# Codex verification 7 — Spring-native Phase 2

Your verification 6 (`codex/r2-verify6.out.md`) found that a captured local's initialiser was resolved against an
anonymous class's shadowing field. The fix is `ed874a0`, touching only `ErrorCodeSourceUniquenessTest.java`, and it
replaces name resolution as a whole rather than that one path:

- A local's values are resolved once, at its declaration, in the scope in effect there, and stored as values.
- Inside a local or anonymous class every name of the enclosing method is unknown (an inherited member could shadow it).
- A simple field name is the field of the innermost enclosing class that declares it; past a class that inherits members
  (superclass, interfaces, anonymous) it is unknown. Only a `static final String` (or interface `String`) with a
  literal initialiser has a value; any other field is unknown.
- `Type.NAME` resolves only for a class with that simple name in the scanned sources, and only if every such class
  declares the name as a constant. Any other receiver (`this`, a variable, a call) is unknown.
- The cross-file "unique name" fallback is removed.

New fixtures cover your alias case, an inherited member in an anonymous class, instance fields (bare and `this.`), a
name only another file declares, and a qualified constant from another file. Review read-only.

Answer **ACCEPTED** or **NOT RESOLVED** (file, line, what is wrong) for the code gate as a whole (round-1 finding 8,
round-2 P2-1 and P3-1), then: did this fix introduce a new defect? Say "none" if none.
