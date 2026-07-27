package ae.gov.dubaicustoms.example.eventdriven.producer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The producer service: exposes an endpoint that publishes {@code ShipmentRequested} events over the
 * platform transport (in-memory by default, RabbitMQ under the {@code rabbit} profile). It never
 * touches a broker client — publishing goes through {@code EventPublisher}.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
