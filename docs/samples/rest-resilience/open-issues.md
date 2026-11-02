# Open issues

| ID | Impact | Owner | Mitigation | Link |
|---|---|---|---|---|
| HI-0094 | Request bodies are held in memory, and the deadline starts after your own interceptors | accepted by the owner (D131) | Documented; don't use this for large uploads | [issue register](../../production-readiness/issue-register.md) |
| HI-0095 | An encoded or oddly named secret, or personal data, can remain in `bodySnippet()` | accepted by the owner (D131) | Common forms removed; documented as best effort | [issue register](../../production-readiness/issue-register.md) |
| HI-0096 | The JDK's `net.properties` is checked at context start, so a live edit is not seen | accepted by the owner (D131) | Restart the JVM after changing it | [issue register](../../production-readiness/issue-register.md) |
| HI-0097 | Two user composite registries sharing an exporter count attempts twice | accepted by the owner (D131) | Boot's one composite is unaffected | [issue register](../../production-readiness/issue-register.md) |
| HI-0098 | A reset within 2 ms of the deadline is called the deadline: 504 not 503 | accepted by the owner (D131) | Both are `retryable`; never for a POST | [issue register](../../production-readiness/issue-register.md) |
| HI-0099 | The JSON library's message for a malformed success body can quote a bad token into the error log | Phase 7 (D131) | Documented. Needs a downstream sending a secret in malformed JSON | [issue register](../../production-readiness/issue-register.md) |
| — | Inbound deadline propagation is not implemented | deferred by the plan | Each client has its own deadline | [scope](../../production-readiness/phase-06-rest-resilience/scope.md) |
| — | `@Bulkhead(type = THREADPOOL)` is not detected by the virtual-thread guard | service | Don't use it; documented | [resilience](../../modules/resilience.md) |
| — | TLS failures, HTTP/2 and the `@Retry` aspect were not run | Phase 8 | See verification | [verification](verification.md) |
