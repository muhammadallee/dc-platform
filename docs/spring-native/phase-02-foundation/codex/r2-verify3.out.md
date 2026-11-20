**NOT RESOLVED** — [ErrorCodeSourceUniquenessTest.java:302](/C:/Users/Ali/AppData/Local/Temp/codex-p2v3/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:302). The exclusion treats subtype and bounded-type-parameter receivers as unrelated types. This still escapes validation:

```java
<T extends ProblemDetail> void broken(T customBody) {
    customBody.setProperty("code", "BROKEN");
}
```

Unless `customBody` is also declared explicitly as `ProblemDetail` elsewhere, it enters `otherNames` and the call is skipped. Thus round-1 finding 8 and round-2 P2-1/P3-1 remain unresolved as a whole. The reported chained-call escape is fixed.

New defects introduced by this fix: **none**. The remaining escape predates it.

Read-only static review; executable verification was blocked by JDK security-file access.
