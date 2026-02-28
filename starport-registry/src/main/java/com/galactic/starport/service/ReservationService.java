package com.galactic.starport.service;

import java.util.Optional;

import com.galactic.starport.service.confirmreservation.ConfirmReservationFacade;
import com.galactic.starport.service.holdreservation.HoldReservationFacade;
import com.galactic.starport.service.reservationcalculation.ReservationCalculation;
import com.galactic.starport.service.reservationcalculation.ReservationCalculationFacade;
import com.galactic.starport.service.validation.ReserveBayValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
@Slf4j
public class ReservationService {

    private final HoldReservationFacade holdReservationFacade;
    private final ConfirmReservationFacade confirmReservationFacade;
    private final ReserveBayValidator reservationValidator;
    private final ReservationCalculationFacade reservationCalculationFacade;

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        reservationValidator.validate(command);
        Long reservationId = holdReservationFacade.createHoldReservation(command);
        ReservationCalculation calc = reservationCalculationFacade.calculate(reservationId, command);
        Reservation reservation = confirmReservationFacade.confirmReservation(
                calc, command.destinationStarportCode());

        return Optional.of(reservation);
    }
}