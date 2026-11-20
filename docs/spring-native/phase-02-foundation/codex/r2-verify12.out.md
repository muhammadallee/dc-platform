**NOT RESOLVED** — round-1 finding 8 and round-2 P2-1/P3-1 remain open. The five reported fixes are present, but constructor discovery still has gaps.

I constructed three escape classes. The first works with the existing enum; the others use class-shaped `DataAccessFailure` fixtures under the contract’s constructor rule.

1. **Delegating constructor calls are unchecked** — [scanner line 524](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v15/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:524).

   ```java
   enum DataAccessFailure {
       X(500);

       DataAccessFailure(int status) {
           this(status, System.getProperty("unchecked.code"), "detail");
       }

       DataAccessFailure(int status, String code, String detail) {}
   }
   ```

   The enum constant has fewer than two arguments, so its construction is skipped. The delegated `this(...)` is a `MethodInvocationTree`, whose name matches none of the checked methods. Its code argument therefore escapes. Explicit `super(500, "BROKEN")` calls escape identically in class fixtures.

2. **Constructor references are unchecked** — [scanner line 540](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v15/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:540).

   ```java
   class DataAccessFailure {
       DataAccessFailure(int status, String code) {}
   }

   class Example {
       java.util.function.BiFunction<Integer, String, DataAccessFailure>
           maker = DataAccessFailure::new;

       Object failure = maker.apply(500, "BROKEN");
   }
   ```

   The member-reference visitor checks method names but omits constructor references (`new`). This contradicts the stated reference rule. This form cannot instantiate the current enum.

3. **Parameterized and annotated constructor types evade name recognition** — [scanner line 551](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v15/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:551), [helper line 811](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v15/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:811).

   For a class fixture with `DataAccessFailure(int status, String code)`, these constructions escape:

   ```java
   // Generic class DataAccessFailure<T>:
   new DataAccessFailure<String>(500, "BROKEN");
   new DataAccessFailure<>(500, "BROKEN");

   // Non-generic class; @A targets TYPE_USE:
   new @A DataAccessFailure(500, "BROKEN");
   ```

   `lastName()` handles identifiers and member selections, but returns empty for `ParameterizedTypeTree` and `AnnotatedTypeTree`. These class forms also do not apply to the current enum.

All three gaps predate `d023c5d`.

**New defect introduced: yes — incorrect signature erasure causes false rejections.** At [line 742](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v15/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:742), `erasure()` truncates `TypeMirror.toString()` at `<`; it does not perform Java erasure.

Two independently verified changes to the audited `create` method trigger this regression:

- Annotating its code parameter with a `TYPE_USE` annotation: `@A String code`.
- Declaring `<T extends String>` and using `T code`.

Both retain the audited erased parameter types, but the new scanner rejects the forwarded parameter as unknown. The parent accepts both. Canonical signature construction needs compiler type information rather than string truncation.

Validation used in-memory current/parent scanner probes on JDK 21. Escape fixtures produced no compiler errors and passed with ten unique control codes present. The Maven suite was not run; JDK 25 JShell failed loading its security configuration. No files changed.
