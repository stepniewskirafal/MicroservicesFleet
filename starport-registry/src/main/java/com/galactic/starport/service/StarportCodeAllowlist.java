package com.galactic.starport.service;

import com.galactic.starport.repository.StarportPersistenceFacade;
import java.util.Set;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * DB-backed whitelist for the {@code starport} metric tag. Starport codes are a small, bounded set
 * (seeded), so they are loaded once after startup and cached. Any code not in the set collapses to
 * {@code "other"} — fail-closed, so a typo'd or hostile request can never spawn a new Prometheus
 * series. Loading on {@link ApplicationReadyEvent} guarantees Flyway has seeded the table first; the
 * cache is read on every reservation but refreshed only on restart (the seed set is static).
 */
@Component
class StarportCodeAllowlist implements StarportTagSanitizer {

    static final String UNKNOWN_BUCKET = "other";

    private final StarportPersistenceFacade persistenceFacade;
    private volatile Set<String> knownCodes = Set.of();

    StarportCodeAllowlist(StarportPersistenceFacade persistenceFacade) {
        this.persistenceFacade = persistenceFacade;
    }

    @EventListener(ApplicationReadyEvent.class)
    void loadKnownCodes() {
        knownCodes = persistenceFacade.findAllStarportCodes();
    }

    @Override
    public String sanitize(String starportCode) {
        return knownCodes.contains(starportCode) ? starportCode : UNKNOWN_BUCKET;
    }
}
