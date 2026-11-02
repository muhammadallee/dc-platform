Reviewed `471db62..6741388` and surrounding code. Static review only; no JVM or Docker execution. The parked and accepted issue IDs are excluded.

“Right” means the stated disposition addresses the finding; it does not imply that tests passed.

| Item | Assessment | Result |
|---|---|---|
| D1 | **Partial** | Reads both sources, but rereading the file does not establish the JDK’s cached setting. `DefaultPlatformRestClientFactory.java:77–90`; finding 3 below. |
| D2 | **Right — documented residual** | The interceptor/upload limitation is explicitly documented. Not re-reported. |
| D3 | **Partial** | Simple examples work; truncated/multiple CDATA sections and mixed quotes still expose secrets. `RemoteCallException.java:67–70`; finding 2. |
| D4 | **Right** | The query/fragment suffix, including whitespace, is removed from retained I/O causes. Other disclosure paths remain in finding 4. |
| D5 | **Partial** | A smaller clock window still cannot distinguish a reset from timer cancellation. `ResilientRequestFactory.java:389`; finding 9. |
| D6 | **Right** | The reported 60-second opening condition is removed. The new threshold also suppresses ordinary slow-call metrics. |
| D7 | **Right for independent registries** | Both receive events, once per sequential binding. Overlapping destinations and registry retention introduce separate problems; findings 7–8. |
| D8 | **Right** | The guard includes `backends`. |
| D9 | **Partial** | Creation ownership does not imply exclusive use after publication. `DefaultPlatformRestClientFactory.java:140–150`; finding 6. |

Concrete production defects:

