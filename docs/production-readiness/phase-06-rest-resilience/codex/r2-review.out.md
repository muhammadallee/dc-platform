Reviewed `1162d99..471db62`, including surrounding code and dependency sources. Static review only; no JVM or Docker execution. The excluded issue IDs are not reported.

**A. Round 1 dispositions**

“Right” below means the claimed fix addresses the original finding. New regressions are assessed separately.

| # | Assessment | Result at HEAD |
|---|---|---|
| 1 | **Partial** | Redirect suppression is correct, but `DefaultPlatformRestClientFactory.java:66` checks only system properties. The JDK also reads `conf/net.properties`; see B1. |
| 2 | **Right** | POST/PATCH cannot follow the redirect that produced the misleading `ConnectException`. |
| 3 | **Right** | The positive, case-sensitive method allowlist fixes custom methods and lowercase `post`. |
| 4 | **Right** | Closing the returned stream closes its owning response and releases the permit once. |
| 5 | **Right** | Comparing `Duration` values avoids the reported `Retry-After` nanosecond overflow. |
| 6 | **Right** | Bulkhead acquisition no longer waits. |
| 7 | **Partial** | `ResilientRequestFactory.java:96–98` still buffers without a size bound. With interceptors, creation occurs after initial buffering and interceptor work, so even elapsed-time accounting remains incomplete; B2. |
| 8 | **Right** | The broader exclusion catches Spring’s JSON-body wrapper under the default retry configuration. The original AC16 wording remains inconsistent with this amended contract. |
| 9 | **Right** | Observed body-read failures now reach the breaker. Atomic verdict ownership prevents duplicate accounting. There is a separate duration regression in B6. |
| 10 | **Partial** | The listed Basic, escaped-quote, simple XML and header-line examples are fixed. Literal XML secrets still escape at `RemoteCallException.java:67`; B3. Encoded names also remain the stated residual. |
| 11 | **Right** | The reported cubic pattern was replaced, and redaction fails closed on `StackOverflowError`. This is not a claim that I measured its execution time. |
| 12 | **Right** | Both observation keys now remove query strings and userinfo. |
| 13 | **Partial** | `ResilientRequestFactory.java:256` retains query text following whitespace and leaves fragments untouched; B4. |
| 14 | **Partial** | `ResilientRequestFactory.java:385` guesses from the clock, misclassifying genuine connection failures near expiry; B5. |
| 15 | **Right** | A rejected retry wait closes the discarded response and throws without reading its snippet. |
| 16 | **Right** | The REST starter now supplies `resilience4j-micrometer`. |
| 17 | **Partial** | `PlatformRestClientAutoConfiguration.java:83–86` replaces duplicate binding with “first registry wins”; supported additional registries receive nothing. B7. |
| 18 | **Right** | All transports share one five-second termination-wait budget. |
| 19 | **Right** | New live transports cannot escape destruction. The race cleanup can, however, abort existing calls prematurely; B9. |
| 20 | **Right** | Both defaults and client overrides reject zero/negative connect and read timeouts. |
| 21 | **Partial** | Metrics-only configuration is accepted, but `PlatformResilienceAutoConfiguration.java:55–59` misses the supported `backends` alias; B8. |

**B. Remaining defects and regressions**

1. **High — The JDK retry guard misses an effective configuration source.**  
   [DefaultPlatformRestClientFactory.java:66](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:66)

   **Trigger:** Set `jdk.httpclient.enableAllMethodRetry=true` in the runtime’s `conf/net.properties`, without a corresponding system property. Reuse a connection for POST, then have the server process the request and disconnect before returning headers.

   **Consequence:** Startup succeeds, but the JDK can resend the POST independently of the platform retry loop. The direct transport’s redirect policy does not prevent this. The JDK obtains this setting through `Utils.getNetProperty`, which falls back to the networking properties file. [JDK retry implementation](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/MultiExchange.java), [property lookup](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/share/classes/sun/net/NetProperties.java).

