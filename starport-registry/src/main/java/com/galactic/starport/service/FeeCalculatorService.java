package com.galactic.starport.service;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
class FeeCalculatorService {
    private final MeterRegistry meterRegistry;

    private final ObservationRegistry observationRegistry;
    private DistributionSummary calculatedFeeSummary;

    @PostConstruct
    void initMetrics() {
        calculatedFeeSummary = DistributionSummary.builder("reservations.fees.calculated.amount")
                .description("Distribution of calculated reservation fees")
                .baseUnit("credits")
                .register(meterRegistry);
    }

    @Transactional
    public BigDecimal calculateFee(Reservation newReservation) {
        Observation observation = Observation.start("reservations.fees.calculation", observationRegistry);
        try (Observation.Scope scope = observation.openScope()) {
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
            // mark the observation as successful
            observation.lowCardinalityKeyValue("status", "success");
            return calculatedFee;
        } catch (RuntimeException ex) {
            observation.error(ex);
            observation.lowCardinalityKeyValue("status", "error");
            throw ex;
        } finally {
            // stopping the observation will emit the measured duration and tags
            observation.stop();
        }
    }
}
