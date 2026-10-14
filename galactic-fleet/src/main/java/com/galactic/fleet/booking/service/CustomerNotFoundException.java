package com.galactic.fleet.booking.service;

public final class CustomerNotFoundException extends RuntimeException {
    public CustomerNotFoundException(String customerCode) {
        super("Customer '%s' not found".formatted(customerCode));
    }
}
