package com.galactic.starport.service;

import com.galactic.starport.service.confirmreservation.ConfirmReservationFacade;
import com.galactic.starport.service.confirmreservation.ReservationConfirmationException;
import com.galactic.starport.service.holdreservation.HoldReservationFacade;
import com.galactic.starport.service.reservationcalculation.ReservationCalculation;
import com.galactic.starport.service.reservationcalculation.ReservationCalculationFacade;
import com.galactic.starport.service.routeplanner.RouteUnavailableException;
import com.galactic.starport.service.validation.ReserveBayValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
@Slf4j
public class ReservationService {

    private static final String METRIC_RESERVATIONS = "reservations";

    private final HoldReservationFacade holdReservationFacade;
    private final ConfirmReservationFacade confirmReservationFacade;
    private final ReserveBayValidator reservationValidator;
    private final ReservationCalculationFacade reservationCalculationFacade;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final StarportTagSanitizer starportTagSanitizer;

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        reservationValidator.validate(command);

        String starport = command.destinationStarportCode();
        String shipClass = command.shipClass().name();

        // TX1: create the HOLD. If no bay is free there is nothing to compensate.
        Long reservationId;
        try {
            reservationId = holdReservationFacade.createHoldReservation(command);
        } catch (NoDockingBaysAvailableException ex) {
            incrementReservationCounter(starport, shipClass, "no_capacity");
            throw ex;
        }

        // The HOLD is now committed in its own transaction. The calculate (HTTP to planner) and
        // confirm (TX2) steps run separately, so any failure between here and CONFIRM would orphan the
        // HOLD — the bay stays blocked for overlapping windows forever. Explicitly release it on every
        // non-confirmed exit. (HoldReaper is the backstop for a crash that prevents this compensation.)
        boolean confirmed = false;
        try (BaggageInScope ignored = tracer.createBaggageInScope("reservationId", String.valueOf(reservationId))) {
            ReservationCalculation calc = reservationCalculationFacade.calculate(reservationId, command);
            Reservation reservation = confirmReservationFacade.confirmReservation(calc, starport);

            confirmed = true;
            incrementReservationCounter(starport, shipClass, "confirmed");
            return Optional.of(reservation);

        } catch (RouteUnavailableException ex) {
            incrementReservationCounter(starport, shipClass, "route_unavailable");
            throw ex;

        } catch (ReservationConfirmationException ex) {
            incrementReservationCounter(starport, shipClass, "error");
            throw ex;

        } finally {
            if (!confirmed) {
                compensateHold(reservationId);
            }
        }
    }

    private void compensateHold(Long reservationId) {
        try {
            holdReservationFacade.cancelHold(reservationId);
        } catch (RuntimeException ex) {
            // Best-effort: the @Scheduled HoldReaper reclaims it later. Never mask the original failure.
            log.error("Failed to compensate orphaned HOLD reservationId={} — reaper will reclaim it", reservationId, ex);
        }
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
