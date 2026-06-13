package com.galactic.starport.service.holdreservation;

import com.galactic.starport.service.ReserveBayCommand;

public interface HoldReservationFacade {
    Long createHoldReservation(ReserveBayCommand command);

    /** Compensation: release a HOLD that can no longer be confirmed, so its bay isn't orphaned. */
    void cancelHold(Long reservationId);
}
