package com.galactic.starport.domain.exception;

import java.time.Instant;
import java.util.Objects;

public class NoDockingBaysAvailableException extends RuntimeException {
    private final String starportCode;
    private final Instant from;
    private final Instant to;

    public NoDockingBaysAvailableException(String starportCode, Instant from, Instant to) {
        super("No docking bays available in starport '%s' for interval %s - %s".formatted(starportCode, from, to));
        this.starportCode = Objects.requireNonNull(starportCode, "starportCode");
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
    }

    public String starportCode() {
        return starportCode;
    }

    public Instant from() {
        return from;
    }

    public Instant to() {
        return to;
    }
}
