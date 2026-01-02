package com.galactic.starport.service;

public class ReservationConfirmationException extends RuntimeException {
    public ReservationConfirmationException(Long reservationId) {
        super("Exception occurred during reservation confirmation process, reservationId: '%s'"
                .formatted(reservationId));
    }
}
