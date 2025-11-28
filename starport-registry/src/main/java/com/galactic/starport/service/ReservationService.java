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
    private final ReserveBayValidationService reservationValidator;
    private final FeeCalculatorService feeCalculatorService;
    private final RoutePlannerService routePlannerService;
    private final ObservationRegistry observationRegistry;
    private static final String OBSERVATION_NAME = "reservations.reserve";

    public /*Optional<Reservation>*/ void reserveBay(ReserveBayCommand command) {
        Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                .lowCardinalityKeyValue("shipClass", command.shipClass().name())
                .lowCardinalityKeyValue("requestRoute", String.valueOf(command.requestRoute()))
                .observe(() -> {
                    log.info("Reserving bay for command: {}", command);
                    reservationValidator.validate(command);
                    confirmReservationService.confirmReservation(getReservationCalculation(command));
                });
    }

    private ReservationCalculation getReservationCalculation(ReserveBayCommand command) {
        Long reservationId = createHoldReservationService.createHoldReservation(command);
        BigDecimal calculatedFee = feeCalculatorService.calculateFee(command);
        Optional<Route> route = routePlannerService.calculateRoute(command);
        return new ReservationCalculation(reservationId, calculatedFee, route);
    }
}
