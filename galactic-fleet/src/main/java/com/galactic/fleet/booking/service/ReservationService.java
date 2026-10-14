package com.galactic.fleet.booking.service;

import com.galactic.fleet.booking.service.feecalculator.FeeCalculator;
import com.galactic.fleet.booking.service.routeplanner.RoutePlanner;
import com.galactic.fleet.booking.service.routeplanner.RouteRejectedException;
import com.galactic.fleet.booking.service.validation.ReserveBayValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Reserves a docking bay (ADR-0046).
 *
 * <ol>
 *   <li>validate the command,
 *   <li>compute the fee and plan the route — pure computations, <b>outside any transaction</b>,
 *   <li>one transaction: lock a free bay, store the reservation as CONFIRMED, publish
 *       {@code ReservationConfirmed} ({@link ReservationConfirmer}).
 * </ol>
 *
 * Nothing is written before step 3, so a rejected route or a full starport leaves no trace to
 * compensate — there is no HOLD state and no reaper.
 */
@RequiredArgsConstructor
@Service
public class ReservationService {

    private static final String METRIC_RESERVATIONS = "reservations";

    private final ReserveBayValidator reservationValidator;
    private final FeeCalculator feeCalculator;
    private final RoutePlanner routePlanner;
    private final ReservationConfirmer reservationConfirmer;
    private final MeterRegistry meterRegistry;
    private final StarportTagSanitizer starportTagSanitizer;

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        reservationValidator.validate(command);

        String starport = command.destinationStarportCode();
        String shipClass = command.shipClass().name();

        BigDecimal fee = feeCalculator.calculateFee(command);

        Route route;
        try {
            route = routePlanner.calculateRoute(command);
        } catch (RouteRejectedException ex) {
            // The routeplanning module deterministically refused the route — a domain outcome (422).
            incrementReservationCounter(starport, shipClass, "route_rejected");
            throw ex;
        }

        Reservation reservation;
        try {
            reservation = reservationConfirmer.confirm(command, fee, route);
        } catch (NoDockingBaysAvailableException ex) {
            incrementReservationCounter(starport, shipClass, "no_capacity");
            throw ex;
        }

        incrementReservationCounter(starport, shipClass, "confirmed");
        return Optional.of(reservation);
    }

    private void incrementReservationCounter(String starport, String shipClass, String outcome) {
        // Whitelist the starport code: a raw request value could otherwise explode metric cardinality.
        meterRegistry
                .counter(
                        METRIC_RESERVATIONS,
                        "starport",
                        starportTagSanitizer.sanitize(starport),
                        "shipClass",
                        shipClass,
                        "outcome",
                        outcome)
                .increment();
    }
}
