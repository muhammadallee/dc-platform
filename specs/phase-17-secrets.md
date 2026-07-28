# Phase 17 — Secrets: reconcile references, don't build a capability (Track 2)

**Status:** DRAFT plan (2026-07, rev 2). Supersedes the earlier "build a full secrets capability"
draft. Prerequisite reading: `docs/reviews/platform-review-2026-07.md` (Track 2), decisions **D50**
(secrets deferred), **D57** (secrets TCK omitted), **D80** (scope).

---

## 0. Decision: no secrets capability is built

The consuming architecture makes a platform secrets capability redundant:

- Services get secrets from **HashiCorp Vault via Spring Cloud Vault** (`spring-cloud-vault-config`):
  add the dependency, put `${...}` placeholders in `application.yml`, and Spring Cloud Vault populates
  the `Environment` from Vault at bootstrap. Property injection is therefore **native Spring** — nothing
  for the platform to add.
- Secret **keys/paths are static; only values rotate**. Rotation is handled by **rolling pod restart**
  (Level 1 consumption): the app reads the current value at startup, and a restart on rotation re-reads
  it. No in-process `@RefreshScope`/live-refresh is required, which removes the only part a platform
  module could have meaningfully owned.

Given that, a dedicated `secrets-api / spi / impl / autoconfigure` capability would wrap things that
don't need wrapping (Vault connection = Spring Cloud Vault; property injection = Spring; rotation =
Kubernetes rollout). This confirms **D50**. The full-capability draft is abandoned.

**What remains is reconciliation, not construction:** the reactor ships four hooks that point at a
platform secrets source that does not exist. They must be re-pointed at the sanctioned Spring Cloud
Vault + `${...}` pattern so the repo is internally consistent. An optional thin convention starter is
noted for later, to be built only if per-service Vault config drift becomes a real cost.

---

## 1. Scope A — reconcile the dangling references (do this; small, `build:`/`docs:`)

Four places currently reference a nonexistent platform secrets capability. Re-point each at the real
pattern (Spring Cloud Vault + property placeholders; `System.getenv` still banned, but the *alternative*
named is Spring config/placeholders, not a platform source).

1. **`noSystemGetenv` usage rule** — `test/platform-test-api/.../arch/PlatformUsageRules.java`
   - `noSystemGetenv()` `.because(...)` message (line ~160): change "use the platform secrets property
     source" → e.g. "read secrets/config through Spring config placeholders (`${...}`, populated by
     Spring Cloud Vault) — never `System.getenv`".
   - Method javadoc (lines ~153–154): same re-point.
   - Test `PlatformUsageRulesTest.systemGetenvIsFlaggedWithASecretsMessage()` asserts the message
     contains `"platform secrets"` (line ~37) — update the assertion to the new wording.
   - Fixture comment `.../arch/fixtures/bad/EnvReader.java` (lines 3–4): re-point.
   - Docs table row in `docs/modules/dx.md` and `docs/llms-full.txt`
     (`| noSystemGetenv | System.getenv(...) | the platform secrets property source |`): change the
     "alternative" column to "Spring config placeholders (Spring Cloud Vault)".

2. **`secrets-unresolvable-ref` FailureAnalyzer** — documented as deferred in `CHANGELOG` (phase-16 A.2).
   No platform module owns Vault, so there is nothing to analyze. **Remove the deferral note** (or fold
   it into the optional starter in Scope B if that is ever built). Do not ship a platform FailureAnalyzer
   for Spring Cloud Vault's own failures — Spring Cloud Vault reports those itself.

3. **`spring-cloud-vault` enforcer ban** — documented as deferred in `CHANGELOG` (phase-16 B.1). Since
   Spring Cloud Vault is the **sanctioned** mechanism, it must **not** be banned. **Drop the deferred-ban
   note** and record that spring-cloud-vault is explicitly allowed (the opposite of the other wrapped
   libs). Revisit only if Scope B ships a starter (then the ban would steer teams to the starter, not
   away from the library).

4. **`platform-tck-secrets`** — omitted by **D57**. With no provider to certify, the TCK stays omitted;
   just confirm D57 remains the standing record and remove any "planned" phrasing.

**Acceptance for Scope A:** `grep -ri "platform secrets property source" .` returns only historical
CHANGELOG entries; `grep -ri "secret.*defer\|defer.*secret" CHANGELOG.md` returns nothing; the
`PlatformUsageRules` test passes with the new message; `mvn -T1 verify` green. Add decision **D81**
recording the "reconcile, don't build" outcome. No new modules, no BOM change.

## 2. Scope B — optional thin convention starter (LATER, only if drift hurts)

Not part of the reactor unless justified. If, at fleet scale, services diverge in how they wire Spring
Cloud Vault (auth method, KV mount path, timeouts, lease config, log redaction), a **POM-only** starter
can standardize it:

- `platform-starter-secrets-vault` (Starter category — **POM only**, per CLAUDE.md rule 4): depends on
  `spring-cloud-vault-config` + a tiny `secrets-vault-autoconfigure` that only contributes **default
  properties** (via `EnvironmentPostProcessor`, `platform-secrets-defaults` source): standard auth
  (Kubernetes auth in-cluster), KV v2 mount convention, connect/read timeouts, and secret-key redaction
  wiring into the phase-4 `LogSanitizer`. No `Secrets` API, no SPI, no providers.
- If this ships, then (and only then): reinstate the `spring-cloud-vault` **direct-dependency** ban
  (steer to the starter, escape hatch `-Dplatform.bans.skip=true`), add a Vault-config FailureAnalyzer,
  and `docs/modules/secrets.md`.

This is convention-over-configuration, not a capability. Estimated ~1–2 days if pursued. **Default
recommendation: don't build it now** — revisit when config drift is observed.

## 3. Explicitly out of scope (record, don't build)

- Runtime `Secrets.get(...)` API, `SecretRef`, `secrets-spi`, env/file providers — all dropped. Services
  consume secrets as ordinary Spring properties.
- Live `@RefreshScope` rotation — not needed under restart-based rotation. If a future service needs
  zero-restart rotation for a specific bean, that is a per-service `@RefreshScope` + Spring Cloud Vault
  lifecycle concern, documented as a recipe if it ever comes up — not a platform module.
- `dc-secrets:` custom property source — redundant; Spring Cloud Vault already exposes Vault as
  properties.

## 4. Why this is the right call

- **Faithful to the architecture (§1 of `platform-architecture.md`): don't wrap what Spring already
  does well.** Spring Cloud Vault + native placeholders + K8s rollout already deliver the requirement.
- **Removes debt instead of adding it:** the four dangling references become correct, and D50 stops
  looking like an oversight and becomes a recorded, defended decision.
- **Keeps the door open:** Scope B is a clean, small, later add if the fleet actually needs config
  standardization — without committing to a capability that the consumption model doesn't warrant.
