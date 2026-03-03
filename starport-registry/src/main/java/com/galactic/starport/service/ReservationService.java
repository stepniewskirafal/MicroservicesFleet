package com.galactic.starport.service;

import com.galactic.starport.service.confirmreservation.ConfirmReservationFacade;
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

            meterRegistry.counter(METRIC_CREATED,
                    "starport", starport,
                    "shipClass", shipClass,
                    "outcome", "confirmed")
                    .increment();

            return Optional.of(reservation);

        } catch (NoDockingBaysAvailableException e) {
            meterRegistry.counter(METRIC_CREATED,
                    "starport", starport,
                    "shipClass", shipClass,
                    "outcome", "no_capacity")
                    .increment();
            throw e;

        } catch (RouteUnavailableException e) {
            meterRegistry.counter(METRIC_CREATED,
                    "starport", starport,
                    "shipClass", shipClass,
                    "outcome", "route_unavailable")
                    .increment();
            // The HOLD expires via TTL; track it as released for capacity accounting.
            meterRegistry.counter(METRIC_HOLD_RELEASED,
                    "starport", starport,
                    "shipClass", shipClass,
                    "reason", "route_unavailable")
                    .increment();
            throw e;
        }
    }
}
