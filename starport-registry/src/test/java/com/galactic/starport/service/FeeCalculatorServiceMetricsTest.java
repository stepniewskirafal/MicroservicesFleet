package com.galactic.starport.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeeCalculatorServiceMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private FeeCalculatorService feeCalculatorService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        feeCalculatorService = new FeeCalculatorService(meterRegistry);
        feeCalculatorService.initMetrics();
    }

    @Test
    void recordsFeeCalculationMetrics() {
        Reservation reservation = Reservation.builder()
                .ship(Ship.builder().shipClass(Ship.ShipClass.SCOUT).build())
                .startAt(Instant.parse("2025-01-01T10:00:00Z"))
                .endAt(Instant.parse("2025-01-01T12:00:00Z"))
                .build();

        BigDecimal fee = feeCalculatorService.calculateFee(reservation);

        assertEquals(BigDecimal.valueOf(100), fee);
        assertEquals(1, meterRegistry.get("reservations.fees.calculation.duration").timer().count());
        assertEquals(1.0, meterRegistry.get("reservations.fees.calculated.amount").summary().count());
        assertEquals(100.0, meterRegistry.get("reservations.fees.calculated.amount").summary().totalAmount());
    }
}
