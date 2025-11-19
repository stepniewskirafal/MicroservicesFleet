package com.galactic.starport.service;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;

@Builder
public record ReserveBayCommand(
        String startStarportCode,
        String destinationStarportCode,
        String customerCode,
        String shipCode,
        ShipClass shipClass,
        Instant startAt,
        Instant endAt,
        boolean requestRoute) {

    public enum ShipClass {
        SCOUT(BigDecimal.valueOf(50)),
        FREIGHTER(BigDecimal.valueOf(120)),
        CRUISER(BigDecimal.valueOf(250)),
        UNKNOWN(BigDecimal.valueOf(1000));

        private final BigDecimal hourlyRate;

        ShipClass(BigDecimal hourlyRate) {
            this.hourlyRate = hourlyRate;
        }

        public BigDecimal hourlyRate() {
            return hourlyRate;
        }
    }
}
