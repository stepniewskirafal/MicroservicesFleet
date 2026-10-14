package com.galactic.fleet.booking.events;

import java.math.BigDecimal;
import java.time.Instant;
import org.jmolecules.event.annotation.DomainEvent;

/**
 * Application event of the {@code booking} module: a reservation was stored as CONFIRMED.
 *
 * <p>The only type the module exposes (named interface {@code events}); nobody calls into the module.
 * It is published inside the transaction that stores the reservation. The Event Publication Registry
 * writes it to {@code event_publication} in that transaction and hands it to
 * {@code @ApplicationModuleListener}s after the commit, on another thread (ADR-0046). A listener
 * therefore never sees a reservation that was rolled back, and may see the same event twice
 * (at-least-once).
 *
 * <p>The event is the published language of the module: it carries everything a listener needs, so
 * no listener has to call back for more.
 *
 * @param producedAt when the event was published; listeners use it to measure async lag
 * @param route the planned route, or {@code null} when the reservation was made without one
 * @param traceparent W3C trace context of the request that confirmed the reservation, or {@code
 *     null} when there was none. The registry stores the event but not the thread's trace context,
 *     so a listener handling a <em>re-delivered</em> event can only rejoin the original trace through
 *     this field (ADR-0048).
 */
@DomainEvent
public record ReservationConfirmed(
        Long reservationId,
        String status,
        String starportCode,
        String dockingBayLabel,
        String customerCode,
        String shipCode,
        Instant startAt,
        Instant endAt,
        BigDecimal feeCharged,
        Instant producedAt,
        Route route,
        String traceparent) {

    /** An event published outside any trace. */
    public ReservationConfirmed(
            Long reservationId,
            String status,
            String starportCode,
            String dockingBayLabel,
            String customerCode,
            String shipCode,
            Instant startAt,
            Instant endAt,
            BigDecimal feeCharged,
            Instant producedAt,
            Route route) {
        this(
                reservationId,
                status,
                starportCode,
                dockingBayLabel,
                customerCode,
                shipCode,
                startAt,
                endAt,
                feeCharged,
                producedAt,
                route,
                null);
    }

    /** The route planned for the reservation, as it was returned to the caller. */
    public record Route(
            String routeCode,
            String originPortId,
            String destinationPortId,
            String shipClass,
            double etaHours,
            double riskScore,
            Instant plannedAt) {}
}
