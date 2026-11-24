package com.galactic.fleet.activityfeed.pipeline;

import com.galactic.fleet.activityfeed.filter.ReservationIntakeFilter;
import com.galactic.fleet.activityfeed.filter.ReservationRecordFilter;
import com.galactic.fleet.activityfeed.filter.RiskClassificationFilter;
import com.galactic.fleet.activityfeed.filter.RouteExtractionFilter;
import com.galactic.fleet.activityfeed.filter.RouteRecordFilter;
import com.galactic.fleet.activityfeed.filter.StayDurationFilter;
import com.galactic.fleet.activityfeed.model.EnrichedReservationEvent;
import com.galactic.fleet.activityfeed.model.EnrichedRouteEvent;
import com.galactic.fleet.booking.events.ReservationConfirmed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Function;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composes the filters into the two pipelines of the module (Pipes &amp; Filters, ADR-0043). Both
 * take the {@link ReservationConfirmed} event itself — the published language of {@code booking} —
 * so nothing is copied into a local twin (ADR-0043). See {@link FleetEventListener} for delivery.
 *
 * <pre>
 * reservation:  intake  →  stay duration  →  record       →  telemetry.enriched-reservations
 * route:        route extraction  →  risk classification  →  record  →  telemetry.enriched-routes
 *               (null when the reservation has no route — the chain ends, nothing is published)
 * </pre>
 */
@Configuration
public class EventPipelineConfiguration {

    private static final String PROCESSED_BY = "telemetry-pipeline";
    private static final String MDC_RESERVATION_ID = "reservationId";
    private static final String MDC_ROUTE_ID = "routeId";

    @Bean
    public Function<ReservationConfirmed, EnrichedReservationEvent> reservationPipeline(
            MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        Function<ReservationConfirmed, EnrichedReservationEvent> filters = PipelineBuilder.<ReservationConfirmed>create(
                        meterRegistry)
                .stage("reservation.intake", new ReservationIntakeFilter(meterRegistry))
                .stage("reservation.duration", new StayDurationFilter())
                .stage("reservation.record", new ReservationRecordFilter(PROCESSED_BY, meterRegistry))
                .build();

        return event -> {
            // Feed the business IDs into MDC so the OTLP log appender (captureMdcAttributes:
            // reservationId,routeId) actually emits them — they live in the event payload, not in
            // baggage, so without this the attributes are always empty.
            putMdc(MDC_RESERVATION_ID, String.valueOf(event.reservationId()));
            putMdc(MDC_ROUTE_ID, event.route() == null ? null : event.route().routeCode());
            try {
                // An explicit span, so the enrichment is a visible, timeable node in the trace.
                return Observation.createNotStarted("events.reservation.enrich", observationRegistry)
                        .lowCardinalityKeyValue("processedBy", PROCESSED_BY)
                        .observe(() -> filters.apply(event));
            } finally {
                MDC.remove(MDC_RESERVATION_ID);
                MDC.remove(MDC_ROUTE_ID);
            }
        };
    }

    @Bean
    public Function<ReservationConfirmed, EnrichedRouteEvent> routePipeline(
            MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        // The span covers the enrichment proper, not the extraction: a reservation without a route
        // ends the chain in the first stage and must not leave an empty "route enriched" span behind.
        Function<ReservationConfirmed.Route, EnrichedRouteEvent> enrichment =
                PipelineBuilder.<ReservationConfirmed.Route>create(meterRegistry)
                        .stage("route.risk", new RiskClassificationFilter())
                        .stage("route.record", new RouteRecordFilter(PROCESSED_BY, meterRegistry))
                        .build();

        return PipelineBuilder.<ReservationConfirmed>create(meterRegistry)
                .stage("route.extraction", new RouteExtractionFilter(meterRegistry))
                .stage("route.enrichment", route -> {
                    putMdc(MDC_ROUTE_ID, route.routeCode());
                    try {
                        return Observation.createNotStarted("events.route.enrich", observationRegistry)
                                .lowCardinalityKeyValue("processedBy", PROCESSED_BY)
                                .observe(() -> enrichment.apply(route));
                    } finally {
                        MDC.remove(MDC_ROUTE_ID);
                    }
                })
                .build();
    }

    private static void putMdc(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }
}
