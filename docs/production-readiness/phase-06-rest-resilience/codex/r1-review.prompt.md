# Phase 6 review round 1: REST client and resilience

You are reviewing an internal Spring Boot 4.1 / Spring 7 platform. Static review only: no JVM or Docker in your sandbox.
Review the change from `1162d99` to HEAD (`git diff 1162d99 HEAD`), reading the surrounding code. The scope, with requirements
and acceptance criteria, is `docs/production-readiness/phase-06-rest-resilience/scope.md`. Read it first.

Attack these, with concrete triggers:

1. **Duplicate sends.** Can a POST or PATCH reach a server twice by any path: the JDK client's own retry behaviour and its system
   properties, redirects (a 307 or 308 re-sends a POST), `method.name()` casing, a custom `HttpMethod`, a customizer, HTTP/2 upgrade, a
   GOAWAY or refused stream? Does `retryable()` ever say true for something that may have been applied?
2. **Request factory.** `AbstractClientHttpRequest` contract and `RestClient`'s use of it: headers copied correctly (`Content-Length`,
   multi-value, restricted headers); empty bodies; a body written through `StreamingHttpOutputMessage`; attributes; a `JdkClientHttpRequestFactory`
   created per attempt. What does each per-attempt object cost or leak?
3. **Permits.** Is every bulkhead permit and breaker permission returned, on every path: a response never closed by the caller
   (`exchange`, `retrieve().toEntity`, an exception in the status handler, a converter failure), an `Error`, a double close, the deadline
   path, `acquire()` failing on a retry attempt, half-open permissions, `onError` after `releasePermission`? Can a leak wedge a client?
4. **Retry.** Discarded responses closed before the next attempt? Interrupted threads? `Retry-After` of 0, huge, overflowing, or in the past;
   the wait compared with the deadline; backoff overflow; jitter of zero; retries of a body that cannot be replayed; a retry storm, given
   that the breaker counts each attempt.
5. **Deadline.** Does it really bound the call: connect time, a slow body, the time blocked in `bulkhead.max-wait`, `HttpClient` queueing? The
   timeout passed per attempt versus the `Duration` compare in `timeout.equals(remaining)`, used to choose `DEADLINE` over `TIMEOUT`.
6. **Failure mapping.** `classify`: which `IOException`s can the JDK client and Spring's `JdkClientHttpRequest` really raise for timeout, refused,
   unknown host, TLS failure, reset, cancelled? Is a connection failure that wraps its cause recognised? A `ConnectException` raised after a
   request was sent on a reused connection? A `RemoteCallException` thrown from an `InputStream.read` through Spring's converters and
   `IntrospectingClientHttpResponse`; the observation's error tag; `problemStatus` reaching `ProblemDetailFactory`; a `RemoteCallException`
   thrown in a messaging handler or the outbox, or caught by `DataAccessFailure`.
7. **Redaction.** Patterns: what secret forms get through (form-encoded, XML, multi-line, `Set-Cookie`, a `Basic` token, JSON with escaped quotes, URL
   userinfo)? Catastrophic regex backtracking on an 8 KiB body? Does the 8 KiB cut interact badly with redaction or with multi-byte text? Do the
   exception's message, its `cause`'s message (URLs with query strings), a log line, or a span carry a secret or query string?
8. **Breaker semantics.** Client-name sharing across `builder()` calls with different settings (first config wins?), `computeIfAbsent` of the
   `HttpClient` when overrides differ; half-open under concurrency; failures counted when only one host of several is down.
9. **Lifecycle.** `destroy`: order against the web server's drain and the messaging consumers; `HttpClient.shutdown` and `awaitTermination` in a stop's
   time budget; calls after close; several factory instances; thread and selector leaks from clients built by name; `builder()` per request.
10. **Metrics and health.** `MeterBinder` called twice, or with a composite registry, overwriting the single `onAttempt` listener, double binding
    the tagged metrics; health exposing internal names; a `HealthIndicator` bean named `restclientHealthIndicator` truly reporting as `restclient`;
    `validate-group-membership`; Resilience4j's own auto-configuration seeing this registry. Boot's `uri` cap for `http.client.requests`.
11. **Properties.** Binding of nested records and boxed values, `Set<String>` merge, `@DefaultValue` on `Defaults` when `defaults` is absent, the
    constructor's `IllegalArgumentException` at bind time; generated metadata; the message's property name; a zero `deadline`; `max-wait` above the deadline.
12. **Resilience capability.** `ignore-exceptions` under the default config: does it reach instances that declare their own config, or `@Retry`? A class
    loader miss at the post-processor stage; the guard bean, being `static` and `SmartInitializingSingleton`, wired from an `Environment`; a guard that misses
    `@Bulkhead(type = THREADPOOL)`; the metadata the docs promise.
13. **Compatibility.** `@API(STABLE)` changes; the archetype sample, `GreetingClientTest`, and its timing; token relay and trace on retries; a consumer with a
    customizer that calls `requestFactory`; an app without Micrometer, without actuator, without `spring-boot-health`, without `resilience4j-micrometer`.

Parked by the owner, do not report: HI-0071, HI-0079, HI-0086. Accepted, do not report: HI-0078, HI-0087, HI-0088, HI-0093. Report concrete defects only, each
with file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`. Say so plainly if you find nothing in an area.
