**NOT RESOLVED** for round-1 finding 8 and round-2 P2-1/P3-1.

I reproduced five remaining escape classes within the stated contract. Locations below refer to [ErrorCodeSourceUniquenessTest.java](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v16/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java). Fixtures assume the existing platform codes remain present to satisfy the minimum count.

1. **Compound assignments to the `code` field escape — lines 590–602.**

   ```java
   class DataAccessFailure {
       String code = "DC-TST-0001";

       void mutate(String supplied) {
           code += supplied;
       }
   }
   ```

   The initializer passes, but the entry-point scanner has no `visitCompoundAssignment`. The separate value collector marks the field opaque, which has no effect unless something subsequently asks it to resolve that field. Result: `unsupported=[]`.

2. **A varargs constructor checks only the first argument supplied for `code` — lines 607–614.**

   ```java
   class DataAccessFailure {
       DataAccessFailure(String... code) {}

       void escape() {
           new DataAccessFailure("DC-TST-0001", "BROKEN");
       }
   }
   ```

   Formal-parameter index 0 is mapped to actual-argument index 0; the remaining varargs values disappear from validation. The array-valued parameter is incorrectly accepted as one literal. The same gap affects enum constants and constructor delegation, and works with `int status, String... code`. Result: `unsupported=[]`.

3. **Unresolved `DataAccessFailure` entry points still fail open — lines 583–584, 617–619 and 678–680.**

   With the dependency unavailable to the gate’s attribution classpath:

   ```java
   class Child extends external.DataAccessFailure {
       Child() {
           super(500, "BROKEN");
       }
   }
   ```

   `super` supplies an empty fallback name, so the unresolved constructor is skipped. Qualified `outer.super(...)` behaves identically.

   Two further variants also escape:

   ```java
   class Uses {
       external.DataAccessFailure failure;

       void escape() {
           failure.code = "BROKEN";
           new external.DataAccessFailure("BROKEN");
       }
   }
   ```

   The field write requires a resolved `VariableElement`; the one-argument construction fails the fallback’s `argumentCount() >= 2` condition.

   For longer unresolved constructions, the fallback still assumes argument 1. Thus a dependency constructor declared as `(int status, String detail, String code)` accepts this unchecked code:

   ```java
   new external.DataAccessFailure(500, "DC-TST-0002", "BROKEN");
   ```

   All these probes returned `unsupported=[]`.

4. **Compact record constructors hide their implicit field assignment — lines 590–602.**

   ```java
   record DataAccessFailure(int status, String code, String detail) {
       DataAccessFailure {
           code = System.getProperty("unchecked.code");
       }

       static void escape() {
           new DataAccessFailure(500, "DC-TST-0001", "detail");
       }
   }
   ```

   The invocation’s literal passes. The visible assignment targets the parameter; the implicit assignment of its modified value to `this.code` is absent from the scanned tree. Consequently, the promised field-assignment check never runs. `code += "BROKEN"` in a compact constructor escapes identically. Result: `unsupported=[]`.

5. **`setProperty` calls with additional arguments are skipped — line 575.**

   ```java
   class Sink {
       void setProperty(String key, Object value, Object... extra) {}

       void escape() {
           setProperty("code", "BROKEN", 0);
       }
   }
   ```

   The contract says every `setProperty`, on any receiver, subject to the stated key/audit exceptions. The implementation additionally requires exactly two actual arguments, silently excluding this overload. Result: `unsupported=[]`.

**New defects introduced: yes.** Finding 2’s exact fixture is rejected by the parent and accepted by `dbb00db`. Additionally, an empty varargs invocation now throws `IndexOutOfBoundsException` at line 660:

```java
class DataAccessFailure {
    DataAccessFailure(String... code) {}
    void example() { new DataAccessFailure(); }
}
```

An implicit `super()` targeting that constructor crashes identically. This is a scanner failure, not another passing escape. The other listed gaps predate this commit.

Validation used in-memory executions of the current and parent scanner under JDK 21. JDK 25 execution was blocked by its security-configuration loading error; the Maven suite was not run. No repository files changed.
