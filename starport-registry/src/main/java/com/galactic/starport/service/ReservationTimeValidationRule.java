package com.galactic.starport.service;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@Order(0)
class ReservationTimeValidationRule implements ReserveBayCommandValidationRule {
    @Override
    public void validate(ReserveBayCommand command) {
        final Instant startTime = command.startAt();
        final Instant endTime = command.endAt();
        if (!startTime.isBefore(endTime)) {
            throw new InvalidReservationTimeException(startTime, endTime);
        }
        log.debug("Reservation time is valid: startAt={}, endAt={}", startTime, endTime);
    }
}
