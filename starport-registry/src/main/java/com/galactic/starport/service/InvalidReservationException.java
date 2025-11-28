package com.galactic.starport.service;

public class InvalidReservationException extends RuntimeException {
    public InvalidReservationException(String string) {
        super("Invalid reservation exception: '%s'".formatted(string));
    }
}