2. **High — The deadline still excludes work before the innermost request is created.**  
   [ResilientRequestFactory.java:98](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:98)

   **Trigger:** Install an interceptor, including the platform token relay, and use a slow upload callback or an interceptor that spends longer than the configured deadline before invoking `execution.execute`.

   **Consequence:** The platform request receives a fresh deadline afterwards and still sends. Moving the timestamp to this `createRequest` does not include Spring’s outer request buffering or preceding interceptor execution. A retrying interceptor also creates another inner request, resetting this budget on each invocation. Spring creates that delegate at the end of the interceptor chain. [Spring interceptor implementation](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-web/src/main/java/org/springframework/http/client/InterceptingClientHttpRequest.java).

   Without interceptors, the timestamp includes callback elapsed time, but does not interrupt a blocked callback or bound its buffer allocation. Ordinary repeated calls through a reused `RestClient` do correctly create independent requests.

3. **High — Common literal XML secret values survive redaction.**  
   [RemoteCallException.java:67](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-api/src/main/java/ae/gov/dubaicustoms/platform/restclient/RemoteCallException.java:67)

   **Trigger:** An error body contains either:

   ```xml
   <password><![CDATA[hunter2]]></password>
   <password value="hunter2"/>
   ```

   **Consequence:** `hunter2` remains in `bodySnippet()`. For CDATA, `XML_VALUE` stops immediately at `<`; for the attribute form, the secret is inside the opening-tag capture that replacement preserves. `SECRET_VALUE` does not remove either value. These require neither encoded secret names nor unusual names, and the consumer probe explicitly logs the resulting snippet.

4. **Medium — Sanitized transport messages still disclose malformed URL content.**  
   [ResilientRequestFactory.java:256](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:256)

   **Trigger:** An idempotent request receives a redirect such as `Location: http://example.test/?q=public SECRET`, or an invalid fragment such as `http://example.test/#SECRET%ZZ`.

   **Consequence:** URI parsing quotes the malformed target in the exception. `\?\S*` removes only through the first whitespace, leaving `SECRET`; a fragment without `?` is untouched. The retained cause reaches the platform exception log. The JDK wraps response-filter failures into `IOException`, making this a reachable sanitization path. [JDK response-filter error handling](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/MultiExchange.java).

   Classification itself does **not** lose the original exception type: its `instanceof` checks use `e`, not the rebuilt cause. A null message is handled correctly.

5. **Medium — A connection reset can become a deadline failure before the deadline.**  
   [ResilientRequestFactory.java:385](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:385)

   **Trigger:** With a one-second deadline and a longer read timeout, the server resets a partially delivered success body at approximately 950 ms.

   **Consequence:** The remaining 50 ms falls inside the 100 ms margin, so a genuine `CONNECTION` failure becomes `DEADLINE`, producing 504 instead of 503 for GET. With timeouts below 100 ms, essentially any body I/O failure falls inside this window. A delayed consumer can likewise discover an earlier reset after expiry and receive the wrong kind.

   This does **not** make an applied POST retryable: `idempotent=false` still produces 500 and `retryable=false`. The defect is classification, not duplicate authorization.

6. **Medium — Deferred verdict duration introduces an undocumented breaker-opening condition.**  
   [DefaultPlatformRestClientFactory.java:131](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:131), [ResilientRequestFactory.java:394](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilientRequestFactory.java:394)

   **Trigger:** Complete successful streamed downloads, but close their streams more than 60 seconds after each request began—for example, after processing the downloaded data. Repeat for the configured minimum calls.

   **Consequence:** Verdict duration includes the caller’s delay before close. The breaker configuration retains Resilience4j’s default slow-call policy: 60 seconds and a 100% slow-call threshold. It can therefore open with zero failed remote calls, contrary to R5’s stated failure policy. Longer configured successful downloads have the same problem. [Resilience4j defaults](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.4.0/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/CircuitBreakerConfig.java).

