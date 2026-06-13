package com.galactic.telemetry.pipeline;

import com.galactic.telemetry.model.EnrichedReservationEvent;
import com.galactic.telemetry.model.EnrichedRouteEvent;
import com.galactic.telemetry.model.ReservationConfirmedEvent;
import com.galactic.telemetry.model.RoutePlannedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Consumes reservation and route events from Kafka, enriches them,
 * and publishes enriched events back to Kafka.
 */
@Configuration
public class EventPipelineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EventPipelineConfiguration.class);

    private static final String PROCESSED_BY = "telemetry-pipeline";
    private static final double RISK_THRESHOLD_LOW = 0.3;
    private static final double RISK_THRESHOLD_HIGH = 0.7;
    private static final String METRIC_RESERVATION_LAG = "events.reservation.lag";

    @Bean
    public Function<ReservationConfirmedEvent, EnrichedReservationEvent> reservationPipeline(MeterRegistry meterRegistry) {

        Counter receivedCounter = Counter.builder("events.reservation.received")
                .description("Reservation events consumed from Kafka")
                .register(meterRegistry);
        Counter enrichedCounter = Counter.builder("events.reservation.enriched")
                .description("Reservation events enriched and published")
                .register(meterRegistry);
        DistributionSummary lagSummary = DistributionSummary.builder(METRIC_RESERVATION_LAG)
                .baseUnit("seconds")
                .description("End-to-end async lag: time from ReservationConfirmed produced "
                        + "(starport-registry/OutboxAppender) to enriched (telemetry-pipeline)")
                .register(meterRegistry);

        return event -> {
            // Feed the business IDs into MDC so the OTLP log appender (captureMdcAttributes:
            // reservationId,routeId) actually emits them — they live in the event payload, not in
            // baggage, so without this the attributes are always empty.
            putMdc("reservationId", String.valueOf(event.reservationId()));
            putMdc("routeId", event.routeCode());
            try {
                receivedCounter.increment();

                log.info(
                        "Processing ReservationConfirmedEvent: reservationId={} starport={} customer={}",
                        event.reservationId(),
                        event.starportCode(),
                        event.customerCode());

                if (event.producedAt() != null) {
                    double lagSeconds = Duration.between(event.producedAt(), Instant.now()).toMillis() / 1000.0;
                    if (lagSeconds >= 0) {
                        lagSummary.record(lagSeconds);
                    }
                }

                long durationHours = 0;
                if (event.startAt() != null && event.endAt() != null) {
                    durationHours = Duration.between(event.startAt(), event.endAt()).toHours();
                }

                enrichedCounter.increment();

                return new EnrichedReservationEvent(
                        "RESERVATION_CONFIRMED",
                        event.reservationId(),
                        event.status(),
                        event.starportCode(),
                        event.dockingBayLabel(),
                        event.customerCode(),
                        event.shipCode(),
                        event.routeCode(),
                        event.startAt(),
                        event.endAt(),
                        event.feeCharged(),
                        durationHours,
                        Instant.now(),
                        PROCESSED_BY);
            } finally {
                MDC.remove("reservationId");
                MDC.remove("routeId");
            }
        };
    }

    private static void putMdc(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    @Bean
    public Function<RoutePlannedEvent, EnrichedRouteEvent> routePipeline(MeterRegistry meterRegistry) {

        Counter receivedCounter = Counter.builder("events.route.received")
                .description("Route planned events consumed from Kafka")
                .register(meterRegistry);
        Counter enrichedCounter = Counter.builder("events.route.enriched")
                .description("Route events enriched and published")
                .register(meterRegistry);

        return event -> {
            putMdc("routeId", event.routeId());
            try {
                receivedCounter.increment();

                log.info(
                        "Processing RoutePlannedEvent: routeId={} {} -> {} eta={}h risk={}",
                        event.routeId(),
                        event.originPortId(),
                        event.destinationPortId(),
                        event.etaHours(),
                        event.riskScore());

                String riskLevel = classifyRisk(event.riskScore());

                enrichedCounter.increment();

                return new EnrichedRouteEvent(
                        "ROUTE_PLANNED",
                        event.routeId(),
                        event.originPortId(),
                        event.destinationPortId(),
                        event.shipClass(),
                        event.etaHours(),
                        event.riskScore(),
                        riskLevel,
                        event.plannedAt(),
                        Instant.now(),
                        PROCESSED_BY);
            } finally {
                MDC.remove("routeId");
            }
        };
    }

    private static String classifyRisk(double riskScore) {
        if (riskScore < RISK_THRESHOLD_LOW) {
            return "LOW";
        } else if (riskScore < RISK_THRESHOLD_HIGH) {
            return "MEDIUM";
        } else {
            return "HIGH";
        }
    }
}
