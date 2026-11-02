Reviewed `6741388..12c8e4c` and surrounding code. Static review only; no JVM or Docker execution. The specified parked and accepted findings are excluded.

The final section’s fix claims assess as follows:

| Fix | Assessment | Result |
|---|---|---|
| 1: failing attempt listener | **Partial** | Successful outcomes and permits survive `RuntimeException`. Later listeners still lose the event: `DefaultPlatformRestClientFactory.java:108`, finding 4 below. |
| 2: XML secrets | **Partial** | Original examples are covered, but quoted delimiters and CDATA still defeat `RemoteCallException.java:69`; finding 2. |
| 3: cached JDK configuration | **Right — accepted residual** | Not re-reported. |
| 4: illegal headers and URL-bearing exceptions | **Right for the reported triggers** | Their credential-bearing messages are removed. The new runtime branch introduces finding 3; other exception disclosure paths remain below. |
| 5: observation fragments | **Right** | Both observation URI values lose query and fragment suffixes. |
| 6: transport publication versus destruction | **Right** | Creation/publication and the destruction snapshot exclude each other. |
| 7–8: overlapping/retained registries | **Right — accepted residuals** | Not re-reported. |
| 9: two-millisecond classification window | **Right — accepted residual** | Not re-reported. |
| 10: networking-property semantics | **Partial** | Whitespace handling is correct. The load-error branch incorrectly discards partially loaded properties: `DefaultPlatformRestClientFactory.java:85`; finding 1. |

The concrete production defects are:

1. **High — malformed `net.properties` can enable duplicate POSTs despite the guard.**  
   [DefaultPlatformRestClientFactory.java:85](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:85)

   **Trigger:** Start a fresh JVM, without the system-property override, with these lines in order:

   ```properties
   jdk.httpclient.enableAllMethodRetry=true
   unrelated=\uZZZZ
   ```

   `Properties.load()` inserts the first property before throwing on the second. The JDK catches the exception and retains the partially populated properties; this guard returns immediately and ignores them.

   **Consequence:** Construction succeeds while all-method retry is enabled. A POST applied on a reused connection whose response is lost can be resent by the JDK. This is a new cold-start bypass, independent of the accepted live-file-edit/cache residual. The claim that a load failure means the setting is off is incorrect. [JDK property loading](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/share/classes/sun/net/NetProperties.java), [incremental property insertion](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/share/classes/java/util/Properties.java), [HTTP retry policy](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/MultiExchange.java).

2. **High — XML redaction still exposes credentials and can erase unrelated following elements.**  
   [RemoteCallException.java:69](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-api/src/main/java/ae/gov/dubaicustoms/platform/restclient/RemoteCallException.java:69)

   **Triggers:** These are valid XML fragments:

   ```xml
   <password hint="/>">PRIVATE</password>
   <password><![CDATA[</x>PRIVATE]]></password>
   <token value="a>b"/><id>keep</id>
   ```

   **Consequence:** In the first, the self-closing alternative stops at `/>` inside the quoted attribute, leaving `PRIVATE`. In the second, the closing-tag lookahead stops inside CDATA, again leaving `PRIVATE`. Both secrets reach `bodySnippet()`.

   In the third, `>` inside the attribute is mistaken for the opening-tag boundary. Matching continues through `<id>keep`, destroying the following element’s diagnostic text. The new sibling-preservation test does not exercise this shape.

   The expression stops at **any** `</`, not the matching element’s closing tag. Its bounded lazy repetitions do backtrack, contrary to the comment, but static inspection does **not** establish a catastrophic-time or deadline-hang defect for the transport’s 8 KiB input.

3. **Medium — post-send runtime failures bypass the breaker verdict.**  
   [ResilientRequestFactory.java:152](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:152)

   **Trigger:** During a half-open GET trial, the downstream receives the request and responds with a redirect whose `Location` contains an illegal space. The JDK’s redirect filter throws from `URI.create`; Spring unwraps the asynchronous failure and rethrows its `RuntimeException`.

   **Consequence:** This branch labels a remote failure `invalid_request`, records no breaker failure, and calls `releasePermission()`. In half-open state that **restores the trial permission**. Repeated malformed redirects therefore admit repeated trials without reopening the breaker or advancing its completed-call count. Closed-state failures are likewise omitted.

   This does **not** leak the half-open permission or permanently refuse subsequent calls; it returns permission without a verdict. The comment “Nothing was sent” is demonstrably false. The replacement exception remains non-retryable, so this branch itself does not duplicate a POST. [JDK redirect processing](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/RedirectFilter.java), [Spring runtime propagation](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-web/src/main/java/org/springframework/http/client/JdkClientHttpRequest.java), [half-open permission release](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.3.0/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java).

