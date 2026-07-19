# Reference — Configuration Property Conventions

1. **Prefix:** `dc.platform.<cap>`; nested groups mirror concepts (`…messaging.handler.retry.max-attempts`).
2. **Kill switch:** every capability: `dc.platform.<cap>.enabled` (Boolean, default true,
   `matchIfMissing=true` on the condition). Sub-features get their own `enabled` when independently toggleable.
3. **Shape:** one immutable record per capability, `@ConfigurationProperties(prefix="dc.platform.<cap>")`,
   `@Validated` with constraints, defaults via `@DefaultValue` / compact constructor.
   Registered with `@EnableConfigurationProperties` on the autoconfiguration (never scanning).
4. **Metadata:** `spring-boot-configuration-processor` on every autoconfigure module (annotationProcessor);
   descriptions via javadoc on components (processor lifts them). Hand-written
   `additional-spring-configuration-metadata.json` for: deprecations (`replacement`, `reason`),
   value hints, and properties set via EnvironmentPostProcessor (invisible to the processor —
   MUST be added manually; checklist item).
5. **Durations/sizes:** `java.time.Duration` / `DataSize` so users write `10s`, `5MB`.
6. **Deprecation:** never delete a key in a minor. Add metadata deprecation + keep old key working
   (alias binding or explicit migration in the record); WARN once at startup naming the replacement;
   remove at next major.
7. **Platform-set environment defaults** (health groups, actuator exposure, resilience defaults) go through
   a `PlatformDefaultsEnvironmentPostProcessor` per module adding a LOWEST-precedence property source named
   `platform-<cap>-defaults` — user config always wins; the name is visible in `/actuator/env` for
   debuggability (comment this in code).
8. **Documentation:** every key appears in the generated reference (phase 14 completeness gate).
