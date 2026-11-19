**Found two High and four Medium issues.** Review was read-only; I did not execute Maven or Docker tests.

1. **High — Consumer conformance still requires the removed Kafka abstraction.**  
   [PlatformUsageRules.java:96](/C:/Users/Ali/AppData/Local/Temp/codex-p1r1/test/platform-test-api/src/main/java/ae/gov/dubaicustoms/platform/test/arch/PlatformUsageRules.java:96)

   A generated service that adds `spring-kafka` and injects `KafkaTemplate` passes the newly relaxed dependency ban but fails its generated `PlatformConformanceTest`. This rule still rejects Kafka templates/listener containers and instructs developers to use `EventPublisher`/`@EventHandler`, whose Kafka provider was deleted. The test at `PlatformUsageRulesTest.java:81` explicitly preserves that rejection.

   **Fix:** remove Kafka from the conformance predicate, explanatory messages and matching tests. Retain the RabbitMQ restriction until its planned rewrite.

2. **High — Retained consumer-repository preparation scripts select deleted modules.**  
   [phase-04-repo.sh:8](/C:/Users/Ali/AppData/Local/Temp/codex-p1r1/tooling/hardening/phase-04-repo.sh:8), [phase-06-repo.sh:8](/C:/Users/Ali/AppData/Local/Temp/codex-p1r1/tooling/hardening/phase-06-repo.sh:8)

   Following the live outbox or REST consumer-run instructions invokes these scripts before the newly ported scenarios. Their Maven `-pl` lists still contain security, validation, Kafka and events modules, so reactor selection fails before building. `phase-05-repo.sh` has the same problem. The preparation helper also copies the old baseline repository, retaining cut artifacts.

   **Fix:** provide preparation scripts for the current scope using a fresh repository and valid module selections, then update the sample commands. Keep historical preparation instructions explicitly tied to `pre-spring-native`.

3. **Medium — Published MCP and skill catalogs present parked starters as available.**  
   [PlatformIndexGenerator.java:79](/C:/Users/Ali/AppData/Local/Temp/codex-p1r1/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/PlatformIndexGenerator.java:79)

   The generator includes every `STARTER_TO_PAGE` entry, including OpenAPI, REST client and resilience. Its `oneLiner()` deliberately skips blockquotes, discarding their new parking banners. I checked the generated index: these capabilities have starter coordinates but no parking status. That index is bundled into the released MCP server and feeds the skill.

   Consequently, `list_starters` or the skill recommends dependencies absent from the released BOM and repository; a consumer following that advice cannot build.

   **Fix:** filter the shipped catalog using publishable/BOM membership, or propagate an explicit availability field through the index, MCP responses, snippets and skill. Preserve separate documentation for parked development.

4. **Medium — Live documentation still gives unusable instructions and false security assurances.**  
   [docs/testing.md:91](/C:/Users/Ali/AppData/Local/Temp/codex-p1r1/docs/testing.md:91), [02-errors-validation.md:375](/C:/Users/Ali/AppData/Local/Temp/codex-p1r1/docs/book/chapters/02-errors-validation.md:375), [docs/examples.md:30](/C:/Users/Ali/AppData/Local/Temp/codex-p1r1/docs/examples.md:30)

   Concrete failures include copying `Containers.kafka()` into a test, adding the deleted `platform-starter-validation`, or relying on the golden-path example being “authenticated by default.” The messaging chapter also still recommends the events starter and relay. Removing hyperlinks while retaining these instructions does not make the pages accurate.

   **Fix:** replace executable instructions with supported equivalents and remove obsolete security claims. Move retained historical explanations behind explicit historical notices and tag links. The recorded intention to rewrite these pages later does not correct their current guidance.

5. **Medium — The closed hardening record contains prohibited substantive edits.**  
   [issue-register.md:119](/C:/Users/Ali/AppData/Local/Temp/codex-p1r1/docs/production-readiness/issue-register.md:119)

   HI-0109’s status and follow-up were changed, and HI-0110–HI-0112 were added. These are neither superseded banners nor rewritten links. A reader consulting the supposedly frozen evidence now sees later decisions mixed into that record.

   **Fix:** restore this file’s substantive content to the base commit and put Spring-native issue updates under `docs/spring-native/`. The other inspected hardening-record changes are banners or permitted tag-link replacements.

6. **Medium — The event-driven example was not restricted to RabbitMQ as Phase 1 step 5 requires.**  
   [producer/pom.xml:71](/C:/Users/Ali/AppData/Local/Temp/codex-p1r1/examples/example-event-driven/producer/pom.xml:71)

   Both producer and consumer retain an active-by-default `local` Maven profile with the in-memory starter. Starting the two applications normally creates separate process-local transports: publishing in the producer cannot reach the consumer. Step 5 explicitly requires keeping this example with the RabbitMQ profile only; D137’s exception concerns the archetype.

   **Fix:** make RabbitMQ the example’s runtime transport and retain in-memory transport only for isolated tests.

For the remaining questions:

- **Code/build:** no deleted-module Maven dependency or deleted production Java-type reference found in retained modules, including optional/test dependencies and configuration resources. No additional cut dependency found in the golden-path/smoke scripts, rewrite recipe, CI or release commands.
- **Behavior:** no additional implementation defect found in the JPA fallback, outbox principal handling, token-relay removal or RabbitMQ wiring. Their relevant assertions remain. `Containers.postgres()` retains its implementation, but I found no dedicated `Containers` test. The archetype’s production capability-report restriction is exercised by the golden path.
- **Management security:** Prometheus remains anonymously reachable on the application port at this commit. The ledger explicitly records the lost authentication assertions, and the plan schedules ND13’s separate-port protection for Phase 3. That protection is **not yet implemented**; this is a declared deferral, not an unlisted weakening.
- **Parking/check-bom:** no binary deployment or archetype leak found. Static checking across all 54 reactor projects, including root and nested examples, found no missing publishable BOM entry or managed deploy-skipped module. The catalog leak is finding 3.
- **Links:** no missing local file target found in the scanned live Markdown links; this does not validate external URLs or heading anchors.
- **Consumer ledger:** **no unlisted dropped or weakened assertion found** in the six ported scenario diffs. The drops reconcile to core 15, logging 11, data 1, outbox 3, RabbitMQ 0 and REST 0.
