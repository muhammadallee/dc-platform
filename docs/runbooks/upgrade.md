# Runbook — Upgrading a Service to a New Train (for application teams)

1. Read `docs/upgrade/<version>.md` — do everything under **ACTION REQUIRED** first (for this train:
   [0.2.0](../upgrade/0.2.0.md)).
2. Bump one number: the `<version>` of `platform-service-parent` in your pom (or `platform.version`
   property if you consume the BOM directly).
3. Run the checker:
   ```bash
   mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:upgrade-check -Dplatform.target=<version>
   ```
   Fix every deprecation it reports (it names the replacement key/type). Warnings today are removals
   at the next major — do not defer.
4. `mvn verify`. Startup WARNs about deprecated properties count as failures for this checklist.
5. Smoke locally: app boots, `/actuator/platform` capabilities all ACTIVE as expected, key flows pass.
6. Majors only: apply the published OpenRewrite recipe first
   (`mvn org.openrewrite.maven:rewrite-maven-plugin:run -Drewrite.recipeArtifactCoordinates=ae.gov.dubaicustoms.platform:platform-migrations:<v>`),
   then steps 1–5.
7. Stuck? Check `--debug` condition report + the capability page's "Replace/Disable" section before
   filing a platform issue; include your `/actuator/platform` output.

See also: [compatibility](../reference/compatibility.md) · [release runbook](release.md).
