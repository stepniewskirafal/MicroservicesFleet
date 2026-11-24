package com.galactic.fleet.activityfeed.filter;

import com.galactic.fleet.booking.events.ReservationConfirmed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First filter of the reservation chain: counts the event and measures how long it waited between
 * being published (inside the reservation's transaction) and arriving here (after the commit, on
 * another thread). It passes the event on unchanged.
 */
public class ReservationIntakeFilter implements Function<ReservationConfirmed, ReservationConfirmed> {

    private static final Logger log = LoggerFactory.getLogger(ReservationIntakeFilter.class);

    private final Counter receivedCounter;
    private final DistributionSummary lagSummary;

    public ReservationIntakeFilter(MeterRegistry meterRegistry) {
        this.receivedCounter = Counter.builder("events.reservation.received")
                .description("Reservation events received from the publishing module")
                .register(meterRegistry);
        this.lagSummary = DistributionSummary.builder("events.reservation.lag")
                .baseUnit("seconds")
                .description("End-to-end async lag: time from ReservationConfirmed published "
                        + "(inside the reservation transaction) to enriched (after the commit, on another thread)")
                .register(meterRegistry);
    }

    @Override
    public ReservationConfirmed apply(ReservationConfirmed event) {
        receivedCounter.increment();
        log.info(
                "Processing ReservationConfirmed: reservationId={} starport={} customer={}",
                event.reservationId(),
                event.starportCode(),
                event.customerCode());
        if (event.producedAt() != null) {
            double lagSeconds =
                    Duration.between(event.producedAt(), Instant.now()).toMillis() / 1000.0;
            if (lagSeconds >= 0) {
                lagSummary.record(lagSeconds);
            }
        }
        return event;
    }
}
