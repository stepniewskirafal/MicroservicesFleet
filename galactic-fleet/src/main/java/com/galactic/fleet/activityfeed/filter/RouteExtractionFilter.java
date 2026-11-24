package com.galactic.fleet.activityfeed.filter;

import com.galactic.fleet.booking.events.ReservationConfirmed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First filter of the route chain: takes the planned route out of the event.
 *
 * <p>A reservation made without a route has none — the filter returns {@code null}, which ends the
 * chain, so no route record is produced. This is the one place where the chain drops a message.
 */
public class RouteExtractionFilter implements Function<ReservationConfirmed, ReservationConfirmed.Route> {

    private static final Logger log = LoggerFactory.getLogger(RouteExtractionFilter.class);

    private final Counter receivedCounter;

    public RouteExtractionFilter(MeterRegistry meterRegistry) {
        this.receivedCounter = Counter.builder("events.route.received")
                .description("Planned routes received with a confirmed reservation")
                .register(meterRegistry);
    }

    @Override
    @Nullable
    public ReservationConfirmed.Route apply(ReservationConfirmed event) {
        ReservationConfirmed.Route route = event.route();
        if (route == null) {
            return null;
        }
        receivedCounter.increment();
        log.info(
                "Processing planned route: routeId={} {} -> {} eta={}h risk={}",
                route.routeCode(),
                route.originPortId(),
                route.destinationPortId(),
                route.etaHours(),
                route.riskScore());
        return route;
    }
}
