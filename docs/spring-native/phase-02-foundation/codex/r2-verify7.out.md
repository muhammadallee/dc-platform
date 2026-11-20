**NOT RESOLVED** — the code gate still permits malformed codes.

1. [ErrorCodeSourceUniquenessTest.java:617](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v10/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:617): `lookupIn` treats a variable as a type whenever its spelling matches a scanned class. This single-file fixture escapes:

   ```java
   class D { static final String KEY = "detail"; }
   class Keys { String KEY = "code"; }
   class A {
       static final String KEY = "code";
       void broken(org.springframework.http.ProblemDetail body, Keys D) {
           body.setProperty(D.KEY, "BROKEN");
       }
   }
   ```

   Java resolves `D` to the parameter, so the key is `"code"`. The scanner resolves it to class `D`’s `"detail"` and skips validation.

2. [ErrorCodeSourceUniquenessTest.java:358](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v10/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:358): fields inside local/anonymous classes still enter `locals` because `methodDepth > 0`. An instance field `String key = "detail"` followed inside `run()` by `this.key = "code"; body.setProperty(key, "BROKEN");` is consequently skipped. Qualified assignments do not invalidate that stored value.

**New defect introduced: yes.** Finding 1 introduces a new false negative for the exact fixture above: the parent revision rejects it through its same-file `KEY = "code"` lookup; `ed874a0` skips it. Finding 2 already existed.

Read-only source review. The attempted in-memory reproduction could not run because JShell failed to load Java’s security configuration; no repository files changed.
