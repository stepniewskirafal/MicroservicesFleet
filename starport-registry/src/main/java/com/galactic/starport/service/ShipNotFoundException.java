package com.galactic.starport.service;

public final class ShipNotFoundException extends RuntimeException {
    public ShipNotFoundException(String shipCode) {
        super("Ship '%s' not found".formatted(shipCode));
    }
}
