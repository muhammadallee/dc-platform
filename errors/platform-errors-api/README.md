# platform-errors-api

Business exception hierarchy and the `ProblemDetailCustomizer` extension point.

## ADR note — why this api module depends on `spring-web`

The dependency constitution forbids third-party types in api/spi signatures. This module is the
one sanctioned exception (decision D11 in `docs/decisions/decision-log.md`):
`org.springframework.http.ProblemDetail` **is** the RFC-9457 problem-details model — the standard
the errors capability exists to implement — not an implementation detail behind it. Wrapping it
would force every consumer to convert at the boundary and would drift from the servlet stack's
native handling. The ArchUnit constitution whitelists `org.springframework.http` for the `errors`
capability only; any other third-party package still fails the build here.
