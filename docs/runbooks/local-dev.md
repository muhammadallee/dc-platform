# Runbook — Local Development

## Prerequisites
Java 25 (the reactor requires it; the enforcer's `RequireJavaVersion` fails otherwise), Maven 3.9+,
git. Docker Desktop OPTIONAL (only for `-Pdocker` and manual infra).

## Everyday commands
```bash
mvn -T1C verify                                   # full build+tests, no Docker needed
mvn -T1C -pl <path/to/module> -am verify          # one module + its dependencies
mvn -T1C install                                  # put platform into ~/.m2 for local apps
./tooling/scripts/golden-path.sh                  # end-to-end DX check (after phase 13)
```

## Docker-backed tests & manual infra
```bash
docker compose -f docker-compose.local.yml up -d  # kafka, rabbit, redis, postgres, vault(dev), localstack
mvn -Pdocker verify                               # runs @Tag("docker") suites (Testcontainers manages
                                                  # its own containers; compose is for MANUAL app runs)
docker compose -f docker-compose.local.yml down -v
```
Vault dev token: `dev-root`. Postgres: `platform/platform@localhost:5432/platform`.

## Running an example against real infra
```bash
(cd examples/example-golden-path && mvn spring-boot:run -Dspring-boot.run.profiles=local,kafka,pg)
```
Profiles: `local` (console logs, H2/inmemory), `kafka`, `pg`, `redis` switch providers to compose services.

## Debugging platform behavior in an app
1. `--debug` flag → ConditionEvaluationReport: why each platform auto-config did/didn't activate.
2. `GET /actuator/platform` → capability status + active providers.
3. `GET /actuator/env` → look for `platform-<cap>-defaults` property sources (platform-set defaults; anything above them overrides).
4. Startup log line `platform: …` lists capabilities.
5. Kill switch a capability: `dc.platform.<cap>.enabled=false`.

## Troubleshooting
- **Build fails in enforcer `PlatformLayerRule`** → the message names the violated edge and the fix; do not add exclusions.
- **japicmp failure** → you broke public API/SPI binary compat; either restore, or (major only) follow [release](release.md) deprecation path.
- **Docker tests fail with "Docker not available"** → expected without Docker; they must be `assume`-skipped — if they FAIL instead, the guard is missing (fix the test).
- **`${revision}` weirdness in IDE** → reimport Maven; flatten plugin handles publishing, IDE uses raw poms.

## Rendering these docs
The Markdown sources build and are gated (completeness + link check) by the default `mvn -T1C verify`.
The HTML site is an opt-in render (it shells out to MkDocs, which is not a Maven-Central tool):
```bash
mvn -Pdocs-site -pl docs/platform-docs verify     # builds docs/site/ via mkdocs
python -m http.server -d docs/site 8000           # browse at http://localhost:8000
```
