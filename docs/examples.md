# Examples

Four runnable services under [`examples/`](https://github.com) build inside the platform reactor and
consume the platform exactly as a real service does — through `platform-service-parent` and the
`platform-starter-*` dependencies. They are never published (`maven.deploy.skip`) and never part of
the [BOM](reference/bom.md); their `example-` artifactId prefix marks them as demonstrations to the
dependency-constitution enforcer.

Run any of them from its module directory with `mvn spring-boot:run`, or boot-and-probe the whole
stack with `tooling/scripts/golden-path.sh` (archetype) and `tooling/scripts/smoke-matrix.sh`
(golden-path across capability combinations).

## example-minimal — the floor

`platform-starter-core` + `-errors` + `-logging`, and nothing else. It exists to prove the smallest
thing the platform guarantees:

- every request carries a correlation ID and structured JSON logs, with no code in the service doing
  it;
- a thrown [`NotFoundException`](modules/errors.md) renders as an RFC-9457
  `application/problem+json` 404 with a stable [error code](reference/error-codes.md) — there is
  deliberately no `@RestControllerAdvice` anywhere in the service.

`GET /ping` returns `{"status":"ok"}`; `GET /widgets/{id}` always throws, so the problem-response
path is always exercised. See [`ProblemResponseTest`](modules/errors.md) for the assertion.
