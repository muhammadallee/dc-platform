# Lab: logs, metrics and a trace

You give a generated service three endpoints and watch what the platform does with them: a key-value pair becomes a
JSON field, a password is masked, a request is counted in Prometheus, and one trace runs through two HTTP calls. About
ten minutes, most of it Maven.

**You need:** JDK 25, Maven 3.9, `curl`, this repository, and `PLATFORM_HOME` set to it ([labs](../index.md)). No
Docker. **Capabilities:** [logging](../../modules/logging.md) and [observability](../../modules/observability.md);
background in [chapter 3](../../book/chapters/03-logging-observability.md).

## Step 0 — Put the platform in a local Maven repository

Nothing is published yet. This builds your checkout into a repository of its own,
`~/.m2-hardening/lab-logging-observability`, with no older platform artifact in it:

```bash lab-step
PLATFORM_REPO=$(bash "$PLATFORM_HOME/tooling/hardening/native-repo.sh" lab-logging-observability)
export PLATFORM_REPO
echo "platform repository: $PLATFORM_REPO"
```

## Step 1 — Generate the service

```bash lab-step
mvn -B -q -o -Dmaven.repo.local="$PLATFORM_REPO" \
    org.apache.maven.plugins:maven-archetype-plugin:3.1.2:generate -DarchetypeCatalog=local \
    -DarchetypeGroupId=ae.gov.dubaicustoms.platform -DarchetypeArtifactId=platform-service-archetype \
    -DarchetypeVersion=1.0.0-SNAPSHOT -DgroupId=com.example -DartifactId=shipping \
    -Dpackage=com.example.shipping -DinteractiveMode=false
cd shipping
```

Logging and observability come with every generated service. No feature options are needed.

## Step 2 — Configuration

None. JSON logs, masking, metrics and tracing work with only the starters. The ports go on the command line in step 4:
the API on 18080, actuator on its own port, 18081.

## Step 3 — Three endpoints

One logs a field, one logs a password, one calls the first over HTTP. Only Spring, SLF4J and Micrometer types:

```java lab-file src/main/java/com/example/shipping/ShipmentController.java
package com.example.shipping;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
class ShipmentController {

    private static final Logger log = LoggerFactory.getLogger(ShipmentController.class);

    private final ObservationRegistry observations;
    private final Environment environment;

    ShipmentController(ObservationRegistry observations, Environment environment) {
        this.observations = observations;
        this.environment = environment;
    }

    @GetMapping("/shipments/{id}")
    String shipment(@PathVariable String id) {
        log.atInfo().addKeyValue("shipmentId", id).log("shipment viewed");
        return "shipment " + id;
    }

    @PostMapping("/login")
    String login(@RequestParam String user, @RequestParam String password) {
        log.atInfo().addKeyValue("user", user).addKeyValue("password", password)
                .log("login attempt with password={}", password);
        return "ok";
    }

    @GetMapping("/track/{id}")
    String track(@PathVariable String id) {
        log.info("tracking request");
        // An observed client: the outbound call carries this request's trace in its traceparent header.
        RestClient self = RestClient.builder().observationRegistry(observations)
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port")).build();
        return self.get().uri("/shipments/{id}", id).retrieve().body(String.class);
    }
}
```

## Step 4 — Build and start

```bash lab-step
mvn -B -q -o -Dmaven.repo.local="$PLATFORM_REPO" package
java -jar target/shipping-*.jar --server.port=18080 --management.server.port=18081 > app.log 2>&1 &
echo $! > ../app.pid
for attempt in $(seq 1 90); do
  curl -fs http://localhost:18081/actuator/health/readiness > /dev/null && break
  sleep 1
done
```

Every line on stdout is one JSON object: the platform turns the banner off so a log shipper never meets a line it
cannot parse:

```bash lab-check
curl -fs http://localhost:18081/actuator/health/readiness | grep -q '"status":"UP"'
test "$(grep -cv '^{' app.log)" -eq 0
```

## Step 5 — A field you can query

```bash lab-step
curl -s http://localhost:18080/shipments/SHP-42; echo
grep '"message":"shipment viewed"' app.log
```

```json
{"@timestamp":"…","message":"shipment viewed","logger":"com.example.shipping.ShipmentController","level":"INFO",
 "traceId":"…","spanId":"…","shipmentId":"SHP-42","service":"shipping"}
```

