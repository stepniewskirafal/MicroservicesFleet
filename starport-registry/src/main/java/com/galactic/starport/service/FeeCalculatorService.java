package com.galactic.starport.service;

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
class FeeCalculatorService {
    private static final String OBSERVATION_NAME = "reservations.fees.calculate";
    private static final String METRIC_FEE_AMOUNT = "reservations.fees.calculated.amount";
    private static final String METRIC_FEE_HOURS = "reservations.fees.calculated.hours";
    private final ObservationRegistry observationRegistry;
    private final DistributionSummary feeAmountSummary;
    private final DistributionSummary feeHoursSummary;

    FeeCalculatorService(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;

        this.feeAmountSummary = DistributionSummary.builder(METRIC_FEE_AMOUNT)
                .baseUnit("pln")
                .description("Calculated reservation fee amount")
                .register(meterRegistry);

        this.feeHoursSummary = DistributionSummary.builder(METRIC_FEE_HOURS)
                .baseUnit("hours")
                .description("Charged hours used to calculate reservation fee")
                .register(meterRegistry);
    }

    BigDecimal calculateFee(ReserveBayCommand command) {
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                .lowCardinalityKeyValue("shipClass", command.shipClass().name())
                .observe(() -> {
                    long billingHours = calculateBillingHours(command);
                    BigDecimal fee = calculateFeeAmount(command.shipClass(), billingHours);
                    feeAmountSummary.record(fee.doubleValue());
                    feeHoursSummary.record(billingHours);
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
