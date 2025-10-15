package com.galactic.starport.domain.exception;

import java.util.Objects;

public class StarportNotFoundException extends RuntimeException {
    private final String starportCode;

    public StarportNotFoundException(String starportCode) {
        super("Starport not found: '%s'".formatted(starportCode));
        this.starportCode = Objects.requireNonNull(starportCode, "starportCode");
    }

    public String starportCode() {
        return starportCode;
    }
}
