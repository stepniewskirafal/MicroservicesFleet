package com.galactic.fleet.activityfeed.internal;

import com.galactic.fleet.booking.events.ReservationConfirmed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Computes what leaves the system about a confirmed reservation: an enriched reservation record and,
 * when a route was planned, an enriched route record. The input is the {@link ReservationConfirmed}
 * event itself — it is the published language of the publishing module, so it is read directly
 * rather than copied into a local twin (ADR-0046). See {@link FleetEventListener} for delivery.
 */
@Configuration
public class EventPipelineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EventPipelineConfiguration.class);

    private static final String PROCESSED_BY = "telemetry-pipeline";
    private static final double RISK_THRESHOLD_LOW = 0.3;
    private static final double RISK_THRESHOLD_HIGH = 0.7;
    private static final String METRIC_RESERVATION_LAG = "events.reservation.lag";

    @Bean
    public Function<ReservationConfirmed, EnrichedReservationEvent> reservationPipeline(
            MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {

        Counter receivedCounter = Counter.builder("events.reservation.received")
                .description("Reservation events received from the publishing module")
                .register(meterRegistry);
        Counter enrichedCounter = Counter.builder("events.reservation.enriched")
                .description("Reservation events enriched and published")
                .register(meterRegistry);
        DistributionSummary lagSummary = DistributionSummary.builder(METRIC_RESERVATION_LAG)
                .baseUnit("seconds")
                .description("End-to-end async lag: time from ReservationConfirmed published "
                        + "(inside the reservation transaction) to enriched (after the commit, on another thread)")
                .register(meterRegistry);

        return event -> {
            // Feed the business IDs into MDC so the OTLP log appender (captureMdcAttributes:
            // reservationId,routeId) actually emits them — they live in the event payload, not in
            // baggage, so without this the attributes are always empty.
            putMdc("reservationId", String.valueOf(event.reservationId()));
            putMdc("routeId", routeCodeOf(event));
            try {
                // An explicit span, so the enrichment is a visible, timeable node in the trace.
                return Observation.createNotStarted("events.reservation.enrich", observationRegistry)
                        .lowCardinalityKeyValue("processedBy", PROCESSED_BY)
                        .observe(() -> enrichReservation(event, receivedCounter, enrichedCounter, lagSummary));
            } finally {
                MDC.remove("reservationId");
                MDC.remove("routeId");
            }
        };
    }

    private EnrichedReservationEvent enrichReservation(
            ReservationConfirmed event,
            Counter receivedCounter,
            Counter enrichedCounter,
            DistributionSummary lagSummary) {
        receivedCounter.increment();

        log.info(
                "Processing ReservationConfirmed: reservationId={} starport={} customer={}",
                event.reservationId(),
                event.starportCode(),
                event.customerCode());

        if (event.producedAt() != null) {
            double lagSeconds =
                    Duration.between(event.producedAt(), Instant.now()).toMillis() / 1000.0;
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
                routeCodeOf(event),
                event.startAt(),
                event.endAt(),
                event.feeCharged(),
                durationHours,
                Instant.now(),
                PROCESSED_BY);
    }

    private static String routeCodeOf(ReservationConfirmed event) {
        return event.route() == null ? null : event.route().routeCode();
    }

    private static void putMdc(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    @Bean
    public Function<ReservationConfirmed.Route, EnrichedRouteEvent> routePipeline(
            MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {

        Counter receivedCounter = Counter.builder("events.route.received")
                .description("Planned routes received with a confirmed reservation")
                .register(meterRegistry);
        Counter enrichedCounter = Counter.builder("events.route.enriched")
                .description("Route records enriched and published")
                .register(meterRegistry);

        return route -> {
            putMdc("routeId", route.routeCode());
            try {
                return Observation.createNotStarted("events.route.enrich", observationRegistry)
                        .lowCardinalityKeyValue("processedBy", PROCESSED_BY)
                        .observe(() -> enrichRoute(route, receivedCounter, enrichedCounter));
            } finally {
                MDC.remove("routeId");
            }
        };
    }

    private EnrichedRouteEvent enrichRoute(
            ReservationConfirmed.Route route, Counter receivedCounter, Counter enrichedCounter) {
        receivedCounter.increment();

        log.info(
                "Processing planned route: routeId={} {} -> {} eta={}h risk={}",
                route.routeCode(),
                route.originPortId(),
                route.destinationPortId(),
                route.etaHours(),
                route.riskScore());

        String riskLevel = classifyRisk(route.riskScore());

        enrichedCounter.increment();

        return new EnrichedRouteEvent(
                "ROUTE_PLANNED",
                route.routeCode(),
                route.originPortId(),
                route.destinationPortId(),
                route.shipClass(),
                route.etaHours(),
                route.riskScore(),
                riskLevel,
                route.plannedAt(),
                Instant.now(),
                PROCESSED_BY);
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
