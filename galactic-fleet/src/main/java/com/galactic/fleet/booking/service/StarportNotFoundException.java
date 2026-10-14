package com.galactic.fleet.booking.service;

public class StarportNotFoundException extends RuntimeException {
    public StarportNotFoundException(String starportCode) {
        super("Starport not found: '%s'".formatted(starportCode));
    }
}
