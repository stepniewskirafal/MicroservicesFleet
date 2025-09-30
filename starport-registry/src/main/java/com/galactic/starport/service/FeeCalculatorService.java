package com.galactic.starport.service;

import java.math.BigDecimal;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
class FeeCalculatorService {
    BigDecimal calculateFee(Reservation newReservation) {
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
        return perHour.multiply(BigDecimal.valueOf(hours));
    }
}
