package com.galactic.starport.service;

import java.time.Instant;

public class NoDockingBaysAvailableException extends RuntimeException {
    public NoDockingBaysAvailableException(String starportCode, String shipClass, Instant start, Instant end) {
        super("No free docking bay in starport='%s' for shipClass='%s' in window [%s..%s]"
                .formatted(starportCode, shipClass, start, end));
    }
}
