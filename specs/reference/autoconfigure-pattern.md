# Reference — Canonical Auto-Configuration Pattern (copy this shape for every capability)

## 1. Properties record
```java
/**
 * Configuration for the <cap> capability. Bound from {@code acme.platform.<cap>.*}.
 * Immutable; validated at startup.  @since 0.x.0
 */
@Validated
@ConfigurationProperties(prefix = "acme.platform.<cap>")
public record <Cap>Properties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** <meaningful description — lifted into config metadata>. */
        @DefaultValue("10s") Duration someTimeout) {
}
```

## 2. Auto-configuration class
```java
/*
 * Activates when: <Cap>Api on classpath AND acme.platform.<cap>.enabled != false
 * Backs off when: user defines a <MainBeanType> bean
 * Beans: <mainBean> — <one line>; <secondary> — <one line>
 * Order: after <X>AutoConfiguration because <why>
 */
@AutoConfiguration(after = SomeSpringBootAutoConfiguration.class)
@ConditionalOnClass(SomeApiType.class)
@ConditionalOnProperty(prefix = "acme.platform.<cap>", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(<Cap>Properties.class)
public class <Cap>AutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MainBeanType mainBean(<Cap>Properties props,
                          ObjectProvider<MainBeanCustomizer> customizers) {
        var builder = MainBeanType.builder(props);
        customizers.orderedStream().forEach(c -> c.customize(builder)); // ordered: @Order on beans
        return builder.build();
    }

    @Bean
    CapabilityDescriptor <cap>CapabilityDescriptor(/* … */) {
        return new CapabilityDescriptor("<cap>", "ACTIVE", "<detail>");
    }
}
```
Registered in `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## 3. Starter POM (no code — dependencies only, each with a why-comment)

## 4. Mandatory ContextRunner test matrix (one test class per autoconfiguration)
```java
class <Cap>AutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(<Cap>AutoConfiguration.class));

    @Test void activeByDefault() { runner.run(ctx -> assertThat(ctx).hasSingleBean(MainBeanType.class)); }

    @Test void killSwitchDisables() {
        runner.withPropertyValues("acme.platform.<cap>.enabled=false")
              .run(ctx -> assertThat(ctx).doesNotHaveBean(MainBeanType.class)); }

    @Test void backsOffWhenUserBeanPresent() {
        runner.withBean("mine", MainBeanType.class, () -> myInstance)
              .run(ctx -> assertThat(ctx).getBean(MainBeanType.class).isSameAs(myInstance)); }

    @Test void inactiveWhenClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(SomeApiType.class))
              .run(ctx -> assertThat(ctx).doesNotHaveBean(<Cap>AutoConfiguration.class)); }

    @Test void customizersApplyInOrder() { /* two ordered customizers; assert application order */ }
}
```
Add capability-specific behavior tests beyond the matrix; the matrix itself is non-negotiable.

## 5. Checklist deltas
Also update: platform-bom entry · AutoConfiguration.imports · config metadata (incl. additional-*.json
for env-post-processor keys) · docs/modules/<cap>.md · CHANGELOG · CapabilityDescriptor bean.

## 6. FailureAnalyzer (phase 16 onward — required for each capability's top misconfigurations)
Ship a `FailureAnalyzer` (registered in `spring.factories`) for each likely misconfiguration.
The Action line ALWAYS names the fix concretely: starter coordinates, property key, or doc anchor —
startup failures are a discovery surface; make them teach.
