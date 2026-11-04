1. **High — F1 misses consumers retaining Boot’s parent.**  
   **File:** [docs/book/appendix/d-compatibility.md:45](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/docs/book/appendix/d-compatibility.md:45).  
   **Trigger:** Keep `spring-boot-starter-parent:4.1.2`, copy the documented `platform-bom` import, and add `platform-starter-messaging-rabbit`, without overriding `rabbit-amqp-client.version`. There is no explicit Boot BOM import to reorder.  
   **Consequence:** Boot’s inherited, direct management of `com.rabbitmq:amqp-client:5.30.0` takes precedence over the platform BOM’s `5.37.0`, retaining the RabbitMQ vulnerabilities recorded in HI-0105. Maven preserves existing managed entries when importing BOMs; see its [import implementation](https://maven.apache.org/ref/3.9.9/maven-model-builder/xref/org/apache/maven/model/composition/DefaultDependencyManagementImporter.html).  
   **Default-route assessment:** The archetype and README route remain safe. However, retaining [Boot’s standard Maven parent](https://docs.spring.io/spring-boot/maven-plugin/using.html) while adding the advertised BOM is a likely adoption path for existing applications. The warning must also cover inherited Boot management and direct these consumers to `platform-service-parent` or the required overrides.

F2 is confirmed **for the stated direct-dependency route**: [platform-service-parent/pom.xml:96](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/build/platform-service-parent/pom.xml:96) bans the exporter with the OTLP message, and the observability documentation declares it unsupported. The rule deliberately excludes transitive dependencies.

For item 2, static traversal of Boot 4.1.2’s cached BOM imports and parents found **24 managed `io.opentelemetry` artifacts; Zipkin alone is absent from the 1.67.0 BOM**. No additional artifact gap was found. No builds or runtime tests were run.

VERDICT: REJECT