4. **Medium — one failing registry suppresses healthy registries’ attempt metrics.**  
   [DefaultPlatformRestClientFactory.java:108](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:108), [ResilientRequestFactory.java:218](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:218)

   **Trigger:** Bind two independent registries. The first has a conflicting gauge named `platform.restclient.attempts` with the matching client/outcome tags; the second is healthy.

   **Consequence:** The first listener throws while registering its counter. `forEach` aborts, and the outer `report` catch swallows the exception. The second registry receives no event. Persistent conflicts suppress every matching event there.

   Catching around the complete fan-out preserves the call, but does not isolate listeners. This is separate from the accepted overlapping-exporter issue.

5. **High — transport exception sanitization still retains credential-bearing protocol text.**  
   [ResilientRequestFactory.java:283](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:283), also line 145.

   **Trigger:** An upstream returns a malformed status line such as:

   ```text
   HTTP/1.1 xxx token=PRIVATE
   ```

   The JDK throws `ProtocolException` quoting the status line.

   **Consequence:** Because the message contains none of `://`, `?`, or `#`, `sanitized()` retains the credential verbatim in the propagated exception. Additionally, `recordFailure(started, e)` receives the original exception before sanitization; with circuit-breaker DEBUG logging enabled, its raw message is logged. That latter path also defeats message dropping when the malformed status line contains a credential-bearing URL. [JDK status-line exceptions](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/Http1HeaderParser.java), [breaker exception logging](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.3.0/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java).

6. **High — JSON conversion errors bypass credential sanitization.**  
   [DefaultPlatformRestClientFactory.java:119](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:119), [ResilientRequestFactory.java:377](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:377)

   **Trigger:** A client reads a successful `application/json` response using `.body(Map.class)`, and the upstream accidentally omits quotes:

   ```text
   {"password":PRIVATE}
   ```

   **Consequence:** Jackson includes `PRIVATE` in its unrecognized-token exception. Spring retains that exception beneath `HttpMessageNotReadableException` and `RestClientException`. The status handler only sanitizes error-status bodies, and the stream wrapper only classifies read failures; neither protects this conversion failure. The credential therefore remains in the exception chain. Default retry exclusion prevents repetition, but does not prevent disclosure. [Jackson token diagnostics](https://raw.githubusercontent.com/FasterXML/jackson-core/jackson-core-3.0.4/src/main/java/tools/jackson/core/json/UTF8StreamJsonParser.java), [Spring conversion wrapping](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-web/src/main/java/org/springframework/http/converter/AbstractJacksonHttpMessageConverter.java), [RestClient cause retention](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-web/src/main/java/org/springframework/web/client/DefaultRestClient.java).

For the test-gap claims:

| Gap | Assessment | Remaining trigger and consequence |
|---|---|---|
| AC6 date margin | **Partial — Low** | `ResilienceBehaviourTest.java:651–659`: a five-second pause during client setup expires the precomputed date before timing starts. Correct behavior still fails the `>1s` assertion. |
| AC8 half-open observation | **Partial — Low** | `phase-06-rest.sh:237–242`: delay the scrape until after the five-second response completes; it observes `CLOSED` and fails correct code. The held-stream slice correctly proves half-open refusal. |
| AC12 order and permits | **Partial — Medium** | The outcome sequence at `ResilienceBehaviourTest.java:728` fixes the breaker-refusal assertion. The permit test at lines 697–706 still passes if retries bypass permit acquisition; it does not observe acquisition/release per attempt. |
| AC13 real POST inside retry | **Right** | A real POST returning 503 now executes inside the real `RetryableOperation`; downstream count must remain one. The aspect exclusion remains explicit. |
| AC16 nested failure | **Right** | The assertion now requires `RemoteCallException` in the captured exception trace. |
| AC15 shutdown states | **Partial — Medium** | `phase-06-rest.sh:315–318,338–340`: receipt precedes response emission (`stub.py:68–80`). Pausing the stub after receipt can put SIGTERM before backoff; delaying `docker stop` beyond four seconds can put it after the in-flight response. Both can still pass. Longer sleeps/timeouts do not establish the claimed states. |
| §7.7 refusal bodies and POST bulkhead refusal | **Right** | Every observed refusal body is checked; the new slice exercises a POST against a full bulkhead. |
| Transport termination | **Right — narrowed claim** | Only clean process exit is claimed; transport termination remains unasserted. |
| R11 sample, previously outstanding | **Partial — Low** | `scope.md:91` still requires `docs/samples/rest-resilience/`, which is absent. Following the documented sample requirement produces no sample. |

The unchanged AC5, AC17, §7.11 and R9/R10 dispositions remain right.

The lifecycle synchronization introduces no demonstrated defect: every access to factory `closed` uses the same monitor, so removing `volatile` is valid. `computeIfAbsent` and the destruction snapshot are mutually exclusive, and shutdown waits occur outside the monitor.

Dropping an entire message containing `://`, `?`, or `#` loses diagnostic detail but is conservative for those messages; it does not change classification. The concrete confidentiality failures are the uncovered paths above.

VERDICT: REJECT
