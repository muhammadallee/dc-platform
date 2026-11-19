**NOT RESOLVED** — [ErrorCodeSourceUniquenessTest.java:649](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v11/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:649) checks only the leftmost name. `this.D.KEY` still resolves against scanned class `D`, although `D` is an instance field:

```java
class D {
    static final String KEY = "detail";
}
class Keys {
    String KEY = "code";
}
class A {
    Keys D = new Keys();

    void broken(org.springframework.http.ProblemDetail p) {
        p.setProperty(this.D.KEY, "BROKEN");
    }
}
```

The guard checks `this`, then line 654 selects class `D`. It resolves the key as `"detail"` and skips validation; the actual key is `"code"`. This falls outside the accepted external-inheritance limitation. Round-1 finding 8 and round-2 P2-1/P3-1 remain unresolved.

**New defect introduced: none identified.** This escape also exists before `f6c9862`.

Read-only static review; executable verification was blocked by `Error loading java.security file`.
