package com.galactic.starport.service;

import java.util.Optional;

import com.galactic.starport.service.confirmreservation.ConfirmReservationFacade;
import com.galactic.starport.service.holdreservation.HoldReservationFacade;
import com.galactic.starport.service.reservationcalculation.ReservationCalculation;
import com.galactic.starport.service.reservationcalculation.ReservationCalculationFacade;
import com.galactic.starport.service.validation.ReserveBayValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReservationService {

    private final HoldReservationFacade holdReservationFacade;
    private final ConfirmReservationFacade confirmReservationFacade;
    private final ReserveBayValidator reservationValidator;
    private final ReservationCalculationFacade reservationCalculationFacade;

    public ReservationService(
            HoldReservationFacade holdReservationFacade,
            ConfirmReservationFacade confirmReservationFacade,
            ReserveBayValidator reservationValidator,
            ReservationCalculationFacade reservationCalculationFacade) {

        this.holdReservationFacade = holdReservationFacade;
        this.confirmReservationFacade = confirmReservationFacade;
        this.reservationValidator = reservationValidator;
        this.reservationCalculationFacade = reservationCalculationFacade;
    }

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        log.info("Reserving bay for command: {}", command);

        // Validate the incoming command
        reservationValidator.validate(command);

        // Create a HOLD reservation first
        Long reservationId = holdReservationFacade.createHoldReservation(command);

        // Calculate fee and route
        ReservationCalculation calc = reservationCalculationFacade.calculate(reservationId, command);

        // Confirm the reservation with the calculated data
        Reservation reservation = confirmReservationFacade.confirmReservation(
                calc, command.destinationStarportCode());

        return Optional.of(reservation);
    }
}