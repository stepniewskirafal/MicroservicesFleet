package com.galactic.fleet.activityfeed.filter;

import com.galactic.fleet.activityfeed.model.EnrichedReservationEvent;
import com.galactic.fleet.activityfeed.model.TimedReservation;
import com.galactic.fleet.booking.events.ReservationConfirmed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.function.Function;

/**
 * Last filter of the reservation chain: builds the record that leaves on {@code
 * telemetry.enriched-reservations}.
 */
public class ReservationRecordFilter implements Function<TimedReservation, EnrichedReservationEvent> {

    private static final String EVENT_TYPE = "RESERVATION_CONFIRMED";

    private final String processedBy;
    private final Counter enrichedCounter;

    public ReservationRecordFilter(String processedBy, MeterRegistry meterRegistry) {
        this.processedBy = processedBy;
        this.enrichedCounter = Counter.builder("events.reservation.enriched")
                .description("Reservation events enriched and published")
                .register(meterRegistry);
    }

    @Override
    public EnrichedReservationEvent apply(TimedReservation timed) {
        ReservationConfirmed event = timed.reservation();
        enrichedCounter.increment();
        return new EnrichedReservationEvent(
                EVENT_TYPE,
                event.reservationId(),
                event.status(),
                event.starportCode(),
                event.dockingBayLabel(),
                event.customerCode(),
                event.shipCode(),
                event.route() == null ? null : event.route().routeCode(),
                event.startAt(),
                event.endAt(),
                event.feeCharged(),
                timed.durationHours(),
                Instant.now(),
                processedBy);
    }
}
