package com.galactic.starport.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final CreateHoldReservationService createHoldReservationService;
    private final ConfirmReservationService confirmReservationService;
    private final ReserveBayValidationComposite validateReservationCommandService;
    private final FeeCalculatorService feeCalculatorService;
    private final RoutePlannerService routePlannerService;
    private final ObservationRegistry observationRegistry;

    public /*Optional<Reservation>*/ void reserveBay(ReserveBayCommand command) {
        Observation.createNotStarted("reservations.reserve", observationRegistry)
                .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                .observe(() -> {
                    log.info("Reserving bay for command: {}", command);
                    validateReservationCommandService.validate(command);
                    confirmReservationService.confirmReservation(getReservationCalculation(command));
                });
    }

    private ReservationCalculation getReservationCalculation(ReserveBayCommand command) {
        Long reservationId = createHoldReservationService.allocateHold(command);
        BigDecimal calculatedFee = feeCalculatorService.calculateFee(command);
        Optional<Route> route = routePlannerService.calculateRoute(command);
        return new ReservationCalculation(reservationId, calculatedFee, route);
    }
}
