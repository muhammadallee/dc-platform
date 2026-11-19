**NOT RESOLVED** — [ErrorCodeSourceUniquenessTest.java:244](/C:/Users/Ali/AppData/Local/Temp/codex-p2v4/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:244) records local types without restoring block scope. The exemption at line 308 therefore skips this field call:

```java
ProblemDetail body = ProblemDetail.forStatus(500);

void broken() {
    { java.util.Properties body = new java.util.Properties(); }
    body.setProperty("code", "BROKEN");
}
```

After the block, `body` refers to the `ProblemDetail` field, but `localTypes` still identifies it as `java.util.Properties`. `"BROKEN"` escapes validation, leaving round-1 finding 8 and round-2 P2-1/P3-1 unresolved as a whole.

**New defect introduced: yes — this scope-leak escape.** At `b1afda9`, the explicit `ProblemDetail` field placed `body` in `problemNames`, so this call was checked. The bounded-type-parameter escape is fixed.

Read-only static review; tests were not run.
