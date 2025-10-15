package com.galactic.starport.domain.exception;

import java.util.Objects;
import java.util.UUID;

public class DockingBayNotFoundException extends RuntimeException {
    private final UUID bayId;

    public DockingBayNotFoundException(UUID bayId) {
        super("Docking bay not found: %s".formatted(bayId));
        this.bayId = Objects.requireNonNull(bayId, "bayId");
    }

    public UUID bayId() {
        return bayId;
    }
}
