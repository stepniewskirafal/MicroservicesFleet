package com.galactic.fleet.booking.service;

import com.galactic.fleet.booking.events.ReservationConfirmed;
import com.galactic.fleet.booking.repository.StarportPersistenceFacade;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transaction of a reservation (ADR-0043): lock a free bay, store the reservation as
 * CONFIRMED and publish {@link ReservationConfirmed}.
 *
 * <p>The event is published <em>inside</em> the transaction on purpose. The Event Publication
 * Registry stores it in {@code event_publication} with the reservation, so both commit or neither
 * does; listeners in other modules run after the commit, on another thread, in their own transaction.
 * The transaction itself does no I/O besides the database, so the bay lock is held only briefly.
 */
@Service
@Slf4j
class ReservationConfirmer {

    private static final String OBSERVATION_ALLOCATE = "reservations.bay.allocate";
    private static final String OBSERVATION_CONFIRM = "reservations.confirm";
    private static final String TRACEPARENT = "traceparent";

    private final StarportPersistenceFacade persistenceFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    ReservationConfirmer(
            StarportPersistenceFacade persistenceFacade,
            ApplicationEventPublisher eventPublisher,
            ObservationRegistry observationRegistry,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<Propagator> propagator) {
        this(
                persistenceFacade,
                eventPublisher,
                observationRegistry,
                tracer.getIfAvailable(() -> Tracer.NOOP),
                propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    ReservationConfirmer(
            StarportPersistenceFacade persistenceFacade,
            ApplicationEventPublisher eventPublisher,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            Propagator propagator) {
        this.persistenceFacade = persistenceFacade;
        this.eventPublisher = eventPublisher;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Transactional
    public Reservation confirm(ReserveBayCommand command, BigDecimal fee, Route route) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(fee, "fee must not be null");
        return Observation.createNotStarted(OBSERVATION_CONFIRM, observationRegistry)
                .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                .observe(() -> {
                    Reservation reservation = Observation.createNotStarted(OBSERVATION_ALLOCATE, observationRegistry)
                            .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                            .lowCardinalityKeyValue(
                                    "shipClass", command.shipClass().name())
                            .observe(() -> persistenceFacade.createConfirmedReservation(command, fee, route));

                    eventPublisher.publishEvent(toEvent(reservation, command, route));
                    log.info(
                            "Reservation confirmed: reservationId={}, starport={}, ship={}, customer={}, window=[{}..{}]",
                            reservation.getId(),
                            command.destinationStarportCode(),
                            command.shipCode(),
                            command.customerCode(),
                            command.startAt(),
                            command.endAt());
                    return reservation;
                });
    }

    /**
     * @param route the route as it was planned — not the one read back with the stored reservation,
     *     which no longer knows when it was planned (the database does not keep {@code plannedAt})
     */
    private ReservationConfirmed toEvent(Reservation reservation, ReserveBayCommand command, Route route) {
        return new ReservationConfirmed(
                reservation.getId(),
                reservation.getStatus().name(),
                reservation.getStarport() != null ? reservation.getStarport().getCode() : null,
                reservation.getDockingBay() != null
                        ? reservation.getDockingBay().getBayLabel()
                        : null,
                reservation.getCustomer() != null ? reservation.getCustomer().getCustomerCode() : null,
                reservation.getShip() != null ? reservation.getShip().getShipCode() : null,
                reservation.getStartAt(),
                reservation.getEndAt(),
                reservation.getFeeCharged(),
                Instant.now(),
                route == null
                        ? null
                        : new ReservationConfirmed.Route(
                                route.getRouteCode(),
                                route.getStartStarportCode(),
                                route.getDestinationStarportCode(),
                                command.shipClass().name(),
                                route.getEtaHours(),
                                route.getRiskScore(),
                                route.getPlannedAt()),
                currentTraceparent());
    }

    /**
     * The W3C {@code traceparent} of the current span, or {@code null} outside a trace. It travels
     * in the event because the registry persists the event, not the thread's trace context — a
     * re-delivered event would otherwise start a new, unrelated trace (ADR-0043).
     */
    private String currentTraceparent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }
}
