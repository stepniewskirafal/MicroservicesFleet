package com.galactic.starport.service;

import java.math.BigDecimal;
import java.util.Optional;

import com.galactic.starport.service.holdreservation.HoldReservationFacade;
import com.galactic.starport.service.validation.ReserveBayValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReservationService {

    private final HoldReservationFacade holdReservationFacade;
    private final ConfirmReservationService confirmReservationService;
    private final ReserveBayValidator reservationValidator;
    private final FeeCalculatorService feeCalculatorService;
    private final RoutePlannerService routePlannerService;

    public ReservationService(
            HoldReservationFacade holdReservationFacade,
            ConfirmReservationService confirmReservationService,
            ReserveBayValidator reservationValidator,
            FeeCalculatorService feeCalculatorService,
            RoutePlannerService routePlannerService) {

        this.holdReservationFacade = holdReservationFacade;
        this.confirmReservationService = confirmReservationService;
        this.reservationValidator = reservationValidator;
        this.feeCalculatorService = feeCalculatorService;
        this.routePlannerService = routePlannerService;
    }

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        log.info("Reserving bay for command: {}", command);

        // Validate the incoming command
        reservationValidator.validate(command);

        // Create a HOLD reservation first
        Long reservationId = holdReservationFacade.createHoldReservation(command);

        // Calculate fee and route
        ReservationCalculation calc = getReservationCalculation(reservationId, command);

        // Confirm the reservation with the calculated data
        Reservation reservation = confirmReservationService.confirmReservation(
                calc, command.destinationStarportCode());

        return Optional.of(reservation);
    }

    private ReservationCalculation getReservationCalculation(Long reservationId, ReserveBayCommand command) {
        BigDecimal calculatedFee = feeCalculatorService.calculateFee(command);
        Route route = routePlannerService.calculateRoute(command);
        return new ReservationCalculation(reservationId, calculatedFee, route);
    }
}