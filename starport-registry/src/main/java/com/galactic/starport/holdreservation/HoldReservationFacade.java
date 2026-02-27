package com.galactic.starport.holdreservation;

import com.galactic.starport.service.ReserveBayCommand;

// Public: Single access point for hold reservation functionality
public interface HoldReservationFacade {
    Long createHoldReservation(ReserveBayCommand command);
}
