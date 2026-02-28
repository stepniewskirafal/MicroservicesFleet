package com.galactic.starport.service.reservationcalculation;

import com.galactic.starport.service.ReserveBayCommand;

// Public: Single access point for reservation calculation functionality
public interface ReservationCalculationFacade {
    ReservationCalculation calculate(Long reservationId, ReserveBayCommand command);
}
