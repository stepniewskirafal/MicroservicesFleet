package com.galactic.starport.service.holdreservation;

import com.galactic.starport.service.ReserveBayCommand;

// Public: Single access point for hold reservation functionality
public interface HoldReservationFacade {
    Long createHoldReservation(ReserveBayCommand command);
}
