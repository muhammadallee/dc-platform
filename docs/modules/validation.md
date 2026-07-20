# Validation

The Bean Validation constraints every DC service needs, plus a validator wired to the platform
message bundle and method validation switched on — annotate and go.

## What you get

- **`@NotBlankTrimmed`** — string must contain non-whitespace content; `null` and `"  "` fail.
- **`@Ulid`** — 26 Crockford base32 characters (case-insensitive, first char `0`–`7`); `null`
  passes (combine with `@NotNull`).
- **`@SafeText`** — rejects ISO control characters (tab/newline included): the cheap first line
  of defense against log injection and terminal-escape smuggling in single-line fields.
- **`@FutureInstant`** — `Instant` in the future, measured against the validator's
  `ClockProvider`, so tests inject a fixed clock instead of sleeping.
- **Platform validator** — message interpolation consults
  `platform-validation-messages.properties` first (override per key in your own bundle).
- **Method validation on** — `@Validated` services throw `ConstraintViolationException`, which
  the errors capability maps to a 400 problem response with `errors[]`.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-validation</artifactId>
</dependency>
```

Brings `spring-boot-starter-validation` (hibernate-validator + EL) — the api and autoconfigure
modules stay provider-free.

## Zero-config behavior

```java
record CreateOrder(@NotBlankTrimmed String customerName,
                   @NotNull @Ulid String declarationId,
                   @SafeText String remark,
                   @NotNull @FutureInstant Instant pickupAt) { }
```

`@Valid` request bodies and `@Validated` method parameters validate out of the box; with the
errors starter on board, failures become RFC-9457 400 responses with an `errors[]` extension.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.validation.enabled` | `true` | Kill switch for the whole capability. |

(Hand-written until the generated reference lands in phase 14.)

## Customize

- Override any message key (e.g. `dc.platform.validation.Ulid.message`) in your application's
  `ValidationMessages.properties` — the platform bundle sits below it.
- Constraints work with plain hibernate-validator too: message defaults ship in the api jar's
  `ContributorValidationMessages.properties`.

## Replace / Disable

- Define your own `Validator` bean — the platform validator AND its method-validation wiring
  both stand down.
- `dc.platform.validation.enabled=false` switches the capability off wholesale (Boot's default
  validation auto-configuration then applies).

## Error codes

None of its own — violations surface through the errors capability as `DC-CORE-0400` problem
responses.

## Testing

Build a standalone validator with a fixed clock for constraint unit tests:

```java
Validation.byDefaultProvider().configure()
        .clockProvider(() -> Clock.fixed(NOW, ZoneOffset.UTC))
        .buildValidatorFactory().getValidator();
```

## Local dev notes

No Docker, no network. If messages show raw `{dc.platform.validation.…}` keys, the interpolator
never saw a bundle — check that the starter (not just the api jar) is on the classpath.
