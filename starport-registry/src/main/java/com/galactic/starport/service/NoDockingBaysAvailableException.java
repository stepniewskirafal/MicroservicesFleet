package com.galactic.starport.service;

import java.time.Instant;

public class NoDockingBaysAvailableException extends RuntimeException {
    public NoDockingBaysAvailableException(String starportCode, Instant from, Instant to) {
        super("No docking bays available in starport '%s' for interval %s - %s".formatted(starportCode, from, to));
    }
}
