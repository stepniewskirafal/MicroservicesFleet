package com.galactic.starport.service;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

@Component
@Slf4j
@Order(0)
class ReservationTimeValidationRule implements ReserveBayCommandValidationRule {
    static final String ERROR_CODE = "reservation.time.invalid";

    @Override
    public void validate(ReserveBayCommand command, Errors errors) {
        final Instant startTime = command.startAt();
        final Instant endTime = command.endAt();

        if (startTime == null || endTime == null) {
            errors.reject(
                    ERROR_CODE, new Object[] {startTime, endTime}, "Reservation start and end time must be provided.");
            log.debug("Reservation time invalid: startAt or endAt is null: startAt={}, endAt={}", startTime, endTime);
            return;
        }

        if (!startTime.isBefore(endTime)) {
            errors.reject(
                    ERROR_CODE,
                    new Object[] {startTime, endTime},
                    "End date must be after start date. Passed start: %s, end: %s".formatted(startTime, endTime));
            log.debug("Reservation time invalid: startAt={}, endAt={}", startTime, endTime);
            return;
        }

        log.debug("Reservation time is valid: startAt={}, endAt={}", startTime, endTime);
    }
}
