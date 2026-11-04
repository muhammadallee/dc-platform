# Troubleshooting

**observed** means seen during this phase; **anticipated** means reasoned, not seen.

| What you see | Why | What to do | |
|---|---|---|---|
| A POST to a redirecting URL gives `status 307` as success | Only known idempotent methods follow redirects, since a 307 would send it again | Call the final URL, or handle the 3xx yourself | **observed** |
| `RemoteCallException` with kind `CIRCUIT_OPEN`, and nothing reached the downstream | The breaker is open after repeated failures | Check the `restclient` details in `/actuator/health/dependencies`; it tries again after `open-duration` | **observed** |
| `BULKHEAD_FULL` under load | More than `max-concurrent` calls at once, or responses not closed | Raise it, or close streamed bodies: a permit is held until the response closes | **observed** |
| A body failure called `DEADLINE` | `read-timeout` covers the whole response, and the deadline covers the call | Raise both for a large download | **observed** |
| Your own test client is refused after a few failures | Its breaker opened | Disable it for that client in the test: `circuit-breaker.enabled=false` | **observed** |
| Startup fails naming `retry.methods` | POST, PATCH or a custom method was listed | Remove it: those are never retried | **observed** in tests |
| Startup fails naming `enableAllMethodRetry` | A JVM option or `conf/net.properties` turns on the JDK's re-sending of POST | Remove it, and restart the JVM | **observed** in tests |
| Startup fails naming `thread-pool-bulkhead` | A thread-pool bulkhead with virtual threads | Use a semaphore bulkhead | **observed** in tests |
| A retried GET reaches the downstream more often than `max-attempts` | The JDK re-sends a GET once after a dropped connection | Expected: at worst 2 × `max-attempts` | **observed** |
| `bodySnippet()` is `[REDACTED]` | The body held a secret-named XML element | Read the downstream's own log | **observed** in tests |
| A snippet missing a word, as in "[REDACTED] failed" | "basic validation failed" looks like an auth scheme | Accepted: redaction errs safe | **anticipated** |
| A customizer's `requestFactory(...)` stopped retries | It replaced the factory that holds retry, deadline, breaker and bulkhead | Don't, or accept losing them | **anticipated** |
| A `RestClientException` instead of a `RemoteCallException` | A JSON library wrapped a body failure | Your caller gets 500, `retryable: false`; handle both if you catch them | **observed** in tests |
