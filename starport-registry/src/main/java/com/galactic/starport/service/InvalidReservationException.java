package com.galactic.starport.service;

public class InvalidReservationException extends RuntimeException {
    public InvalidReservationException(String message) {
        super("Invalid reservation exception: '%s'".formatted(message));
    }
}
