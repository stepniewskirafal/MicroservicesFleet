package com.galactic.starport.service.holdreservation;

import com.galactic.starport.service.ReserveBayCommand;

public interface HoldReservationFacade {
    Long createHoldReservation(ReserveBayCommand command);
}
