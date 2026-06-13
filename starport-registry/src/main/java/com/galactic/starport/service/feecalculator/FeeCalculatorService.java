package com.galactic.starport.service.feecalculator;

import com.galactic.starport.service.InvalidReservationTimeException;
import com.galactic.starport.service.ReserveBayCommand;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
class FeeCalculatorService implements FeeCalculator {
    private static final String OBSERVATION_NAME = "reservations.fees.calculate";
    private static final String METRIC_FEE_AMOUNT = "reservations.fees.calculated.amount";

    // ── Dynamic berth tariff ──────────────────────────────────────────────────
    // Peak surcharge: ships arriving during the galactic "rush window" (06:00–18:00 UTC) pay extra.
    private static final int PEAK_START_HOUR = 6; // inclusive
    private static final int PEAK_END_HOUR = 18; // exclusive
    private static final BigDecimal PEAK_SURCHARGE_RATE = new BigDecimal("0.15");
    // Volume discount: the longer a ship stays berthed, the cheaper the anchorage.
    private static final long LONG_STAY_HOURS = 24;
    private static final long EXTENDED_STAY_HOURS = 72;
    private static final BigDecimal LONG_STAY_DISCOUNT_RATE = new BigDecimal("0.10");
    private static final BigDecimal EXTENDED_STAY_DISCOUNT_RATE = new BigDecimal("0.20");

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    FeeCalculatorService(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public BigDecimal calculateFee(ReserveBayCommand command) {
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                .lowCardinalityKeyValue("shipClass", command.shipClass().name())
                .observe(() -> {
                    long billingHours = calculateBillingHours(command);
                    BigDecimal fee = calculateFeeAmount(command, billingHours);

                    // Revenue by starport + ship class — Micrometer caches meters by (name+tags).
                    DistributionSummary.builder(METRIC_FEE_AMOUNT)
                            .baseUnit("cr")
                            .description("Calculated reservation fee amount in Credits")
                            .tag("starport", command.destinationStarportCode())
                            .tag("shipClass", command.shipClass().name())
                            .register(meterRegistry)
                            .record(fee.doubleValue());

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

    /**
     * Berth fee = base (hourlyRate × hours) adjusted by a volume discount for long stays and a peak
     * surcharge for arrivals during the rush window. Both adjustments are deterministic functions of the
     * reservation alone, so the fee stays independent of route planning and the two can run in parallel.
     */
    private BigDecimal calculateFeeAmount(ReserveBayCommand command, long hours) {
        BigDecimal baseFee = command.shipClass().hourlyRate().multiply(BigDecimal.valueOf(hours));

        BigDecimal discountRate = longStayDiscountRate(hours);
        boolean peak = isPeakArrival(command.startAt());

        // Standard tariff (short, off-peak): return the base fee untouched at its natural scale.
        if (discountRate.signum() == 0 && !peak) {
            return baseFee;
        }

        BigDecimal surchargeRate = peak ? PEAK_SURCHARGE_RATE : BigDecimal.ZERO;
        BigDecimal multiplier = BigDecimal.ONE.subtract(discountRate).multiply(BigDecimal.ONE.add(surchargeRate));
        return baseFee.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal longStayDiscountRate(long hours) {
        if (hours >= EXTENDED_STAY_HOURS) {
            return EXTENDED_STAY_DISCOUNT_RATE;
        }
        if (hours >= LONG_STAY_HOURS) {
            return LONG_STAY_DISCOUNT_RATE;
        }
        return BigDecimal.ZERO;
    }

    private boolean isPeakArrival(Instant startAt) {
        int hourOfDay = startAt.atZone(ZoneOffset.UTC).getHour();
        return hourOfDay >= PEAK_START_HOUR && hourOfDay < PEAK_END_HOUR;
    }
}
