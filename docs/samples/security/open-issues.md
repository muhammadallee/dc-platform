# Open issues

| ID | Impact | Owner | Mitigation | Link |
|---|---|---|---|---|
| HI-0100 | A numeric `sub`, or an overflowing `nbf`, in a token your own provider signed is accepted | accepted by the owner (D132) | Needs a trusted signature | [issue register](../../production-readiness/issue-register.md) |
| HI-0101 | The `@RequiresPermission` advisor cannot be replaced by a bean of its name: startup fails | accepted by the owner (D132) | Replace the decision with a `PermissionEvaluatorProvider` | [issue register](../../production-readiness/issue-register.md) |
| HI-0102 | If an earlier filter took a UTF-16 writer, a 401, 403 or 503 body is UTF-16 | accepted by the owner (D132) | The body is escaped ASCII, right in any single-byte charset | [issue register](../../production-readiness/issue-register.md) |
| HI-0103 | Not tested: an auto-configuration placed between ours and Boot's, several providers through enforcement, the 1 MiB key-set cap, spans | accepted by the owner (D132) | No defect shown | [issue register](../../production-readiness/issue-register.md) |
| — | No mTLS, opaque tokens, introspection, sessions, CSRF or several issuers | excluded by the plan | — | [scope](../../production-readiness/phase-07-security/scope.md) |
| — | TLS to the key server not run; Keycloak HA and restarts not run | Phase 8 | See verification | [verification](verification.md) |
