package com.galactic.starport.service.holdreservation;

import com.galactic.starport.repository.StarportPersistenceFacade;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backstop for orphaned HOLDs. In-line compensation in {@code ReservationService} releases a HOLD
 * when confirm fails, but it cannot run if the process dies between HOLD and CONFIRM. This reaper
 * periodically cancels any HOLD older than its TTL that was never confirmed, so a crashed request
 * can't block a docking bay forever.
 *
 * <p>The TTL must comfortably exceed the worst-case confirm latency (HTTP-to-planner timeout, a few
 * seconds) so an in-flight reservation is never reaped — the default 2 min has a wide margin.
 */
@Component
@Slf4j
class HoldReaper {

    private static final String METRIC_REAPED = "reservations.hold.reaped";

    private final StarportPersistenceFacade persistenceFacade;
    private final Counter reapedCounter;
    private final Duration ttl;

    HoldReaper(
            StarportPersistenceFacade persistenceFacade,
            MeterRegistry meterRegistry,
            @Value("${reservations.hold.ttl-ms:120000}") long ttlMs) {
        this.persistenceFacade = persistenceFacade;
        this.ttl = Duration.ofMillis(ttlMs);
        this.reapedCounter = Counter.builder(METRIC_REAPED)
                .description("Orphaned HOLD reservations cancelled by the reaper after exceeding their TTL")
                .baseUnit("reservations")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${reservations.hold.reaper.interval-ms:60000}",
            initialDelayString = "${reservations.hold.reaper.initial-delay-ms:60000}")
    public void reapStaleHolds() {
        Instant cutoff = Instant.now().minus(ttl);
        int reaped = persistenceFacade.reapStaleHolds(cutoff);
        if (reaped > 0) {
            reapedCounter.increment(reaped);
            log.warn("Reaped {} orphaned HOLD reservation(s) older than {}", reaped, ttl);
        }
    }
}
