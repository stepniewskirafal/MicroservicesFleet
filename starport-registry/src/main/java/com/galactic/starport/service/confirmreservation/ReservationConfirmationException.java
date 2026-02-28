package com.galactic.starport.service.confirmreservation;

public class ReservationConfirmationException extends RuntimeException {
    public ReservationConfirmationException(Long reservationId) {
        super("Exception occurred during reservation confirmation process, reservationId: '%s'"
                .formatted(reservationId));
    }
}
