# Lab: your first service

You generate a service from the archetype, give it one endpoint that fails in two ways, and follow a failure from the
error body a caller sees to the log line support will read. About ten minutes, most of it Maven.

**You need:** JDK 25, Maven 3.9, `curl`, this repository, and `PLATFORM_HOME` set to it ([labs](../index.md)). No
Docker. **Capabilities:** [core](../../modules/core.md) and [errors](../../modules/errors.md); background in
[chapter 1](../../book/chapters/01-core.md) and [chapter 2](../../book/chapters/02-errors-validation.md).

## Step 0 — Put the platform in a local Maven repository

Nothing is published yet. This builds your checkout into a repository of its own, `~/.m2-hardening/lab-first-service`,
with no older platform artifact in it:

```bash lab-step
PLATFORM_REPO=$(bash "$PLATFORM_HOME/tooling/hardening/native-repo.sh" lab-first-service)
export PLATFORM_REPO
echo "platform repository: $PLATFORM_REPO"
```

## Step 1 — Generate the service

```bash lab-step
mvn -B -q -o -Dmaven.repo.local="$PLATFORM_REPO" \
    org.apache.maven.plugins:maven-archetype-plugin:3.1.2:generate -DarchetypeCatalog=local \
    -DarchetypeGroupId=ae.gov.dubaicustoms.platform -DarchetypeArtifactId=platform-service-archetype \
    -DarchetypeVersion=1.0.0-SNAPSHOT -DgroupId=com.example -DartifactId=first-service \
    -Dpackage=com.example.orders -DinteractiveMode=false
cd first-service
```

You get core, errors, logging and observability, Spring's validation, and a sample `/hello` endpoint. No feature
options are needed for this lab.

## Step 2 — Configuration

None. Correlation and the error contract work with only the starters; `application.yml` names the service and nothing
else. The only setting this lab uses is the port, on the command line in step 4.

## Step 3 — An endpoint that fails

A business refusal, with a code of your own, and an unexpected failure whose message names an internal host:

```java lab-file src/main/java/com/example/orders/OrderController.java
package com.example.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    @PostMapping("/orders/{id}/cancel")
    String cancel(@PathVariable long id) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "order %d is already paid".formatted(id));
        problem.setProperty("code", "ORD-0409");
        throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
    }

    @GetMapping("/orders/{id}/invoice")
    String invoice(@PathVariable long id) {
        throw new IllegalStateException("invoice store db-7.internal refused the connection");
    }
}
```

Only Spring types: `ErrorResponseException` and `ProblemDetail`. There is no `@RestControllerAdvice`; the platform's
does the rest.

## Step 4 — Build and start

The build runs the generated tests too, including the conformance test that checks the service uses Spring types.

```bash lab-step
mvn -B -q -o -Dmaven.repo.local="$PLATFORM_REPO" package
java -jar target/first-service-*.jar --server.port=18080 > app.log 2>&1 &
echo $! > ../app.pid
for attempt in $(seq 1 90); do
  curl -fs http://localhost:18080/actuator/health > /dev/null && break
  sleep 1
done
```

```bash lab-check
curl -fs http://localhost:18080/actuator/health -o health.json
grep -q '"status":"UP"' health.json
```

## Step 5 — A business failure, with your trace

Send a `traceparent`, as any traced caller does:

```bash lab-step
TRACE=4bf92f3577b34da6a3ce929d0e0e4736
curl -s -X POST -H "traceparent: 00-$TRACE-00f067aa0ba902b7-01" http://localhost:18080/orders/42/cancel | tee cancel.json
echo
```

```json
{"detail":"order 42 is already paid","instance":"/orders/42/cancel","status":409,"title":"ORD-0409",
 "type":"urn:dc:problem:ORD-0409","code":"ORD-0409","traceId":"4bf92f3577b34da6a3ce929d0e0e4736",
 "timestamp":"2026-11-19T12:46:42.991006300Z","retryable":false}
```

Your code and your detail, plus the platform's `traceId`, `timestamp` and `retryable`:

```bash lab-check
grep -q '"code":"ORD-0409"' cancel.json
grep -q '"detail":"order 42 is already paid"' cancel.json
grep -q "\"traceId\":\"$TRACE\"" cancel.json
```

## Step 6 — An unexpected failure, and its log line

```bash lab-step
curl -s -H "traceparent: 00-$TRACE-00f067aa0ba902b7-01" http://localhost:18080/orders/42/invoice | tee invoice.json
echo
```

The caller gets a 500 with code `DC-CORE-0500`, the same `traceId`, and a generic detail. The internal host name is
not in the body:

```bash lab-check
grep -q '"code":"DC-CORE-0500"' invoice.json
grep -q '"detail":"An unexpected error occurred."' invoice.json
grep -q "\"traceId\":\"$TRACE\"" invoice.json
test "$(grep -c 'db-7' invoice.json)" -eq 0
```

Support takes the `traceId` from the body and searches the log. The ERROR line carries the same trace ID and the full
exception, host name included:

```bash lab-check
test "$(grep "$TRACE" app.log | grep -c 'db-7.internal')" -gt 0
```

## Step 7 — Try this: no `traceparent`

Call it the way a caller without tracing would:

```bash lab-step
curl -s http://localhost:18080/orders/42/invoice | tee fresh.json
echo
```

The platform starts a new trace and reports it. It is a new 32-hex ID, not yours, and it is in the log too:

```bash lab-check
grep -Eq '"traceId":"[0-9a-f]{32}"' fresh.json
test "$(grep -c "$TRACE" fresh.json)" -eq 0
FRESH=$(grep -Eo '"traceId":"[0-9a-f]{32}"' fresh.json | cut -d'"' -f4)
grep -q "$FRESH" app.log
```

## Step 8 — Clean up

```bash lab-step
kill "$(cat ../app.pid)"
rm ../app.pid
```

The generated project stays in the lab directory; delete it when you are done. The local repository stays in
`~/.m2-hardening/lab-first-service` for the next lab.

## Where next

- [Errors module page](../../modules/errors.md): every code, and how to add a field to every error body with an
  `ErrorResponse.Interceptor`.
- [Core module page](../../modules/core.md): the trace across threads and executors.
- [The core + errors sample](../../samples/core-errors/README.md): what was proven, and how to operate it.
