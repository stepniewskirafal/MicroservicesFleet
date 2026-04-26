package com.galactic.telemetry.pipeline;

import com.galactic.telemetry.model.EnrichedReservationEvent;
import com.galactic.telemetry.model.EnrichedRouteEvent;
import com.galactic.telemetry.model.ReservationCreatedEvent;
import com.galactic.telemetry.model.RoutePlannedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final String OBS_PIPELINE_PROCESS = "telemetry.pipeline.process";
    private static final String METRIC_REQUESTS_TOTAL = "telemetry.pipeline.requests.total";

    @Bean
    public Function<ReservationCreatedEvent, EnrichedReservationEvent> reservationPipeline(
            MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {

        Counter receivedCounter = Counter.builder("events.reservation.received")
                .description("Reservation events consumed from Kafka")
                .register(meterRegistry);
        Counter enrichedCounter = Counter.builder("events.reservation.enriched")
                .description("Reservation events enriched and published")
                .register(meterRegistry);
        Counter requestsCounter = Counter.builder(METRIC_REQUESTS_TOTAL)
                .description("Total telemetry pipeline invocations (RED Rate)")
                .tag("pipeline", "reservation")
                .register(meterRegistry);

        return event -> Observation.createNotStarted(OBS_PIPELINE_PROCESS, observationRegistry)
                .lowCardinalityKeyValue("pipeline", "reservation")
                .observe(() -> {
                    requestsCounter.increment();
                    receivedCounter.increment();

                    log.info(
                            "Processing ReservationCreatedEvent: reservationId={} starport={} customer={}",
                            event.reservationId(),
                            event.starportCode(),
                            event.customerCode());

                    try {
                        long durationHours = 0;
                        if (event.startAt() != null && event.endAt() != null) {
                            durationHours = Duration.between(event.startAt(), event.endAt())
                                    .toHours();
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
                    } catch (RuntimeException ex) {
                        PipelineConfiguration.recordError(meterRegistry, "reservation", "exception");
                        throw ex;
                    }
                });
    }

    @Bean
    public Function<RoutePlannedEvent, EnrichedRouteEvent> routePipeline(
            MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {

        Counter receivedCounter = Counter.builder("events.route.received")
                .description("Route planned events consumed from Kafka")
                .register(meterRegistry);
        Counter enrichedCounter = Counter.builder("events.route.enriched")
                .description("Route events enriched and published")
                .register(meterRegistry);
        Counter requestsCounter = Counter.builder(METRIC_REQUESTS_TOTAL)
                .description("Total telemetry pipeline invocations (RED Rate)")
                .tag("pipeline", "route")
                .register(meterRegistry);

        return event -> Observation.createNotStarted(OBS_PIPELINE_PROCESS, observationRegistry)
                .lowCardinalityKeyValue("pipeline", "route")
                .observe(() -> {
                    requestsCounter.increment();
                    receivedCounter.increment();

                    log.info(
                            "Processing RoutePlannedEvent: routeId={} {} -> {} eta={}h risk={}",
                            event.routeId(),
                            event.originPortId(),
                            event.destinationPortId(),
                            event.etaHours(),
                            event.riskScore());

                    try {
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
                    } catch (RuntimeException ex) {
                        PipelineConfiguration.recordError(meterRegistry, "route", "exception");
                        throw ex;
                    }
                });
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
