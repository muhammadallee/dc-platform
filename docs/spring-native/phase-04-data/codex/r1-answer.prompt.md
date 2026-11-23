# Codex reassessment — Spring-native Phase 4, round 1

Your review (`codex/r1-review.out.md`) of `98191f2` found four High and three Medium findings. Claude's answers are at
**`9ad0b15`**: `git diff 98191f2..9ad0b15`. Review read-only. Logging runs 189 tests and data 58, the full reactor
`verify` passes; the consumer run has not been re-run on this commit.

For findings 1–4 the contract is narrowed (decision D142's amendment, `docs/modules/logging.md`, scope §2) instead of
handling each wrapper you found. The masker now calls `toString()` only on:
- JDK value types (an exact allow-list), character data, `Path` and a logging library's objects;
- a resource (`AutoCloseable`);
- an application object with no bean properties (a stated limit).

Everything else is walked or named:
- `Optional` and `Map.Entry` are walked into.
- Any other JDK object is `[Type]`.
- A JDK proxy is `[proxy <Interface>]`, with no method called.
- An entity, a Hibernate proxy and an unloaded collection are placeholders.
- The JPA check runs before every text shortcut, in `mask()` and `sanitizePair()` too.

| # | Answer | Test |
|---|---|---|
| 1 | A map key that is not a plain value (an exact `String`, JDK number, `Boolean`, `Character`, `UUID`, or an enum by `name()`) is written as its walked text, and its value is masked. | `JpaValueMaskingTest.aKeyThatIsNotAPlainValueMasksItsValue`; `StructuredLogMaskingTest.throwingCharacterDataAndMapKeysCostOnlyTheirPlace` updated |
| 2 | A JDK proxy is `[proxy <Interface>]`; nothing of it is called. | `aSpringDataProjectionOverAProxyIsNamedAndNothingOfItRuns` (a real `SpelAwareProxyProjectionFactory` projection over a real proxy) |
| 3 | The rendered set is narrowed, as above. `Optional` and `Map.Entry` are walked into. | `aJdkObjectHoldingAProxyIsItsTypeName`; `Optional` and `Map.entry` added to every case's ways |
| 4 | The JPA check comes first in every path. | `aProxyThatIsTextIsNeverRenderedThroughATextShortcut` (an entity implementing `CharSequence`) |
| 5 | AC17 searches the probe and the generated service's `src/main/java` for any `ae.gov.dubaicustoms.platform` reference (static, indented or fully qualified imports included). A scan error fails the check. | consumer run |
| 6 | The probe logs the proxy, the entity and the collection nested in a map, a list, a record and a bean, and the proxy in an `Optional`. Each placeholder is counted (6, 5, 5); the statement count and the positive control still apply. | consumer run |
| 7 | **Narrowed:** AC20's slice proves the defaults. The starter's own classpath is the golden path's job (`tooling/scripts/golden-path.sh` lines 196–242 and 338–339): it generates a service with the starter alone, checks that `spring-boot-flyway` is packaged, boots it and asserts Flyway applied the migration. Scope AC20 says so. | CI `generator-gate` |

Answer **resolved**, **partly resolved** or **not resolved** for each. For 1–4 and 7, say whether you accept the narrowed
contract. List **every** new issue this diff introduces, in one answer, with severity, file and line, a failure scenario and
a fix. Say "none" if none.
