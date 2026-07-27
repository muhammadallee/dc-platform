package ae.gov.dubaicustoms.platform.test.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.apiguardian.api.API;

/**
 * Consumer-side conformance rules: ArchUnit rules a platform <em>service</em> runs over its own
 * production classes to prove it uses the platform's APIs instead of hand-rolling the primitives the
 * platform already owns. Every rule's failure message names the platform alternative, so the gate
 * teaches — a coding agent that trips one is told exactly which platform type to use instead.
 *
 * <p>The archetype (phase 13) generates a {@code PlatformConformanceTest} that imports the service's
 * classes and runs {@link #all()} against them:
 * <pre>{@code
 * JavaClasses classes = new ClassFileImporter()
 *         .withImportOption(new ImportOption.DoNotIncludeTests())
 *         .importPackages("com.acme.myservice");
 * for (ArchRule rule : PlatformUsageRules.all()) {
 *     rule.check(classes);
 * }
 * }</pre>
 *
 * <p>Rules match banned third-party types by fully-qualified name, so this module does not depend on
 * Kafka, Rabbit, or Spring MVC — a service that pulls none of them still compiles the conformance
 * test. The rules are an intentionally deletable escape hatch: a team with a documented reason may
 * remove {@code PlatformConformanceTest} from its service (discouraged; see {@code docs/modules/dx.md}).
 *
 * <p>This type is stateless and thread-safe; every call to {@link #all()} returns fresh rule objects.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public final class PlatformUsageRules {

    private static final String KAFKA_TEMPLATE = "org.springframework.kafka.core.KafkaTemplate";
    private static final String RABBIT_TEMPLATE = "org.springframework.amqp.rabbit.core.RabbitTemplate";
    private static final String REST_CONTROLLER_ADVICE =
            "org.springframework.web.bind.annotation.RestControllerAdvice";
    private static final String RESPONSE_ENTITY_EXCEPTION_HANDLER =
            "org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler";
    private static final String OBJECT_MAPPER = "com.fasterxml.jackson.databind.ObjectMapper";

    private PlatformUsageRules() {
    }

    /**
     * The full consumer conformance suite. A service runs every rule over its production classes.
     *
     * @return fresh, independent {@link ArchRule} instances; never {@code null} or empty
     */
    public static ArchRule[] all() {
        return new ArchRule[] {
            noDirectMessagingInfrastructure(),
            noHandRolledExceptionHandler(),
            noResponseEntityExceptionHandlerSubclass(),
            noSystemGetenv(),
            noDirectObjectMapperInstantiation(),
            noThreadSleepInProduction(),
        };
    }

    /**
     * A service must not depend on broker templates or listener containers directly — publish via
     * {@code EventPublisher} and consume via {@code @EventHandler}, which already wrap retry, DLQ,
     * correlation, serialization, and metrics.
     */
    static ArchRule noDirectMessagingInfrastructure() {
        return ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat(bannedMessagingInfrastructure())
                .because("use EventPublisher / @EventHandler — never inject KafkaTemplate/RabbitTemplate or wire "
                        + "listener containers directly (retry, DLQ, correlation, serialization and metrics are "
                        + "already handled)")
                .allowEmptyShould(true);
    }

    private static DescribedPredicate<JavaClass> bannedMessagingInfrastructure() {
        return new DescribedPredicate<>("are Kafka/Rabbit templates or listener containers") {
            @Override
            public boolean test(JavaClass clazz) {
                return isBannedMessagingType(clazz.getName());
            }
        };
    }

    /**
     * Whether {@code fullyQualifiedName} is a broker template or listener container the platform hides
     * behind {@code EventPublisher}/{@code @EventHandler}. Package-private and name-based so it is
     * testable without Kafka/Rabbit on the classpath.
     */
    static boolean isBannedMessagingType(String fullyQualifiedName) {
        return KAFKA_TEMPLATE.equals(fullyQualifiedName)
                || RABBIT_TEMPLATE.equals(fullyQualifiedName)
                || fullyQualifiedName.matches("org\\.springframework\\.(kafka|amqp)\\..*ListenerContainer");
    }

    /**
     * A service must not define its own {@code @RestControllerAdvice} extending Spring's
     * {@code ResponseEntityExceptionHandler} — the platform maps everything to RFC 9457 already.
     * Throw {@code PlatformException} subtypes with an {@code ErrorCode} instead.
     */
    static ArchRule noHandRolledExceptionHandler() {
        return ArchRuleDefinition.noClasses()
                .that().areAnnotatedWith(REST_CONTROLLER_ADVICE)
                .should(new ArchCondition<>("extend Spring's ResponseEntityExceptionHandler") {
                    @Override
                    public void check(JavaClass origin, ConditionEvents events) {
                        origin.getRawSuperclass().ifPresent(superclass -> {
                            if (RESPONSE_ENTITY_EXCEPTION_HANDLER.equals(superclass.getName())) {
                                events.add(SimpleConditionEvent.satisfied(origin,
                                        origin.getName() + " is a @RestControllerAdvice extending "
                                                + "ResponseEntityExceptionHandler"));
                            }
                        });
                    }
                })
                .because("throw PlatformException subtypes (BusinessException/NotFoundException/ConflictException) "
                        + "with an ErrorCode — the platform maps them to RFC 9457 with correlation IDs; do not "
                        + "write your own @RestControllerAdvice")
                .allowEmptyShould(true);
    }

    /**
     * A service must not subclass Spring's {@code ResponseEntityExceptionHandler} at all (with or
     * without {@code @RestControllerAdvice}) — broader than {@link #noHandRolledExceptionHandler()},
     * which only catches the annotated form. The platform already maps every exception to RFC 9457.
     */
    static ArchRule noResponseEntityExceptionHandlerSubclass() {
        return ArchRuleDefinition.noClasses()
                .should(new ArchCondition<>("subclass Spring's ResponseEntityExceptionHandler") {
                    @Override
                    public void check(JavaClass origin, ConditionEvents events) {
                        origin.getRawSuperclass().ifPresent(superclass -> {
                            if (RESPONSE_ENTITY_EXCEPTION_HANDLER.equals(superclass.getName())) {
                                events.add(SimpleConditionEvent.satisfied(origin,
                                        origin.getName() + " subclasses ResponseEntityExceptionHandler"));
                            }
                        });
                    }
                })
                .because("subclassing ResponseEntityExceptionHandler is banned here → throw PlatformException "
                        + "subtypes with an ErrorCode; the platform maps them to RFC 9457 with correlation IDs "
                        + "(docs/modules/errors.md)")
                .allowEmptyShould(true);
    }

    /**
     * A service must not read configuration or secrets via {@link System#getenv} — reference them
     * through the platform secrets property source (and {@code dc.platform.*} for config).
     */
    static ArchRule noSystemGetenv() {
        return ArchRuleDefinition.noClasses()
                .should().callMethod(System.class, "getenv")
                .orShould().callMethod(System.class, "getenv", String.class)
                .because("use the platform secrets property source — never System.getenv for secrets or config")
                .allowEmptyShould(true);
    }

    /**
     * A service must not construct its own {@code ObjectMapper} — inject the Spring-managed instance
     * Boot configures (consistent modules, date/time handling, and null strategy). Matched by name so
     * this module needs no compile dependency on Jackson.
     */
    static ArchRule noDirectObjectMapperInstantiation() {
        return ArchRuleDefinition.noClasses()
                .should(new ArchCondition<>("construct a new com.fasterxml.jackson.databind.ObjectMapper") {
                    @Override
                    public void check(JavaClass origin, ConditionEvents events) {
                        origin.getConstructorCallsFromSelf().forEach(call -> {
                            if (OBJECT_MAPPER.equals(call.getTarget().getOwner().getName())) {
                                events.add(SimpleConditionEvent.satisfied(origin,
                                        origin.getName() + " constructs a new ObjectMapper"));
                            }
                        });
                    }
                })
                .because("constructing a new ObjectMapper is banned here → inject the Spring-managed ObjectMapper "
                        + "Boot configures, so JSON stays consistent platform-wide (docs/concepts/conventions.md)")
                .allowEmptyShould(true);
    }

    /**
     * A service must not call {@link Thread#sleep} in production code — use the platform's resilience
     * and scheduling primitives (or Awaitility in tests). Blocking the thread hides latency from the
     * platform's timeouts, retries, and metrics.
     */
    static ArchRule noThreadSleepInProduction() {
        return ArchRuleDefinition.noClasses()
                .should().callMethod(Thread.class, "sleep", long.class)
                .orShould().callMethod(Thread.class, "sleep", long.class, int.class)
                .because("Thread.sleep in production is banned here → use the platform resilience/scheduling "
                        + "primitives (retry/backoff, @Scheduled) or Awaitility in tests (docs/modules/resilience.md)")
                .allowEmptyShould(true);
    }
}
