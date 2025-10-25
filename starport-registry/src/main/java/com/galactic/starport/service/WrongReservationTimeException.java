package com.galactic.starport.service;

import java.time.Instant;

public class WrongReservationTimeException extends RuntimeException {
    public WrongReservationTimeException(Instant startAt, Instant endAt) {
        super("End date must be after start date. Passed start: %s, end: %s".formatted(startAt, endAt));
    }
}
