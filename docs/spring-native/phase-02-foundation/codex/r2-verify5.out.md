**NOT RESOLVED** — [ErrorCodeSourceUniquenessTest.java:252](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v8/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:252) still retains local **values** after their block scope ends:

```java
static final String KEY = "code";

void broken(org.springframework.http.ProblemDetail body) {
    { String KEY = "detail"; }
    body.setProperty(KEY, "BROKEN");
}
```

At runtime, the call’s key is `"code"`. The scanner retains `"detail"` in `locals`, so line 273 skips validation entirely. `"BROKEN"` also escapes the code-like literal check. Thus round-1 finding 8 and round-2 P2-1/P3-1 remain unresolved as a whole.

The receiver-type exemption defect is fixed.

**New defect introduced: none.** This remaining value-scope leak already existed in `0d83a66`.

Read-only static review; tests were not run.
