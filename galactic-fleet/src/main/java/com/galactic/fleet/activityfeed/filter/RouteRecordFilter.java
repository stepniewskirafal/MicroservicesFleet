package com.galactic.fleet.activityfeed.filter;

import com.galactic.fleet.activityfeed.model.ClassifiedRoute;
import com.galactic.fleet.activityfeed.model.EnrichedRouteEvent;
import com.galactic.fleet.booking.events.ReservationConfirmed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.function.Function;

/**
 * Last filter of the route chain: builds the record that leaves on {@code telemetry.enriched-routes}.
 */
public class RouteRecordFilter implements Function<ClassifiedRoute, EnrichedRouteEvent> {

    private static final String EVENT_TYPE = "ROUTE_PLANNED";

    private final String processedBy;
    private final Counter enrichedCounter;

    public RouteRecordFilter(String processedBy, MeterRegistry meterRegistry) {
        this.processedBy = processedBy;
        this.enrichedCounter = Counter.builder("events.route.enriched")
                .description("Route records enriched and published")
                .register(meterRegistry);
    }

    @Override
    public EnrichedRouteEvent apply(ClassifiedRoute classified) {
        ReservationConfirmed.Route route = classified.route();
        enrichedCounter.increment();
        return new EnrichedRouteEvent(
                EVENT_TYPE,
                route.routeCode(),
                route.originPortId(),
                route.destinationPortId(),
                route.shipClass(),
                route.etaHours(),
                route.riskScore(),
                classified.riskLevel(),
                route.plannedAt(),
                Instant.now(),
                processedBy);
    }
}
