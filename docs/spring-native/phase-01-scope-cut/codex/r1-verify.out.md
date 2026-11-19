Reviewed `451420b..61b23b1`, excluding `docs/llms-full.txt`. Read-only; no Maven or Docker runs.

1. **ACCEPTED.** `PlatformUsageRules` now bans only RabbitMQ infrastructure. Kafka templates and listener containers pass; regression assertions cover both.

2. **NOT RESOLVED.** The helper removes the platform artifact subtree before installing the current reactor, addressing stale cut artifacts on its normal path. However, its baseline protection is ineffective, and the new usage commands can silently fall back to a stale repository—details below. All six sample command pages and Phase 1–6 usage lines reference it, but [phase-00-smoke.sh:6](/C:/Users/Ali/AppData/Local/Temp/codex-p1v/tooling/hardening/scenarios/phase-00-smoke.sh:6) still uses the default repository and lacks an explicit historical-only guard.

3. **ACCEPTED.** The generated catalog’s 10 starters exactly match the BOM’s starters. Capabilities and snippets exclude parked starters; the shipped skill catalog agrees.

4. **NOT RESOLVED.** Live instructions remain without a cut-capability warning:
   
   - [outbox/commands.md:41](/C:/Users/Ali/AppData/Local/Temp/codex-p1v/docs/samples/outbox/commands.md:41) selects deleted `events/platform-events-autoconfigure`, so Maven reactor selection fails. Line 27 also still prescribes the events starter. Its new banner claims successful Spring-native verification rather than warning about obsolete content.
   - [local-dev.md:26](/C:/Users/Ali/AppData/Local/Temp/codex-p1v/docs/runbooks/local-dev.md:26) instructs running the golden-path example with `local,kafka,pg`; surrounding instructions advertise removed Compose services.
   - [local-vs-production.md:194](/C:/Users/Ali/AppData/Local/Temp/codex-p1v/docs/book/crosscutting/local-vs-production.md:194) instructs starting removed Redis/Postgres services. The page also prescribes cut storage, security and rate-limiting configuration.
   - [conventions.md:48](/C:/Users/Ali/AppData/Local/Temp/codex-p1v/docs/concepts/conventions.md:48) still directs readers to Kafka, Redis, S3 and Vault provider profiles.

   Replace these instructions or add the explicit historical/unfinished-update banner.

5. **ACCEPTED — rejection accepted.** [Plan Gate E:510](/C:/Users/Ali/AppData/Local/Temp/codex-p1v/docs/spring-native/plan.md:510) explicitly continues the existing issue register. [Phase 1 step 7:612](/C:/Users/Ali/AppData/Local/Temp/codex-p1v/docs/spring-native/plan.md:612) freezes the assessment, release decision and phase folders. My original finding applied the freeze too broadly.

6. **ACCEPTED.** Both applications now depend unconditionally on the RabbitMQ starter and configure its connection without profile activation. The in-memory runtime profiles are removed; consumer tests retain their test transport.

**New defects introduced by the fixes:**

- **High — baseline deletion through path traversal.** [native-repo.sh:19](/C:/Users/Ali/AppData/Local/Temp/codex-p1v/tooling/hardening/native-repo.sh:19) checks an unnormalized path that always starts with `$HOME/.m2-hardening/`. Passing `../.m2-baseline` or `../.m2-native-baseline` bypasses the check; line 21 then deletes the protected baseline before copying/building. **Fix:** validate a nonempty single-component phase slug and verify the resolved target remains beneath the intended hardening directory before any mutation, accounting for symlinks.

- **Medium — failed preparation silently selects the default cache.** [phase-01-core-errors.sh:14](/C:/Users/Ali/AppData/Local/Temp/codex-p1v/tooling/hardening/scenarios/phase-01-core-errors.sh:14), repeated across Phase 2–6, embeds preparation in an environment assignment attached to the scenario command. If preparation fails, Bash still launches the scenario with an empty `HH_MAVEN_REPO`; [harness.sh:63](/C:/Users/Ali/AppData/Local/Temp/codex-p1v/tooling/hardening/lib/harness.sh:63) substitutes `~/.m2/repository`, potentially testing stale artifacts. Confirmed with a harmless Bash reproduction, including `set -e`. **Fix:** check preparation’s exit status separately and launch the scenario only after success.