The key-value pair is its own field, next to the trace fields core puts in the MDC and the `service` field:

```bash lab-check
grep '"message":"shipment viewed"' app.log | grep -q '"shipmentId":"SHP-42"'
grep '"message":"shipment viewed"' app.log | grep -q '"service":"shipping"'
grep '"message":"shipment viewed"' app.log | grep -Eq '"traceId":"[0-9a-f]{32}"'
```

## Step 6 — A password that never reaches the log

```bash lab-step
curl -s -X POST "http://localhost:18080/login?user=ali&password=hunter2-lab"; echo
grep '"message":"login attempt' app.log
```

The `password` field is masked whole because of its name, and the message is masked by pattern:

```bash lab-check
test "$(grep -c 'hunter2-lab' app.log)" -eq 0
grep '"message":"login attempt' app.log | grep -q '"password":"\*\*\*\*"'
grep -q '"message":"login attempt with password=\*\*\*\*"' app.log
```

## Step 7 — A metric, on the management port

Spring Boot counts every request; the platform adds `service`, `env` and `platform.version` to every meter. The
scrape is on 18081 only:

```bash lab-step
curl -s http://localhost:18081/actuator/prometheus | grep '^http_server_requests_seconds_count' | grep 'shipments'
```

```bash lab-check
curl -s http://localhost:18081/actuator/prometheus > scrape.txt
grep '^http_server_requests_seconds_count' scrape.txt | grep 'uri="/shipments/{id}"' | grep -q 'service="shipping"'
test "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/actuator/prometheus)" = 404
```

The `uri` tag is the route template, not `/shipments/SHP-42`: one series per route, however many shipments there
are.

## Step 8 — One trace across two calls

Send a `traceparent`, as a traced caller does. `/track` logs a line, then calls `/shipments` over HTTP:

```bash lab-step
TRACE=4bf92f3577b34da6a3ce929d0e0e4736
curl -s -H "traceparent: 00-$TRACE-00f067aa0ba902b7-01" http://localhost:18080/track/SHP-7; echo
grep "$TRACE" app.log
```

Both lines carry your trace ID: the outbound call sent it on in its own `traceparent`, and the second request
continued it:

```bash lab-check
grep '"message":"tracking request"' app.log | grep -q "\"traceId\":\"$TRACE\""
grep '"shipmentId":"SHP-7"' app.log | grep -q "\"traceId\":\"$TRACE\""
```

## Step 9 — Try this: mask your own identifiers

Restart with one extra pattern, and the shipment IDs are masked too:

```bash lab-step
kill "$(cat ../app.pid)"
for attempt in $(seq 1 30); do kill -0 "$(cat ../app.pid)" 2>/dev/null || break; sleep 1; done
java -jar target/shipping-*.jar --server.port=18080 --management.server.port=18081 \
    '--dc.platform.logging.redaction.patterns[0]=SHP-[0-9]+' > app2.log 2>&1 &
echo $! > ../app.pid
for attempt in $(seq 1 90); do
  curl -fs http://localhost:18081/actuator/health/readiness > /dev/null && break
  sleep 1
done
curl -s http://localhost:18080/shipments/SHP-99; echo
```

The response still says `SHP-99`: masking is for logs, never for what your API returns:

```bash lab-check
grep '"message":"shipment viewed"' app2.log | grep -q '"shipmentId":"\*\*\*\*"'
test "$(grep -c 'SHP-99' app2.log)" -eq 0
```

## Step 10 — Clean up

```bash lab-step
APP_PID=$(cat ../app.pid)
kill "$APP_PID"
for attempt in $(seq 1 30); do
  kill -0 "$APP_PID" 2>/dev/null || break
  sleep 1
done
```

The service shuts down gracefully, and its last lines are JSON too:

```bash lab-check
test "$(kill -0 "$APP_PID" 2>/dev/null && echo running)" != running
test "$(grep -cv '^{' app2.log)" -eq 0
rm ../app.pid
```

## Where next

- [Logging module page](../../modules/logging.md): every masking rule, strict MDC mode, and the back-off limits.
- [Observability module page](../../modules/observability.md): the management port, health groups and OTLP export.
- [The logging + observability sample](../../samples/logging-observability/README.md): what the consumer run proved.
