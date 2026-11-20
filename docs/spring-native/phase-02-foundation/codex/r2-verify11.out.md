**NOT RESOLVED** for round-1 finding 8 and round-2 P2-1/P3-1.

I found five escape classes. All references below are to `ErrorCodeSourceUniquenessTest.java`. Fixtures use Spring’s `ProblemDetail`.

1. **A literal can impersonate the forwarding marker** — [line 546](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v14/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:546).

   ```java
   void escape(ProblemDetail problem) {
       problem.setProperty("code", "\u0000forwarded");
   }
   ```

   The literal resolves to the same string as `FORWARDED`, so validation skips it despite there being no audited forwarding path. It is neither a valid code nor counted for uniqueness.

2. **Enhanced-for values disappear from the assignment analysis** — [line 637](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v14/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:637).

   ```java
   void escape(ProblemDetail problem, List<String> supplied) {
       for (String code : supplied) {
           problem.setProperty("code", code);
           code = "DC-TST-0001";
       }
   }
   ```

   The loop variable has no declaration initializer. Its implicit assignment from `supplied` is never recorded, leaving only the later valid literal assignment. Arbitrary supplied values therefore pass.

3. **Blank final fields can be read before their literal assignment** — [line 631](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v14/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:631).

   ```java
   class Early {
       final String code;

       Early(ProblemDetail problem) {
           publish(problem);
           code = "DC-TST-0001";
       }

       void publish(ProblemDetail problem) {
           problem.setProperty("code", code);
       }
   }
   ```

   This is legal Java and writes `null`. The resolver sees only the later literal assignment and accepts the field, ignoring its observable default value.

4. **Forwarding exemptions also authorize unaudited overloads** — [line 657](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v14/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:657).

   Add this overload and caller inside the actual fully qualified `PlatformExceptionHandler`:

   ```java
   void respond(ProblemDetail problem, String checked, String code) {
       problem.setProperty("code", code);
   }

   void escape(ProblemDetail problem) {
       respond(problem, "DC-TST-0001", "BROKEN");
   }
   ```

   The invocation checks argument 1, while the sink accepts argument 2 through `HANDLER#respond(code)`. Matching uses the method’s simple name and parameter name, without identifying the audited overload or parameter position. This is a new, unaudited forwarding path.

5. **javac folds source literals before the literal gate sees them** — [line 447](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v14/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:447) and [line 465](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v14/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:465).

   Two independently reproducible variants:

   ```java
   static final String BUILT = "D" + "C-TST-0001";
   // Passing BUILT to setProperty("code", BUILT) is accepted.
   ```

   ```java
   static final String CODE = "DC-TST-0001";
   String duplicate = "prefix" + "DC-TST-0001";
   String malformed = "prefix" + "DC-BROKEN";
   ```

   The first contradicts the stipulated rejection of constructed constants. In the second, javac merges the concatenations into literals beginning with `prefix`: the duplicate source literal and malformed source literal disappear from the gate’s accounting. Only one declaration is recorded, with no diagnostic.

**New defects introduced: yes.** Cases **1–4** are regressions: the parent scanner rejects these fixtures, while `b5a89af` accepts them. Case **5** also exists in the parent.

Verified using in-memory executions of both revisions’ scanner logic under Java 21, with a minimal `ProblemDetail` stub. No repository files changed; the full Maven suite was not run.
