package com.galactic.starport.service;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
class FeeCalculatorService {
    private final MeterRegistry meterRegistry;

    private Timer feeCalculationTimer;
    private DistributionSummary calculatedFeeSummary;

    @PostConstruct
    void initMetrics() {
        feeCalculationTimer = Timer.builder("reservations.fees.calculation.duration")
                .description("Time spent calculating reservation fees")
                .register(meterRegistry);
        calculatedFeeSummary = DistributionSummary.builder("reservations.fees.calculated.amount")
                .description("Distribution of calculated reservation fees")
                .baseUnit("credits")
                .register(meterRegistry);
    }

    public BigDecimal calculateFee(Reservation newReservation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            long hours = Math.max(
                    1,
                    Duration.between(newReservation.getStartAt(), newReservation.getEndAt())
                            .toHours());
            BigDecimal perHour =
                    switch (newReservation.getShip().getShipClass()) {
                        case SCOUT -> BigDecimal.valueOf(50);
                        case FREIGHTER -> BigDecimal.valueOf(120);
                        case CRUISER -> BigDecimal.valueOf(250);
                        case UNKNOWN -> BigDecimal.valueOf(1000);
                    };
            BigDecimal calculatedFee = perHour.multiply(BigDecimal.valueOf(hours));
            calculatedFeeSummary.record(calculatedFee.doubleValue());
            return calculatedFee;
        } finally {
            sample.stop(feeCalculationTimer);
        }
    }
}
