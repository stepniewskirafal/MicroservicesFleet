package com.galactic.starport.service.confirmreservation;

import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.reservationcalculation.ReservationCalculation;

// Public: Single access point for confirm reservation functionality
public interface ConfirmReservationFacade {
    Reservation confirmReservation(ReservationCalculation calc, String destinationStarportCode);
}
