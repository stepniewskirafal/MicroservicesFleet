package com.galactic.starport.service;

import org.springframework.validation.Errors;

class ReservationValidationException extends RuntimeException {

    private final Errors errors;

    private ReservationValidationException(String message, Errors errors) {
        super(message);
        this.errors = errors;
    }

    public Errors getErrors() {
        return errors;
    }

    static ReservationValidationException fromErrors(Errors errors) {
        String message =
                String.format("Reservation command validation failed with %d error(s)", errors.getErrorCount());
        return new ReservationValidationException(message, errors);
    }
}
