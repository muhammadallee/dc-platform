package ae.gov.dubaicustoms.platform.test.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class PlatformUsageRulesTest {

    private static final String FIXTURES = "ae.gov.dubaicustoms.platform.test.arch.fixtures";
    private static final JavaClasses CLEAN =
            new ClassFileImporter().importPackages(FIXTURES + ".clean");
    private static final JavaClasses BAD =
            new ClassFileImporter().importPackages(FIXTURES + ".bad");

    @Test
    void allReturnsTheConformanceSuite() {
        ArchRule[] rules = PlatformUsageRules.all();
        assertThat(rules).hasSize(6).doesNotContainNull();
    }

    @Test
    void everyRulePassesOnConformantCode() {
        for (ArchRule rule : PlatformUsageRules.all()) {
            rule.check(CLEAN); // no exception == pass
        }
    }

    @Test
    void systemGetenvIsFlaggedWithASecretsMessage() {
        assertThatThrownBy(() -> PlatformUsageRules.noSystemGetenv().check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("getenv")
                .hasMessageContaining("platform secrets");
    }

    @Test
    void handRolledExceptionHandlerIsFlaggedWithAPlatformExceptionMessage() {
        assertThatThrownBy(() -> PlatformUsageRules.noHandRolledExceptionHandler().check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("HandRolledAdvice")
                .hasMessageContaining("PlatformException");
    }

    @Test
    void responseEntityExceptionHandlerSubclassIsFlagged() {
        assertThatThrownBy(() -> PlatformUsageRules.noResponseEntityExceptionHandlerSubclass().check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("HandRolledAdvice")
                .hasMessageContaining("ResponseEntityExceptionHandler");
    }

    @Test
    void directObjectMapperConstructionIsFlagged() {
        assertThatThrownBy(() -> PlatformUsageRules.noDirectObjectMapperInstantiation().check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ObjectMapperMaker")
                .hasMessageContaining("ObjectMapper");
    }

    @Test
    void threadSleepInProductionIsFlagged() {
        assertThatThrownBy(() -> PlatformUsageRules.noThreadSleepInProduction().check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Sleeper");
    }

    @Test
    void messagingRuleIgnoresServicesWithoutBrokerInfrastructure() {
        // The bad fixtures use no Kafka/Rabbit types, so the messaging rule stays green here;
        // its name-matching is covered directly by isBannedMessagingType below.
        PlatformUsageRules.noDirectMessagingInfrastructure().check(BAD);
    }

    @Test
    void isBannedMessagingTypeMatchesTemplatesAndListenerContainers() {
        assertThat(PlatformUsageRules.isBannedMessagingType(
                "org.springframework.kafka.core.KafkaTemplate")).isTrue();
        assertThat(PlatformUsageRules.isBannedMessagingType(
                "org.springframework.amqp.rabbit.core.RabbitTemplate")).isTrue();
        assertThat(PlatformUsageRules.isBannedMessagingType(
                "org.springframework.kafka.listener.KafkaMessageListenerContainer")).isTrue();
        assertThat(PlatformUsageRules.isBannedMessagingType(
                "org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer")).isTrue();
    }

    @Test
    void isBannedMessagingTypeIgnoresPlatformAndJdkTypes() {
        assertThat(PlatformUsageRules.isBannedMessagingType(
                "ae.gov.dubaicustoms.platform.messaging.EventPublisher")).isFalse();
        assertThat(PlatformUsageRules.isBannedMessagingType("java.lang.String")).isFalse();
        assertThat(PlatformUsageRules.isBannedMessagingType(
                "org.springframework.kafka.core.KafkaAdmin")).isFalse();
    }
}
