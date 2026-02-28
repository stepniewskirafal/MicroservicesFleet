package com.galactic.starport.service.confirmreservation;

import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.reservationcalculation.ReservationCalculation;

public interface ConfirmReservationFacade {
    Reservation confirmReservation(ReservationCalculation calc, String destinationStarportCode);
}
