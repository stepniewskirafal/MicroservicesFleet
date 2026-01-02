package com.galactic.starport.service.outbox;

import com.galactic.starport.service.Reservation;

public interface OutboxFacade {
    void publishReservationConfirmedEvent(Reservation reservation);
}
