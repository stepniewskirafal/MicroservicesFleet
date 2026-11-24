package com.galactic.fleet;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-delivers application events whose listener did not finish, and reports how many are waiting
 * (ADR-0043).
 *
 * <p>The Event Publication Registry keeps a row in {@code event_publication} until the listener
 * returns normally, but nothing in the library re-submits a failed one while the application keeps
 * running. This job does: every {@code events.redelivery.interval-ms} it re-submits publications
 * older than {@code events.redelivery.older-than-ms}. The age threshold keeps it away from
 * publications that are simply still being handled.
 *
 * <p><b>Several instances.</b> Every instance runs this job against the same table. A Postgres
 * advisory lock, held until the end of the job's transaction, lets one instance at a time do the
 * re-submission; the others skip the round. Delivery stays at-least-once — a record may still be
 * sent twice — but instances do not multiply every retry.
 *
 * <p><b>An event that never succeeds</b> is re-submitted every round, forever: the registry does not
 * count attempts, so there is no dead-letter. It stays visible instead — the two gauges below, and a
 * warning once the oldest publication is older than {@code events.redelivery.warn-after-ms}.
 *
 * <p>Platform code, not a module: it sits in the application's root package.
 */
@Component
class EventPublicationMaintenance {

    static final String METRIC_INCOMPLETE = "events.publications.incomplete";
    static final String METRIC_OLDEST_AGE = "events.publications.oldest.age";

    /** Arbitrary but fixed: every instance must ask for the same advisory lock. */
    static final long ADVISORY_LOCK_KEY = 0x6576656E7473L; // "events"

    private static final Logger log = LoggerFactory.getLogger(EventPublicationMaintenance.class);

    private final IncompleteEventPublications incompletePublications;
    private final JdbcTemplate jdbc;
    private final Duration olderThan;
    private final Duration warnAfter;
    private final AtomicLong incompleteCount = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    EventPublicationMaintenance(
            ObjectProvider<IncompleteEventPublications> incompletePublications,
            ObjectProvider<JdbcTemplate> jdbc,
            MeterRegistry meterRegistry,
            @Value("${events.redelivery.older-than-ms:60000}") long olderThanMs,
            @Value("${events.redelivery.warn-after-ms:900000}") long warnAfterMs) {
        // Absent in a module test that boots without a database; the job is then a no-op.
        this.incompletePublications = incompletePublications.getIfAvailable();
        this.jdbc = jdbc.getIfAvailable();
        this.olderThan = Duration.ofMillis(olderThanMs);
        this.warnAfter = Duration.ofMillis(warnAfterMs);
        // The gauges read cached values: a gauge supplier runs on every scrape of every instance, so
        // it must never query the database itself.
        Gauge.builder(METRIC_INCOMPLETE, incompleteCount, AtomicLong::doubleValue)
                .description("Application events whose listener has not completed (rows left in event_publication)")
                .baseUnit("events")
                .register(meterRegistry);
        Gauge.builder(METRIC_OLDEST_AGE, oldestAgeSeconds, AtomicLong::doubleValue)
                .description("Age of the oldest incomplete event publication; 0 when there is none")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${events.redelivery.interval-ms:60000}",
            initialDelayString = "${events.redelivery.initial-delay-ms:60000}")
    @Transactional
    public void redeliverIncompletePublications() {
        if (incompletePublications == null || jdbc == null) {
            return;
        }
        refreshGauges();
        if (incompleteCount.get() == 0) {
            return;
        }
        if (oldestAgeSeconds.get() >= warnAfter.toSeconds()) {
            log.warn(
                    "{} event publication(s) incomplete, the oldest for {} s — its listener keeps failing",
                    incompleteCount.get(),
                    oldestAgeSeconds.get());
        }
        // Released when this transaction ends, i.e. after the re-submission below has been handed
        // to the listeners. Another instance that fires meanwhile gets `false` and skips the round.
        Boolean lockAcquired =
                jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        if (!Boolean.TRUE.equals(lockAcquired)) {
            log.debug("Another instance is re-delivering event publications; skipping this round");
            return;
        }
        incompletePublications.resubmitIncompletePublicationsOlderThan(olderThan);
    }

    private void refreshGauges() {
        jdbc.query(
                "select count(*), coalesce(extract(epoch from (now() - min(publication_date))), 0)"
                        + " from event_publication where completion_date is null",
                row -> {
                    incompleteCount.set(row.getLong(1));
                    oldestAgeSeconds.set((long) row.getDouble(2));
                });
    }
}
