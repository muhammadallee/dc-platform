**NOT RESOLVED** — round-1 finding 8 and round-2 P2-1/P3-1 remain open.

I found six escape classes within the stated contract. All scanner locations below refer to [ErrorCodeSourceUniquenessTest.java](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v13/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java). Fixtures assume the existing platform codes remain present, satisfying the minimum count.

1. **Factory calls still depend on receiver spelling — lines 549–585.**

   ```java
   void escape(Object object, ProblemDetailFactory[] factories) {
       ((ProblemDetailFactory) object)
           .create(500, "BROKEN", null, null, null, null);
       factories[0].create(500, "BROKEN", null, null, null, null);
       getFactory().create(500, "BROKEN", null, null, null, null);
   }
   ```

   `lastName()` returns an empty string for casts, array access and method results, so these calls never reach `check()`. Conditional receivers and constructor expressions escape identically.

   Declaration-based recognition also misses these forms:

   ```java
   <T extends ProblemDetailFactory> void bounded(T held) {
       held.create(500, "BROKEN", null, null, null, null);
   }
   ```

   A qualified, type-annotated declaration such as `internal.@A ProblemDetailFactory held` likewise fails the textual type comparison. These escapes affect both `create` and `decorate`.

2. **Constructed constants bypass uniqueness — lines 380–383 and 520–521. NEW.**

   ```java
   static final String ORIGINAL = "DC-TST-0001";
   static final String D = "D";
   static final String DUPLICATE = D + "C-TST-0001";

   void escape(ProblemDetail problem) {
       problem.setProperty("code", DUPLICATE);
   }
   ```

   Javac supplies the valid constant value, so validation succeeds. But declarations are collected only from whole-code literals: `DUPLICATE` contributes no declaration, and the duplicate is invisible. The same escape works with `final` local constants.

   The parent rejects the constructed value as unresolved; this revision accepts it.

3. **The unresolved-hierarchy guard checks only immediate supertypes — lines 535–539. NEW.**

   ```java
   class Parent extends Missing {}

   class Example {
       static final String CODE = "DC-TST-0001";

       class Child extends Parent {
           void escape(ProblemDetail problem) {
               problem.setProperty("code", CODE);
           }
       }
   }
   ```

   With `Missing` unavailable to the gate, `Child`’s immediate superclass is nevertheless the resolved type `Parent`. The guard passes, and javac recovers the enclosing `Example.CODE`.

   If the dependency available to the producing module defines `Missing.CODE = "BROKEN"`, that inherited field shadows the enclosing constant. The gate therefore accepts the wrong value instead of treating the unresolved hierarchy as unknown.

   An interface chain—`Parent extends Missing`, with `Child implements Parent`—escapes identically. Both inherited-name fixtures were rejected by the parent and accepted by this revision.

4. **Method references to listed entry points are unchecked — lines 392–412.**

   ```java
   void escape(ProblemDetail problem) {
       java.util.function.BiConsumer<String, Object> setter =
           problem::setProperty;
       setter.accept("code", "BROKEN");
   }
   ```

   The scanner handles method invocations and constructor expressions, but not member references. The target remains the listed `setProperty` entry point; this forwarding path is unaudited. References to `factory::create`, `factory::decorate`, and the handler’s `this::respond` have the same discovery gap.

5. **The forwarding exemption matches unaudited calls — line 435.**

   Add this method to `ProblemDetailFactory.java`:

   ```java
   void escape(ProblemDetail problem) {
       String code = "BROKEN";
       problem.setProperty("code", code);
   }
   ```

   `ProblemDetailFactory.java|code` matches before value resolution, so the malformed initializer is never checked. This is an additional setter with an unchecked upstream, not the audited forwarding operation.

   The match also uses only the basename, so an unrelated `ProblemDetailFactory.java` in another package receives the exemption.

6. **Path exclusions hide ordinary platform production files — lines 630–637.**

   Fixture file:

   ```text
   errors/platform-errors-autoconfigure/src/main/java/
   ae/gov/dubaicustoms/platform/errors/autoconfigure/scratch/Unchecked.java
   ```

   ```java
   package ae.gov.dubaicustoms.platform.errors.autoconfigure.scratch;

   class Unchecked {
       void escape(org.springframework.http.ProblemDetail problem) {
           problem.setProperty("code", "BROKEN");
       }
   }
   ```

   The `/scratch/` substring excludes this normal production source entirely. Package-directory components named `tmpviol`, `tooling`, `examples`, or `target` produce the same escape. These exclusions extend beyond the documented consumer-example and archetype exclusions.

**New defects introduced: yes — findings 2 and 3.** The other four gaps predate `24fb384`. The initializer-block key escape is fixed.

Validation used in-memory probes of the current and parent scanner helpers on JDK 21, including minimum-count controls. JDK 25 execution remained blocked by its security-configuration loading error; the project test suite was not run. No files changed.
