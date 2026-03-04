package com.galactic.starport.service.feecalculator;

import com.galactic.starport.service.InvalidReservationTimeException;
import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.ReserveBayCommand.ShipClass;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
class FeeCalculatorService implements FeeCalculator {
    private static final String OBSERVATION_NAME = "reservations.fees.calculate";
    private static final String METRIC_FEE_AMOUNT = "reservations.fees.calculated.amount";
    private static final String METRIC_FEE_HOURS = "reservations.fees.calculated.hours";
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    FeeCalculatorService(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        // Pre-register both metrics for every ship class so Prometheus exposes them
        // from startup — before the first reservation request arrives.
        // Both metrics use only the "shipClass" tag (finite, enumerable set), which
        // guarantees a consistent label cardinality across all series for the same
        // metric name — a hard requirement of the Prometheus data model.
        for (ShipClass shipClass : ShipClass.values()) {
            DistributionSummary.builder(METRIC_FEE_AMOUNT)
                    .baseUnit("cr")
                    .description("Calculated reservation fee amount in Credits")
                    .tag("shipClass", shipClass.name())
                    .register(meterRegistry);
            DistributionSummary.builder(METRIC_FEE_HOURS)
                    .baseUnit("hours")
                    .description("Charged hours used to calculate reservation fee")
                    .tag("shipClass", shipClass.name())
                    .register(meterRegistry);
        }
    }

    @Override
    public BigDecimal calculateFee(ReserveBayCommand command) {
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                .lowCardinalityKeyValue("shipClass", command.shipClass().name())
                .observe(() -> {
                    long billingHours = calculateBillingHours(command);
                    BigDecimal fee = calculateFeeAmount(command.shipClass(), billingHours);

                    // Revenue by ship class — Micrometer caches meters by (name+tags).
                    // "starport" is intentionally omitted: it is a high-cardinality, dynamic
                    // value that would prevent pre-registration and violate Prometheus'
                    // label-consistency requirement across series of the same metric name.
                    DistributionSummary.builder(METRIC_FEE_AMOUNT)
                            .baseUnit("cr")
                            .description("Calculated reservation fee amount in Credits")
                            .tag("shipClass", command.shipClass().name())
                            .register(meterRegistry)
                            .record(fee.doubleValue());

                    DistributionSummary.builder(METRIC_FEE_HOURS)
                            .baseUnit("hours")
                            .description("Charged hours used to calculate reservation fee")
                            .tag("shipClass", command.shipClass().name())
                            .register(meterRegistry)
                            .record(billingHours);

                    log.debug(
                            "Calculated fee: starport={}, shipClass={}, hours={}, fee={}",
                            command.destinationStarportCode(),
                            command.shipClass(),
                            billingHours,
                            fee);
                    return fee;
                });
    }

    private long calculateBillingHours(ReserveBayCommand command) {
        long hours = Duration.between(command.startAt(), command.endAt()).toHours();
        if (hours < 0) {
            throw new InvalidReservationTimeException(command.startAt(), command.endAt());
        }
        return Math.max(1, hours);
    }

    private BigDecimal calculateFeeAmount(ShipClass shipClass, long hours) {
        return shipClass.hourlyRate().multiply(BigDecimal.valueOf(hours));
    }
}