1. **High — Metrics failure can discard a successful outcome, leak permits and cause duplicate POSTs.**  
   [ResilientRequestFactory.java:157](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:157), [PlatformRestClientAutoConfiguration.java:93](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfiguration.java:93).

   **Trigger:** A bound registry already contains a gauge with the counter’s name and identical tags—for example, `platform.restclient.attempts`, `client=orders`, `outcome=success`. A POST succeeds, then the attempt listener registers its counter.

   **Consequence:** Micrometer throws `IllegalArgumentException`. This occurs outside the send’s cleanup block and before `GuardedResponse` exists: the successful response is lost, its bulkhead permit is never returned, and a half-open permission never receives a verdict. Repetition can permanently exhaust the client. An enclosing default `RetryableOperation` retries this exception, sending the applied POST again. Multiple listeners also mean one failing registry prevents subsequent listeners from receiving the event. [Micrometer’s conflicting-type check](https://raw.githubusercontent.com/micrometer-metrics/micrometer/v1.16.5/micrometer-core/src/main/java/io/micrometer/core/instrument/MeterRegistry.java).

2. **High — XML credentials still survive ordinary, literal representations.**  
   [RemoteCallException.java:67](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-api/src/main/java/ae/gov/dubaicustoms/platform/restclient/RemoteCallException.java:67), lines 68–70.

   **Triggers:**
   - An error body begins `<password><![CDATA[PRIVATE` and its `]]>` falls beyond the 8192-byte read boundary. The CDATA alternative fails; the other alternative matches an empty string before `<`, leaving `PRIVATE` in the snippet.
   - `<password><![CDATA[first]]><![CDATA[PRIVATE]]></password>`: only the first section is removed.
   - `<password value="prefix'PRIVATE"/>`: the attribute pattern stops at the apostrophe even though the attribute uses double quotes. `PRIVATE` remains inside the opening tag, which `XML_VALUE` preserves.

   **Consequence:** Credentials remain in `bodySnippet()` and reach the consumer probe’s snippet log. These are neither encoded names nor unusually named secrets.

3. **High — The retry guard can disagree with the effective, cached JDK configuration.**  
   [DefaultPlatformRestClientFactory.java:77](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:77).

   **Trigger:** Earlier networking initializes the JDK networking properties while `enableAllMethodRetry=true`. The file is subsequently corrected to `false`, and the platform factory is constructed or its application context restarted without restarting the JVM.

   **Consequence:** The guard accepts the current file while the JDK retains the earlier value. A processed POST followed by connection loss can be resent. `NetProperties` caches the file, and `MultiExchange` separately caches the resulting retry flag. Construction-time rereading cannot verify either cache. [JDK property cache](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/share/classes/sun/net/NetProperties.java), [JDK retry flag](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/MultiExchange.java).

4. **High — Transport exceptions still expose credentials through two paths.**  
   [ResilientRequestFactory.java:151](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:151), line 260.

   **Triggers:**
   - Set `X-Api-Key` to a secret containing a newline, such as a mounted secret read without removing its trailing newline. JDK header validation throws a runtime exception quoting the complete value; this branch rethrows it unchanged.
   - Return a redirect location such as `http://user:PRIVATE VALUE@example.test/`. URI parsing rejects it, but the userinfo-removal regex cannot cross the space. There is no `?` or `#` to trigger suffix removal.

   **Consequence:** Credentials remain in the propagated exception. The API-key case also reaches the generic exception logger without the header name that could identify it for credential masking. [JDK header validation](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/HttpRequestBuilderImpl.java).

5. **High — URL fragments remain in observations and metric tags.**  
   [DefaultPlatformRestClientFactory.java:258](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:258).

   **Trigger:** Call `.uri("/callback#access_token=PRIVATE")`, with no query.

   **Consequence:** `stripped()` removes only queries and userinfo. The fragment remains in `http.url` and, for this string-template overload, the `uri` metric tag. D4’s exception sanitizer does not protect observations. [Spring observation extraction](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-web/src/main/java/org/springframework/http/client/observation/DefaultClientRequestObservationConvention.java).

6. **Medium — A newly created transport can already be serving another builder when its creator cancels it.**  
   [DefaultPlatformRestClientFactory.java:140](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:140).

   **Trigger:** Builder A publishes its transport through `computeIfAbsent`, then pauses before line 146. Builder B obtains that transport, passes the closed check and starts a call. Destruction begins its graceful wait. A resumes, sees `closed`, and invokes `shutdownNow()` through `created[0]`.

   **Consequence:** B’s active call is cancelled before its grace period. The array correctly identifies the mapping-function invocation that created the transport; it does not establish that nobody else has acquired it.

7. **Medium — Multiple bindings double-count when composite registries share a destination.**  
   [PlatformRestClientAutoConfiguration.java:85](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfiguration.java:85), lines 93–94.

   **Trigger:** Two user composite registries contain the same exporter registry, and both receive the binder.

   **Consequence:** Each attempt invokes both listeners; both increments reach the same underlying counter. Identity deduplication of the composites does not deduplicate their destinations. Independent composites are counted correctly. [Boot binds user composites](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.0/module/spring-boot-micrometer-metrics/src/main/java/org/springframework/boot/micrometer/metrics/autoconfigure/MeterRegistryPostProcessor.java); [composite counters forward increments](https://raw.githubusercontent.com/micrometer-metrics/micrometer/main/micrometer-core/src/main/java/io/micrometer/core/instrument/composite/CompositeCounter.java).

8. **Low — Closed registries and their listeners accumulate for the factory’s lifetime.**  
   [PlatformRestClientAutoConfiguration.java:85](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfiguration.java:85), [DefaultPlatformRestClientFactory.java:205](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:205).

   **Trigger:** Bind temporary/replacement registries, close them, and retain the application factory.

   **Consequence:** The identity set and captured listener references retain every registry. Closing a registry removes neither reference nor listener, so memory retention and per-attempt listener traversal grow with registry churn. A fixed set of application-lifetime registries does not exhibit this growth.

9. **Medium — D5 still misclassifies genuine resets.**  
   [ResilientRequestFactory.java:389](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:389).

   **Trigger:** A GET body resets at approximately 999 ms under a one-second deadline and a longer read timeout.

   **Consequence:** The reset falls inside the two-millisecond margin and becomes `DEADLINE`/504 instead of `CONNECTION`/503. Discovering an earlier reset after expiry also reproduces it. The stated truncation rationale explains tolerating an early timer, but supplies no evidence that this particular exception came from that timer. This still does **not** make an applied POST retryable.

10. **Medium — Reading the networking file adds startup failures unrelated to unsafe retries.**  
    [DefaultPlatformRestClientFactory.java:83](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:83), line 90.

    **Trigger:** A readable `net.properties` contains a malformed Unicode escape in an unrelated property, with no all-method retry setting. `Properties.load()` throws `IllegalArgumentException`. Alternatively, a nonempty whitespace-only **system-property** value is rejected even though the JDK evaluates it as false.

    **Consequence:** Factory construction fails although all-method retry is disabled. The JDK ignores file-loading exceptions and treats nonempty values using `Boolean.parseBoolean`; the guard implements different semantics. An ordinary missing/unreadable file, including an omitted file in a jlink image, is skipped correctly. [JDK loading behavior](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/share/classes/sun/net/NetProperties.java), [flag interpretation](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/MultiExchange.java).

The scenario-gap dispositions are:

| Gap | Assessment | Remaining concrete defect, trigger and consequence |
|---|---|---|
| AC5: exact 8 KiB | **Right** | The counting-stream test asserts exactly 8192 application bytes read. |
| AC6: date-form waiting | **Partial — Low** | `ResilienceBehaviourTest.java:651–659`: the date is calculated before client setup and timing begins. A two-second scheduling/setup pause can leave under one second to wait, making correct behavior fail the `>1s` assertion. Actual waiting is now exercised. |
| AC8: half-open | **Partial — Medium** | `phase-06-rest.sh:238–239`: receipt is observed, but the response is released after a fixed 1500 ms. A delayed scrape observes `CLOSED` and fails against correct code. The slice does establish half-open rejection while its stream remains open. |
| AC12: order and permits | **Partial — Medium** | `ResilienceBehaviourTest.java:697–706,710–723`: removing bulkhead acquisition entirely still passes the permit test; retrying local breaker refusals still produces only one downstream request. These assertions cannot detect the claimed regressions. |
| AC13: retry interaction | **Partial — Medium** | `RestClientRetryInteractionTest.java:29–32,59–62`: exceptions remain synthetic; no platform request executes inside `RetryableOperation`. An unexpected exception escaping a real applied POST—finding 1—passes these tests. The explicit exclusion of aspect execution is accepted. |
| AC16: body consumption | **Partial — Medium** | `ResilienceBehaviourTest.java:746–748`: asserting `RestClientException` plus root `IOException` does not establish an intervening `RemoteCallException`. Removing body-failure classification can still pass. The consumer’s generic 500 assertion also cannot distinguish that regression. |
| AC15: separate shutdown cases | **Partial — Medium** | `phase-06-rest.sh:301–303,323–325`; `stub.py:68–80`: request count increments **before** response headers. Count 1 does not prove the first call has entered backoff. For the second case, count remains 1 after completion, so Docker/scheduling delay can move SIGTERM past completion and still pass. Transport termination remains unasserted. |
| §7.7: disclosure/matrix | **Partial — Medium** | The generic-detail assertion is fixed. However, `phase-06-rest.sh:255–259` checks bulkhead rejection status and timing only; wrong `retryable`/detail values pass, and no POST/PATCH bulkhead rejection is exercised. |
| AC17: bulkhead capacity | **Right** | Capacity 60 accommodates the 30 concurrent requests. |
| §7.11: startup connection evidence | **Right — amended scope** | It now accurately describes recorded timings and construction-based reasoning. |
| R9/R10 wording | **Right** | Pair of transports and always-on deadline are explicit. |
| Previously reported R11 sample gap | **Partial — Low** | `scope.md:91` still requires `docs/samples/rest-resilience/`, which remains absent. Consumers following that requirement have no sample. |

The XML changes do not establish a new exponential-time defect by static inspection: `.{0,8192}?` is bounded but **does backtrack**, and repeated unterminated CDATA starters cause repeated suffix scans. No timing result is claimed. The patterns also remove complete CDATA delimiters and redact metadata such as `<tokenCount value="3"/>`; the confidentiality failures above are the blocking issues.

The 365-day slow-call threshold fits in nanoseconds. It effectively makes ordinary slow-call counts/rates zero as well as preventing slow-call opening; it is not a literal disabling of the rule. I found no separate practical overflow defect there.

VERDICT: REJECT
