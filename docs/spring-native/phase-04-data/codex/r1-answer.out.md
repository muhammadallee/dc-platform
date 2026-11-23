**Findings 2 and 3 remain partly resolved.** I found **two new Medium regressions**. This was a read-only source review of `98191f2..9ad0b15`; I did not rerun tests or the consumer.

| # | Assessment | Narrowed contract |
|---|---|---|
| 1 | **Resolved.** Non-plain keys now conservatively mask their associated values. | **Accepted**, including enum keys classified by `name()`. The numeric-key regression below is separate. |
| 2 | **Partly resolved.** Ordinary projections are protected, but proxies implementing text interfaces still bypass the guard. | **Accepted in principle; incompletely implemented.** |
| 3 | **Partly resolved.** `Optional`, ordinary `Map.Entry` and `AtomicReference` are addressed, but earlier scalar branches bypass the advertised rendering restrictions. | **Accepted in principle**, including the stated resource and property-less-object limits; implementation remains broader. |
| 4 | **Resolved.** JPA recognition precedes the relevant text shortcuts, including pair and map-key handling. | **Accepted.** The JDK-proxy gap under #2 is distinct. |
| 5 | **Resolved in source.** Both source trees are scanned, static/indented/fully qualified references match, and scan errors fail. | Consumer execution remains outstanding. |
| 6 | **Resolved in source.** The added nesting, placeholder counts, initialization checks and positive SQL control address the missing coverage. | Consumer execution remains outstanding. |
| 7 | **Resolved.** The golden path checks the packaged Flyway integration and successful migration startup; the slice checks defaults. | **Accepted as a split proof.** I have not independently verified a passing generator-gate run for this commit. |

The remaining High findings are incomplete fixes, **not newly introduced regressions**:

- **#2 — High: text-like JDK proxies still execute their invocation handler.**  
  File: [PlatformValueMasker.java:409](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1a-070205/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:409), also lines 183, 227 and 395–403.  
  A JDK proxy implementing `CharSequence`, whose `toString()` delegates to a Hibernate reference, is rendered by `sanitizePair()`, `mask()` and the nested walker before reaching the proxy branch. A proxy implementing `Path` similarly bypasses the walker’s guard. Logging can therefore initialize the reference and execute SQL.  
  **Fix:** recognize JDK proxies before all text shortcuts, including both public entry points. Test a text-like proxy whose handler counts every invocation.

- **#3 — High: arbitrary scalar subclasses evade the narrowed rendering policy.**  
  File: [PlatformValueMasker.java:431](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1a-070205/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:431), also lines 401–403 and 562–575.  
  An application `Number` subclass with a `getEntity()` bean property and a `toString()` that renders that entity still reaches `render(number)`. It never reaches bean walking or the new allow-list. An application `Throwable` subclass overriding `toString()` has the same escape. These objects have bean properties, so the accepted property-less-object exception does not cover them.  
  **Fix:** restrict these shortcuts to explicitly trusted types and route other subclasses through structural handling. Add regression cases holding unloaded Hibernate references.

**New issues introduced by this diff:**

1. **Medium — A throwing standalone `Map.Entry` accessor discards surrounding values.**  
   File: [PlatformValueMasker.java:423](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1a-070205/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:423), accessor calls at lines 506 and 509.

   **Failure scenario:** put an application `Map.Entry` implementation whose `getValue()` throws inside `List.of("keep-me", entry)`. Previously, bean walking guarded the accessor and retained the list and healthy values. The new entry branch calls the accessor directly; its exception escapes to `sanitize()` and replaces the entire list with `[unrenderable]`.

   **Fix:** guard entry accessors locally and contain failure to the entry or failed member, preserving surrounding values. Test both throwing `getKey()` and `getValue()` inside another container.

2. **Medium — `Float` and `Double` keys unnecessarily erase their associated values.**  
   File: [PlatformValueMasker.java:718](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1a-070205/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:718).

   **Failure scenario:** `Map.of(1.5d, "healthy")` now renders as `{1.5=****}`. The plain-key list includes integral wrappers and big-number classes but omits `Float` and `Double`, so both take the conservative complex-key branch. This loses ordinary diagnostic values and contradicts the stated plain-JDK-number treatment.

   **Fix:** include exact `Float.class` and `Double.class` in `simpleKey()` and verify that their ordinary associated values survive while credential-name masking remains intact.

No other new issues established in this diff.
