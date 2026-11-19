I would not close Phase 0 yet. There are four High findings below, plus replacement and evidence gaps.

Reviewed `6666618..67521ae`; HEAD matches the reviewed commit. The diff contains 22 files, 2,121 insertions and 610 deletions. This was read-only: I inspected sources, POMs and framework documentation, but did not rerun Maven tests or consumer harnesses.

1. **High — Removing the validation starter also removes Bean Validation itself.**  
   **Location:** [plan.md:587](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/plan.md:587), [decisions.md:45](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/decisions.md:45).

   **Failure:** After Phase 1, the golden-path DTOs cannot compile without another validation API dependency; applications that retain only the API can start without enforcing their constraints.

   **Reasoning:** The runtime provider currently comes from [platform-starter-validation/pom.xml:35](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/validation/platform-starter-validation/pom.xml:35). In errors-autoconfigure, `jakarta.validation-api` is **optional**, and Boot’s validation starter is **test scope** ([pom.xml:59](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/errors/platform-errors-autoconfigure/pom.xml:59), lines 83–87). Neither errors’ exception mapping nor Boot’s web starter supplies the validator. Boot explicitly conditions validation on an implementation being present. [Spring Boot validation documentation](https://docs.spring.io/spring-boot/reference/io/validation.html).

   **Fix:** In Phase 1, replace the removed platform starter with `spring-boot-starter-validation` in the generated foundation and retained examples. Preserve ordinary Bean Validation assertions; drop only platform-specific constraints.

2. **High — The blanket bean-post-processor prohibition conflicts with a proven Oracle timeout guarantee.**  
   **Location:** [plan.md:190](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/plan.md:190), [plan.md:670](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/plan.md:670).

   **Failure:** An application supplies an unstarted Oracle `HikariDataSource`, or receives its JDBC URL through a late property source. Removing the existing post-processor restores unbounded driver waits; retaining it violates the new “user’s bean wins, unchanged” rule.

   **Reasoning:** [OracleConnectionDefaults.java:29](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java:29) implements `BeanPostProcessor` and adds driver properties at lines 45–50. This is the documented fix for **HI-0061**, with additional safeguards in **HI-0063** and timeout configuration in **HI-0070**. Phase 4 cannot simultaneously “confirm instead of rewrite,” retain that behaviour, and prohibit its implementation.

   **Fix:** Explicitly grandfather this bounded, non-overwriting processor, or assign Phase 4 a replacement design and parity tests. Distinguish Boot-created pools from user-created pools and explicitly decide any changed guarantee.

3. **High — An HTTP interceptor cannot prevent an outer `@Retryable` method from repeating POST.**  
   **Location:** [spikes.md:84](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/phase-00-baseline/spikes.md:84), [plan.md:837](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/plan.md:837).

   **Failure:** A retry-annotated service method POSTs an order. The downstream commits it, but the response connection fails. The interceptor performs no retry; nevertheless, the method advice invokes the entire method again, creating another order.

   **Reasoning:** S4 proves exception inclusion/exclusion and attempt counts, not HTTP safety. Spring retries the **method invocation**. The existing resilience defaults deliberately exclude `RemoteCallException` to prevent this second retry layer ([PlatformResilienceEnvironmentPostProcessor.java:39](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/resilience/platform-resilience-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/resilience/autoconfigure/PlatformResilienceEnvironmentPostProcessor.java:39), lines 59–64). Removing that exception and enabling native retry requires an equivalent exclusion policy. [Spring Framework retry semantics](https://docs.spring.io/spring-framework/reference/core/resilience.html).

   **Fix:** Replace the interceptor-only mitigation with an explicit outer-retry exclusion policy, or constrain retries to the transport layer. Add a test where a POST takes effect and its response is lost; assert exactly one downstream invocation under every supported retry mechanism.

4. **High — Phase 1’s deletion and historical-record rules make the docs gate fail.**  
   **Location:** [plan.md:596](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/plan.md:596), lines 599–602.

   **Failure:** Deleting `docs/samples/security/` breaks links in historical records that Phase 1 explicitly prohibits editing.

   **Reasoning:** The existing link checker scans those records; its exclusion covers only `codex/*.md` ([DocsLinkCheckTest.java:31](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/DocsLinkCheckTest.java:31)). A read-only simulation of the planned deletions found these two definite failures:
   - [phase-07-security/closure.md:38](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/production-readiness/phase-07-security/closure.md:38) → `samples/security/operations.md`
   - [phase-07-security/consumer-run.md:79](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/production-readiness/phase-07-security/consumer-run.md:79) → `samples/security/verification.md`

   **Fix:** Define historical-link handling before deletion: retain archival targets, resolve historical records against their tagged tree, or explicitly permit converting those links to immutable tag URLs.

5. **Medium — `RequestContextHolder` is not an equivalent replacement for `RequestContext`, and S7 supplies no evidence for it.**  
   **Location:** [removal-map.md:12](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/phase-00-baseline/removal-map.md:12).

   **Failure:** A Rabbit listener or outbox drainer has no servlet request attributes. A context-free task executed inside another caller’s thread can also retain the wrong MDC unless the existing isolation semantics are rebuilt.

   **Reasoning:** `RequestContextHolder` holds web-request attributes; it does not provide the platform’s trace/extras/MDC scope semantics. [Framework API](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/web/context/request/RequestContextHolder.html). The existing [RequestContextThreadLocalAccessor.java:18](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java:18) deliberately propagates an explicit empty context and restores the previous scope—fixes **HI-0016/HI-0023**. S7 instead inserts a constant trace ID ([S7ProblemDetailTest.java:68](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/phase-00-baseline/spikes/src/test/java/spikes/S7ProblemDetailTest.java:68)).

   **Fix:** Mark this replacement unproven. Specify Micrometer tracing/context-propagation and any remaining internal MDC adapter separately; port the empty-context, nested-scope, async/error-dispatch and listener/drainer tests before deleting the API.

6. **Medium — A second `@RestControllerAdvice` does not replace the post-enrichment `ProblemDetailCustomizer` contract.**  
   **Location:** [removal-map.md:13](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/phase-00-baseline/removal-map.md:13).

   **Failure:** A service advice handles an exception to add its own property. The platform’s exception handler then does not execute, so its code, trace and sanitization pipeline is bypassed.

   **Reasoning:** The current [ProblemDetailCustomizer.java:18](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/errors/platform-errors-api/src/main/java/ae/gov/dubaicustoms/platform/errors/ProblemDetailCustomizer.java:18) promises an already-populated body and originating exception. Exception-handler advice selects a handler; it does not compose handlers into successive decorators. **HI-0014** specifically fixed preservation of the originating exception. S7 tests only one advice. [Spring MVC advice documentation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html).

   **Fix:** Name and test a composable Spring extension mechanism, including originating-exception access and framework/error-dispatch coverage, or retain a narrowly scoped internal extension contract until parity is demonstrated.

7. **Medium — `RestClientCustomizer` loses the existing client-name parameter.**  
   **Location:** [removal-map.md:17](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/phase-00-baseline/removal-map.md:17), [plan.md:826](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/plan.md:826).

   **Failure:** Porting an orders-only credential/header customizer directly to a global Boot customizer applies it to unrelated clients.

   **Reasoning:** [PlatformRestClientCustomizer.java:38](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/restclient/platform-restclient-api/src/main/java/ae/gov/dubaicustoms/platform/restclient/PlatformRestClientCustomizer.java:38) receives `(name, builder)`; Boot’s interface receives only the builder. S3 contains one group and applies the same header everywhere. [Boot customizer API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/restclient/RestClientCustomizer.html).

   **Fix:** Map named clients explicitly to HTTP service groups and their group configurer; define selection for directly injected builders. Test two downstreams with distinct credentials and verify isolation. [Boot group customization](https://docs.spring.io/spring-boot/reference/io/rest-client.html).

8. **Medium — S3 passes even if the configured read timeout is ignored.**  
   **Location:** [S3HttpServiceClientTest.java:61](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/phase-00-baseline/spikes/src/test/java/spikes/S3HttpServiceClientTest.java:61), lines 83–85; [spikes.md:69](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/phase-00-baseline/spikes.md:69).

   **Failure:** A property-binding regression removes the group timeout, but this spike remains green because its server answers immediately.

   **Reasoning:** The assertions prove the base URL and global header customizer. Neither observes a timeout.

   **Fix:** Narrow the recorded verdict accordingly, or add a delayed-response case with a distinguishing elapsed-time bound and timeout exception assertion.

9. **Medium — The dependency inventory is not a v2 scope-cut inventory.**  
   **Location:** [inventory.md:48](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/phase-00-baseline/inventory.md:48), [depmap.py:7](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/phase-00-baseline/evidence/depmap.py:7).

   **Failure:** Re-running the supplied scanner still omits security, validation, events, scheduling, and non-Redis cache/locking dependencies. Its output cannot establish that the expanded cut is safe.

   **Reasoning:** Both regexes encode the earlier cut. The inventory reports eight cut docs dependencies; there are now **15**. It also lists cache/locking modules as kept even though v2 deletes them.

   **Fix:** Derive the cut artifact set from an explicit v2 module manifest, inspect all dependency scopes and Java references, and regenerate the inventory. The concrete references are listed below.

10. **Low — Several factual references still describe the superseded plan.**  
    **Locations and fixes:**
    - [plan.md:86](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/plan.md:86): `security/*` contains **seven**, not eight, module POMs.
    - [plan.md:591](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/plan.md:591): `example-event-driven` uses in-memory/Rabbit profiles, not Kafka or the platform events module. Its producer POM declares them at lines 79/90; consumer at 71/80. Apply the stated Gate A retention condition using those actual dependencies.
    - [plan.md:792](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/plan.md:792): **HI-0099 concerns malformed successful HTTP-response conversion leaking secrets**, not open-path validation. Correct the reference and explicitly associate that regression with Phase 9.
    - [spikes.md:42](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/spring-native/phase-00-baseline/spikes.md:42): the Modulith trial is now Phase **5**, security Phase **8**, and REST resilience Phase **9**. Several subsequent “not yet checked” references retain the old numbering.

    **Failure scenario:** An implementer follows the wrong phase or issue identifier and misses the intended test or unnecessarily deletes an example.

For **question 1**, the row-by-row assessment is:

| Removal-map row | Assessment |
|---|---|
| Core | Partial. `Tracer` is appropriate for trace identity; `RequestContextHolder` is not context-propagation parity. S7 does not test core. No stock replacement is identified for capability descriptors/banner aggregation. |
| Errors | Spring exception/status types are valid building blocks. Platform enrichment, sanitization, code uniqueness and customization still require implementation. Finding 6 applies. |
| Data value types | Explicitly dropped by ND7; no replacement claim to validate. |
| Logging | SLF4J fluent key-values and Boot’s logging-customization hook are real. S5 proves its narrow message-masking example; it does **not** prove the existing redaction suite, which the document correctly defers. |
| Messaging | Spring AMQP supplies the primitives, not all existing semantics. In particular, ordinary `RabbitTemplate.send/convertAndSend` does not wait for confirms. Phase 6 must preserve the bounded confirm/return handling currently in `RabbitEventTransport`; simply enabling confirms is insufficient. [Spring AMQP documentation](https://docs.spring.io/spring-amqp/reference/amqp/template.html). S1 is only outbox feasibility evidence. |
| REST client | Builder/group configuration is real. Findings 7–8 apply. Spring exceptions alone do not preserve bounded redaction, failure-kind classification or safe-repeat metadata from `RemoteCallException`; Phase 9 must supply those behaviours. |
| Resilience | S4 proves native retry inclusion/exclusion. Resilience4j registries provide a viable programmatic replacement. Native retry/concurrency annotations alone do not replace named policies, circuit breaking and deadlines; finding 3 applies. |
| Test support | Selective retention, not a demonstrated replacement. No additional finding against that decision. |

S2’s narrow default-chain claim is supported by its test and [Spring Security’s documented customizer mechanism](https://docs.spring.io/spring-security/reference/7.0/servlet/configuration/java.html). S6 proves its particular proxy/method cases, but its enumeration test visits only one bean’s declared methods—not every inherited or class-guarded method. Since v2 explicitly stops relying on S6, that is not a retained-capability blocker.

For **question 2**, these are the actual retained/parked-to-cut dependencies. An optional Maven dependency remains a dependency needed to compile its declaring module.

| Module | POM references |
|---|---|
| Data JPA autoconfigure | [pom.xml:43](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/data/platform-data-jpa-autoconfigure/pom.xml:43): optional `platform-security-api`. |
| Messaging autoconfigure | [pom.xml:54](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/messaging/platform-messaging-autoconfigure/pom.xml:54): optional `platform-messaging-kafka`; line 66: optional `spring-kafka`. |
| Outbox autoconfigure | [pom.xml:56](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/messaging/platform-messaging-outbox-autoconfigure/pom.xml:56): optional `platform-security-api`. |
| Parked REST client autoconfigure | [pom.xml:43](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/restclient/platform-restclient-autoconfigure/pom.xml:43): optional `platform-security-api`; line 114: **test-scope `platform-security-autoconfigure`**. |
| Archetype template | [pom.xml:57](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/tooling/platform-service-archetype/src/main/resources/archetype-resources/pom.xml:57): validation starter; line 69: security starter. |
| Golden-path example | [pom.xml:42](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/examples/example-golden-path/pom.xml:42): validation; 46: security; 70: cache-caffeine; 78: audit starters. |
| Extension-provider example, if retained | [pom.xml:36](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/examples/example-extension-provider/pom.xml:36): storage-fs starter; line 42: storage TCK. |
| Docs | [pom.xml:112](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/platform-docs/pom.xml:112): validation; 116 security; 117 authz; 119 events; 121 cache; 122 Redis; 124 locking; 125 scheduling; 126 idempotency; 127 storage; 128 audit; 129 audit-messaging; 130 ratelimit; 131 files; 132 flags. All are test-scope generator-ordering dependencies. |
| BOM | [pom.xml:46](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/build/platform-bom/pom.xml:46): validation 46–48; security/authz 56–62; Kafka 67/72; events 76–78; cache/Redis 83–88; locking/scheduling 93–101; idempotency/storage/flags/audit/ratelimit/files 102–133; deleted TCKs 138–143. |

The corresponding Java references requiring removal or adaptation are:

| Area | Files and lines |
|---|---|
| Data | [PlatformDataJpaAutoConfiguration.java:9](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfiguration.java:9), also 77, 138, 143: `CurrentUserAccessor` import, class-name condition and bean signature. [AuditorAwareProvider.java:3](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/AuditorAwareProvider.java:3), also 4, 21, 26, 35: `CurrentUser`/accessor adapter. |
| Outbox | [OutboxAutoConfiguration.java:15](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxAutoConfiguration.java:15), also 133–140: security-conditioned principal source. [OutboxContextWiringTest.java:16](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/messaging/platform-messaging-outbox-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxContextWiringTest.java:16), also 17, 65, 68: security fixtures. |
| Parked REST client | [PlatformRestClientAutoConfiguration.java:8](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/restclient/platform-restclient-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfiguration.java:8), also **59 (`afterName`)**, 126: security import, ordering and condition. [PlatformRestClientAutoConfigurationTest.java:7](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/restclient/platform-restclient-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/restclient/autoconfigure/PlatformRestClientAutoConfigurationTest.java:7), also 8–9, 190–207: security API and actual auto-configuration fixtures. |
| Messaging production code | [PlatformMessagingAutoConfiguration.java:13](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingAutoConfiguration.java:13), also 34–35, 151–164: Kafka transport/template/factory and nested configuration. [MessagingNoTransportFailureAnalyzer.java:30](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/MessagingNoTransportFailureAnalyzer.java:30) recommends the deleted starter. |
| Messaging tests | [ProviderTransportWiringTest.java:9](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/messaging/platform-messaging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/ProviderTransportWiringTest.java:9), also 18–19, 32–47, 60, 87–92; [RabbitBootWiringTest.java:14](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/messaging/platform-messaging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/RabbitBootWiringTest.java:14), also 29/42; [PlatformMessagingDefaultsTest.java:25](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/messaging/platform-messaging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingDefaultsTest.java:25), also 43; [PlatformMessagingAutoConfigurationTest.java:15](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/messaging/platform-messaging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingAutoConfigurationTest.java:15), also 68. Kafka types remain compile-time dependencies even in filtered-classloader tests. |
| Golden-path Java | [OrderService.java:3](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/examples/example-golden-path/src/main/java/ae/gov/dubaicustoms/example/goldenpath/orders/OrderService.java:3), also 43: `Audited`; lines 8/60 retain the Spring cache annotation whose platform configuration is cut. |
| Extension-provider Java, if retained | [EncryptingStorageAutoConfiguration.java:3](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/examples/example-extension-provider/src/main/java/ae/gov/dubaicustoms/example/extension/EncryptingStorageAutoConfiguration.java:3), lines 3–5; [EncryptingFsObjectStore.java:3](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/examples/example-extension-provider/src/main/java/ae/gov/dubaicustoms/example/extension/EncryptingFsObjectStore.java:3), lines 3–8; [StorageBackOffTest.java:5](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/examples/example-extension-provider/src/test/java/ae/gov/dubaicustoms/example/extension/StorageBackOffTest.java:5); [EncryptingFsObjectStoreTckTest.java:3](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/examples/example-extension-provider/src/test/java/ae/gov/dubaicustoms/example/extension/EncryptingFsObjectStoreTckTest.java:3), lines 3–4. |

Additional tooling references are real migration work, although several are strings rather than binary dependencies:

- [platform-service-parent/pom.xml:79](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/build/platform-service-parent/pom.xml:79), lines 79–80 and 91–93: dependency bans direct users toward deleted Kafka/storage starters.
- [adopt-platform.yml:10](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/tooling/platform-migrations/src/main/resources/META-INF/rewrite/adopt-platform.yml:10), lines 10–14: rewrites a dependency to the deleted Kafka starter.
- [PlatformDocs.java:55](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/PlatformDocs.java:55): cut-starter mappings at 55, 59–60, 62, 65, 68–70 and 72–81.
- [platform-test-api/pom.xml:121](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/test/platform-test-api/pom.xml:121) and [Containers.java:5](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/container/Containers.java:5), also 44–45/66: optional Kafka Testcontainers dependency and public helper. Redis/Vault/LocalStack helpers remain at 47/51/55 and 75/94/103.
- [PlatformUsageRules.java:41](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/arch/PlatformUsageRules.java:41), also 76/99: Kafka matching and the rule prohibiting the newly intended Spring messaging APIs.
- [PlatformMcpToolsTest.java:60](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/tooling/platform-mcp-server/src/test/java/ae/gov/dubaicustoms/platform/mcp/PlatformMcpToolsTest.java:60) and [platform-index.json:10](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/tooling/platform-mcp-server/src/test/resources/platform-index.json:10), also 17: fixtures expect deleted Kafka/cache starters.

I found **no direct Phase 1 cut-module dependency** in core, errors, logging, observability, Rabbit transport, outbox implementation, messaging-test, starter-test, example-minimal, parked OpenAPI or parked resilience. Errors has the transitive validation-provider problem in finding 1. Build tests also contain synthetic cut-artifact names and locally defined validation-package fixtures; those are not dependencies on the deleted artifacts.

For **question 3**, I found **no additional wholly unassigned `FIXED`/`ACCEPTED` guarantee** once the blanket carry-over requirement at plan lines 462–468 is applied. That rule does cover guarantees omitted from the short phase lists. However, findings 1–3 show where the concrete instructions undermine that promise; finding 5 identifies an unproven replacement for HI-0016/HI-0023, and finding 6 affects HI-0014. HI-0099 should be explicitly attached to Phase 9, not mistaken for a security-path check.

For **question 4**, findings 2 and 4 are definite contradictions, and finding 10 contains factual corrections. I found **no impossible consumer-count floor**: plan lines 209–211 and 610–612 explicitly permit enumerated scope-related drops. Parking also does not exempt modules from foundation migrations: for example, [RemoteCallException.java:36](C:/Users/Ali/AppData/Local/Temp/codex-p0r1/restclient/platform-restclient-api/src/main/java/ae/gov/dubaicustoms/platform/restclient/RemoteCallException.java:36) still extends the `PlatformException` scheduled for Phase 2 deletion, so that downstream adaptation must occur in Phase 2 even though the REST-client rewrite is Phase 9.
