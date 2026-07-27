package ae.gov.dubaicustoms.example.eventdriven.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The consumer service: subscribes to {@code ShipmentRequested} with {@code @EventHandler}. When a
 * handler throws, the platform retries per {@code dc.platform.messaging.handler.retry.*} and, once
 * attempts are exhausted, republishes the message to the DLQ destination
 * ({@code channel + dc.platform.messaging.dlq.suffix}) — all without broker code here.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