7. **Medium — First-only metrics binding drops supported registry destinations.**  
   [PlatformRestClientAutoConfiguration.java:83](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfiguration.java:83)

   **Trigger:** Supply two independent user-defined composite registries, each with its own exporter, and let Boot bind meters.

   **Consequence:** Only the first composite gets breaker, bulkhead and attempt meters. Boot explicitly supports applying binders to each user-defined composite when those are the available composites. A single Boot-managed composite works correctly; this is not a claim that its children are ordinarily bound twice. The new test actually asserts that the second registry remains empty. [Boot registry binding](https://raw.githubusercontent.com/spring-projects/spring-boot/main/module/spring-boot-micrometer-metrics/src/main/java/org/springframework/boot/micrometer/metrics/autoconfigure/MeterRegistryPostProcessor.java).

8. **Medium — The thread-pool guard misses the supported `backends` alias.**  
   [PlatformResilienceAutoConfiguration.java:55](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/resilience/platform-resilience-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/resilience/autoconfigure/PlatformResilienceAutoConfiguration.java:55)

   **Trigger:** Configure virtual threads and `resilience4j.thread-pool-bulkhead.backends.orders.core-thread-pool-size=2`.

   **Consequence:** Neither checked subtree is present, so startup proceeds with a configured thread-pool bulkhead. Resilience4j exposes `backends` as an alias of its instances map. This violates R6.2. Ordinary relaxed spellings of `thread-pool-bulkhead` are not the problem; the missing subtree is. [Resilience4j alias](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.4.0/resilience4j-framework-common/src/main/java/io/github/resilience4j/common/bulkhead/configuration/CommonThreadPoolBulkheadConfigurationProperties.java).

9. **Medium — A builder/shutdown race aborts existing calls before their grace period.**  
   [DefaultPlatformRestClientFactory.java:118](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/DefaultPlatformRestClientFactory.java:118)

   **Trigger:** `builder("orders")` passes the first closed check and obtains an existing shared transport. Concurrently, `destroy()` sets `closed=true` and starts its graceful wait. The builder then reaches the second check.

   **Consequence:** The builder calls `shutdownNow()` on that existing transport, cancelling other calls immediately instead of allowing the shared five-second grace period. Cleanup needs to distinguish a newly created transport missed by destruction from an existing transport already being drained.

The other requested edge checks did not reveal additional concrete defects:

- **Closure:** The owned JDK response returns an already-held body stream; obtaining it does not initiate a body read. Fully completed bodies retain connection reuse. Abandoning an unread real body may sacrifice HTTP/1 connection reuse, which is the expected cost of cancellation. Empty responses, 204, HEAD, and `toBodilessEntity()` still reach response closure. Empty-body introspection returns a non-streaming null result, so Spring closes the response; its pushback wrapper does not strand the permit. A subsequent `getBody()` wraps the same closed stream. The helper catches an accessor exception and still invokes response closure, although the concrete JDK accessor does not throw one. Intermediate followed redirects are consumed inside the JDK; direct 3xx responses use the ordinary guarded closure path. [Spring response closure](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-web/src/main/java/org/springframework/http/client/JdkClientHttpResponse.java), [JDK stream completion](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/ResponseSubscribers.java).
- **Transport selection:** Selection uses the effective method received after interceptors. Customizers still receive one builder. Two transports mean two pools and selector threads per name, even when only one method family is used. Returning POST’s 3xx is explicitly documented and compatible with `retrieve()`’s default 4xx/5xx error predicate; it is not independently a defect. [Spring status handling](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-web/src/main/java/org/springframework/web/client/StatusHandler.java).
- **Deferred verdicts:** Unclosed responses retain bulkhead and half-open permissions by the stated ownership contract. Cross-thread reading does not depend on thread-local state. A 4xx alone records success; a failure reading its body records a transport failure, even though the status handler subsequently preserves the outward 4xx response classification. The literal `200` passed on close is merely the success branch selector. Discarded retry responses receive their verdict before closure and never create a pending guarded verdict.
- **Redaction mechanics:** Replacement strings are constants; captured `$` and `\` are not reparsed as replacement syntax. Header-line redaction precedes other patterns. The scheme pattern also removes ordinary prose—for example, `basic validation failed` becomes `[REDACTED] failed—so diagnostic fidelity is reduced. The original hostile-input pattern is gone; the six timing tests are not exhaustive performance proof.
- **Lifecycle and retry defaults:** Normal context destruction follows lifecycle shutdown, allowing web and messaging drains first. Calling `shutdownNow()` on an idle client is valid. A previously obtained builder cannot revive a stopped JDK transport. Broadly ignoring `RestClientException` also changes raw-client `@Retry` behavior, but this is declared in the changelog. An instance’s own settings normally inherit defaults unless overridden; absent classes do not leave an index hole. [Spring shutdown ordering](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.8/spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java), [retry configuration inheritance](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.4.0/resilience4j-framework-common/src/main/java/io/github/resilience4j/common/retry/configuration/CommonRetryConfigurationProperties.java).
- **Observations:** `uri` remains `none` without a template and preserves path placeholders with one. `http.url` retains the expanded path but loses query/userinfo. These overrides sanitize those attributes, not arbitrary exception messages; B4 remains relevant. [Spring observation convention](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.7/spring-web/src/main/java/org/springframework/http/client/observation/DefaultClientRequestObservationConvention.java).

**C. Scope and acceptance evidence**

These are findings about what the assertions establish, not claims that the tests were executed successfully.

| Requirement | Assessment |
|---|---|
| R1 | Classification remains incorrect in B5. The JSON-wrapper allowance is implemented but lacks a real JSON failure regression test. |
| R2 | Direct exception mappings implement the table; B5 can select the wrong mapping. Consumer coverage does not exercise the whole table. |
| R3 | B1 leaves a duplicate-send path. Date-form retry waiting and annotation interaction are not proved at their required level. |
| R4 | Not met for the interceptor/buffering paths in B2. Inbound propagation is correctly deferred. |
| R5 | B6 adds an unstated opening condition; B7 loses metrics for supported configurations. Half-open consumer evidence is incomplete. |
| R6 | Semaphore behavior and stream-close release have tests. The thread-pool prohibition is incomplete because of B8. |
| R7 | The inner loop has the stated ordering. The combined order/header/permit assertion required by AC12 is incomplete. |
| R8 | B3/B4 remain. Neither large-body test proves the exact 8 KiB read bound. |
| R9 | B9 affects graceful closure. Literal scope also says one transport per name; implementation creates two, and redirect behavior intentionally changed. |
| R10 | Positive timeout validation is present. The deadline has no enable switch despite “each with enabled”; `Defaults` exposes only a `Duration` at `RestClientProperties.java:84`. |
| R11 | Module docs and archetype changed. Required `docs/samples/rest-resilience/` is absent. |

| Contract | Assessment |
|---|---|
| §7.1 | Factory override/backoff has a slice test. |
| §7.2 | Default snapshot has a slice test. |
| §7.3 | Capability/default-on tests exist; per-feature behavioral switch coverage is incomplete, including no bulkhead-disabled behavior test. |
| §7.4 | Consumer scenarios exist and record responses; their timing assertions establish a loose total bound, not each attempt’s configured timeout. |
| §7.5 | Consumer explicitly checks readiness/liveness with the breaker open. |
| §7.6 | Consumer checks the same trace on all three retry attempts. |
| §7.7 | Incomplete consumer matrix and an ineffective generic-detail assertion; below. |
| §7.8 | B3/B4 remain; log/tag checks cover only selected markers and query forms. |
| §7.9 | Template/query handling has limited coverage; no varying-path/cardinality test establishes this whole contract. |
| §7.10 | Shutdown scenario does not establish all promised cases or transport termination; below. |
| §7.11 | Both startup times are recorded, but absence of startup connection attempts is not asserted. |

Concrete acceptance-test gaps:

1. **Medium — AC5 does not prove “at most 8 KiB read.”**  
   [phase-06-rest.sh:204](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/tooling/hardening/scenarios/phase-06-rest.sh:204), [ResilienceBehaviourTest.java:289](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilienceBehaviourTest.java:289)

   A regression reading substantially more than 8 KiB can pass: the consumer checks only that the server sent less than 64 MiB, while the slice allows nearly 8 MiB of allocation. Server bytes sent also include read-ahead and are not application bytes consumed.

2. **Medium — AC6’s HTTP-date form is only parser-tested.**  
   [ResilienceBehaviourTest.java:216](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilienceBehaviourTest.java:216)

   The test calls `retryAfter()` directly. A defect that parses the date correctly but fails to honor its delay during a real request would pass. Only the seconds form exercises actual waiting.

3. **Medium — AC8 does not observe half-open behavior or its metrics.**  
   [phase-06-rest.sh:228](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/tooling/hardening/scenarios/phase-06-rest.sh:228)

   The scenario configures one half-open permission, sleeps, makes a synchronous successful call, and then checks `CLOSED`. It never holds a trial response while checking `HALF_OPEN`, its metric, or rejection of excess concurrent trials.

4. **Medium — AC12 and AC13 lack their required integrated slice checks.**  
   [ResilienceBehaviourTest.java:389](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/internal/ResilienceBehaviourTest.java:389), [RestClientRetryInteractionTest.java:24](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/resilience/platform-resilience-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/resilience/autoconfigure/RestClientRetryInteractionTest.java:24)

   The header slice checks `X-Added`, not real token/trace propagation or permit acquisition per retry. Consumer trace checks do not supply the specified slice evidence. The retry interaction tests throw synthetic exceptions through `RetryableOperation`; no test invokes an actual `@Retry` proxy around a platform call, including a JSON-body failure.

5. **Medium — The labelled AC16 consumer probe does not read the success body.**  
   [RestProbe.java:70](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/tooling/hardening/scenarios/phase-06/probe/RestProbe.java:70), [phase-06-rest.sh:210](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/tooling/hardening/scenarios/phase-06-rest.sh:210)

   `/kind` uses `toBodilessEntity()`, and that invocation has no result assertion. It cannot prove a failure during successful-body consumption. The following `/get` request does test a String-body deadline, and a slice covers String-body failure, but neither tests the amended JSON wrapping behavior. Scope AC16 still forbids the wrapper that R1.1 now permits.

6. **Medium — AC15 does not prove SIGTERM during an active transport operation or client closure.**  
   [phase-06-rest.sh:285](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/tooling/hardening/scenarios/phase-06-rest.sh:285)

   One request sleeps in backoff, then performs its next send during web-server drain. A fixed one-second sleep does not synchronize SIGTERM with either state. There is no separate signal-while-send/body-read-is-active case and no assertion that transports terminate. The shared harness does genuinely assert exit 143 and the graceful-shutdown log; those parts are present.

7. **Medium — The §7.7 generic-detail assertion accepts disclosure.**  
   [phase-06-rest.sh:40](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/tooling/hardening/scenarios/phase-06-rest.sh:40)

   A detail containing `https://orders.internal/private` passes because it lacks the literal word `toxiproxy`. Thus a URL/body disclosure regression can pass this assertion. Consumer tests also omit several R2 branches, including 429/502/504 and POST/PATCH local breaker/bulkhead rejection.

8. **Medium — AC17 can fail against a correct default bulkhead.**  
   [phase-06-rest.sh:255](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/tooling/hardening/scenarios/phase-06-rest.sh:255)

   Thirty concurrent calls use `main`, whose bulkhead remains at the default 25, against a delayed downstream. If more than 25 overlap, legitimate rejections make the expected 30 downstream requests fail. The trace-isolation scenario needs capacity for its chosen concurrency.

9. **Low — The startup scenario cannot establish “no connection at startup.”**  
   [phase-06-rest.sh:298](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/tooling/hardening/scenarios/phase-06-rest.sh:298)

   A startup connection attempt that times out within the 180-second readiness allowance still passes. Recording healthy/down startup times is useful, but there is no assertion about attempted connections or additional startup delay.

The missing sample directory and deadline enable switch are also **Low** scope gaps at [scope.md:91](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/docs/production-readiness/phase-06-rest-resilience/scope.md:91) and [RestClientProperties.java:84](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/RestClientProperties.java:84): consumers cannot use the required sample or independently disable the deadline as specified.

VERDICT: REJECT
