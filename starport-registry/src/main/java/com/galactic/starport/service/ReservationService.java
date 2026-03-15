package com.galactic.starport.service;

import com.galactic.starport.service.confirmreservation.ConfirmReservationFacade;
import com.galactic.starport.service.confirmreservation.ReservationConfirmationException;
import com.galactic.starport.service.holdreservation.HoldReservationFacade;
import com.galactic.starport.service.reservationcalculation.ReservationCalculation;
import com.galactic.starport.service.reservationcalculation.ReservationCalculationFacade;
import com.galactic.starport.service.routeplanner.RouteUnavailableException;
import com.galactic.starport.service.validation.ReserveBayValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
@Slf4j
public class ReservationService {

    private static final String METRIC_CREATED = "reservations.created.total";
    private static final String METRIC_HOLD_RELEASED = "reservations.hold.released";

    private final HoldReservationFacade holdReservationFacade;
    private final ConfirmReservationFacade confirmReservationFacade;
    private final ReserveBayValidator reservationValidator;
    private final ReservationCalculationFacade reservationCalculationFacade;
    private final MeterRegistry meterRegistry;

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        reservationValidator.validate(command);

        String starport = command.destinationStarportCode();
        String shipClass = command.shipClass().name();

        try {
            Long reservationId = holdReservationFacade.createHoldReservation(command);
            ReservationCalculation calc = reservationCalculationFacade.calculate(reservationId, command);
            Reservation reservation = confirmReservationFacade.confirmReservation(calc, starport);

            incrementReservationCounter(starport, shipClass, "confirmed");
            return Optional.of(reservation);

        } catch (NoDockingBaysAvailableException ex) {
            incrementReservationCounter(starport, shipClass, "no_capacity");
            throw ex;

        } catch (RouteUnavailableException ex) {
            incrementReservationCounter(starport, shipClass, "route_unavailable");
            meterRegistry.counter(METRIC_HOLD_RELEASED,
                    "starport", starport,
                    "shipClass", shipClass,
                    "reason", "route_unavailable")
                    .increment();
            throw ex;

        } catch (ReservationConfirmationException ex) {
            incrementReservationCounter(starport, shipClass, "error");
            throw ex;
        }
    }

    private void incrementReservationCounter(String starport, String shipClass, String outcome) {
        meterRegistry.counter(METRIC_CREATED,
                "starport", starport,
                "shipClass", shipClass,
                "outcome", outcome)
                .increment();
    }
}
