**NOT RESOLVED** — [ErrorCodeSourceUniquenessTest.java:293](/C:/Users/Ali/AppData/Local/Temp/codex-p2v2/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:293). The new receiver filter skips method-call receivers unless named `getBody`. Consequently, this invalid code now escapes validation:

```java
ProblemDetail.forStatus(500).setProperty("code", "BROKEN");
```

**New defect: yes** — this receiver-filter regression. The previous scanner rejected that call. The three originally reported escapes are fixed.

Read-only static review; executable checks were blocked by JDK security-file access.
