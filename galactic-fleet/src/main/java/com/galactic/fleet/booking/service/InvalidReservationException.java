package com.galactic.fleet.booking.service;

public class InvalidReservationException extends RuntimeException {
    public InvalidReservationException(String message) {
        super("Invalid reservation exception: '%s'".formatted(message));
    }
}
