**A, C, E and prior new issues 1–3 are resolved under the revised scope. Prior new issue 4 is partly resolved. I found three new issues.**

Reviewed `474ceaa..d5a27ab` read-only against the source and relevant local Boot 4.1.2 bytecode. No files changed; I did not rerun the reported 164 logging and 51 observability tests.

| Item | Disposition | Reason |
|---|---|---|
| A | **Resolved** | `Binder` receives the original property value and binds it directly to `Integer`, addressing the typed `Double` case. |
| C | **Resolved** | The five-name exemption now requires an ECS configuration. I accept the explicitly stated mixed-format exception: configuring ECS for either destination enables the exemption for both. |
| E | **Resolved** | Failed accessors now consume budget before invocation, and both iterator loops check exhaustion before calling `hasNext()`. Successful accessor values are charged again by `copy()`, making the limit conservative. |
| New 1 | **Resolved** | Accepted scope change: output from service name processors is now explicitly the service’s responsibility. |
| New 2 | **Resolved** | Ordinary Logback/Log4j objects no longer receive the former package-wide exemption; the reported logger value becomes masked text. A separate exemption introduced here creates finding 1 below. |
| New 3 | **Resolved** | Accepted scope change: service value processors at the root or nested, in any order, are explicitly outside the guarantee. |
| New 4 | **Partly resolved** | The successful-start GELF scenario now works. However, the listener observes the property’s current value rather than proving Boot wrote it, and failed logging initialization leaves assignments untracked. |

**New issues introduced by this diff: three.**

1. **High — The new Boot JSON package exemption bypasses masking for ordinary data.**  
   Location: [PlatformValueMasker.java:244](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1e/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:244).

   **Failure scenario:** With Boot’s Logstash format, log a key-value pair whose value is `JsonWriter.MemberPath.of("4111111111111111")`. It is neither a logging event nor a writer, but the new `org.springframework.boot.json.*` exemption returns it untouched. Boot renders its `toString()`, exposing the card number. Previously, the record’s `name` component passed through masking. A flat customizer-added value has the same problem, without using a service processor.

   **Fix:** Remove the package-wide exemption; exempt only the specific writer infrastructure required at its actual writer positions. Add a regression for an ordinary Boot JSON object supplied as data.

2. **Medium — Failed logging initialization leaves stale format properties behind.**  
   Location: [LoggingFormatOwnership.java:21](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1e/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/LoggingFormatOwnership.java:21).

   **Failure scenario:** A platform JSON start writes the format bridge properties, then logging initialization throws—for example, because `logging.config` references a missing file. Boot applies `LoggingSystemProperties` **before** initializing the logging system. Consequently, this later listener never records ownership, although the assignments already exist. A subsequent console-mode start in the same JVM preserves the stale structured format. The previous predicted marker covered this unchanged-format failure case.

   **Fix:** Track actual bridge assignments independently of successful logging initialization, and reconcile or clean them on failure. Test a real failed logging start followed by a console-mode start; merely asserting that no marker was recorded misses the regression.

3. **Medium — Service-written bridge properties can be claimed and subsequently deleted.**  
   Location: [LoggingFormatOwnership.java:28](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1e/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/LoggingFormatOwnership.java:28).

   **Failure scenario:** The bridge property is unset during platform post-processing. A service listener running before Boot’s logging listener sets `CONSOLE_LOG_STRUCTURED_FORMAT=gelf`. Boot preserves that existing value, but the ownership listener records it as Boot-owned. The next start deletes the service’s property. Previously, the predicted platform-formatter marker differed from `gelf`, so cleanup preserved it.

   **Fix:** Establish ownership at the actual conditional assignment, rather than inferring it from an earlier absence and a later value. Cover a service setting the bridge property between post-processing and logging initialization.

I found no other new issues in this diff.
