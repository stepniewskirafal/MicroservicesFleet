package com.galactic.starport.service;

import com.galactic.starport.repository.StarportRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ValidateReservationCommandService {
    private final StarportRepository starportRepository;
    private final MeterRegistry meterRegistry;

    private Timer validationTimer;
    private Counter validationAttemptCounter;
    private Counter validationSuccessCounter;
    private Counter validationErrorCounter;

    @PostConstruct
    void initMetrics() {
        validationTimer = Timer.builder("reservations.validation.duration")
                .description("Time spent validating reservation commands")
                .register(meterRegistry);
        validationAttemptCounter = Counter.builder("reservations.validation.attempts")
                .description("Number of reservation validation attempts")
                .register(meterRegistry);
        validationSuccessCounter = Counter.builder("reservations.validation.success")
                .description("Number of successfully validated reservation commands")
                .register(meterRegistry);
        validationErrorCounter = Counter.builder("reservations.validation.errors")
                .description("Number of reservation validations that failed")
                .register(meterRegistry);
    }

    public void validate(ReserveBayCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        validationAttemptCounter.increment();
        try {
            if (command.requestRoute()) {
                validateStarportExists(command.startStarportCode());
            }
            validateStarportExists(command.destinationStarportCode());
            validateStartEndDates(command.startAt(), command.endAt());
            validationSuccessCounter.increment();
        } finally {
            sample.stop(validationTimer);
        }
    }

    private void validateStarportExists(String starportCode) {
        final boolean starportExists = starportRepository.existsByCode(starportCode);
        if (!starportExists) {
            validationErrorCounter.increment();
            throw new StarportNotFoundException(starportCode);
        }
        log.debug("Starport {} exists.", starportCode);
    }

    private void validateStartEndDates(Instant startAt, Instant endAt) {
        if (startAt.isAfter(endAt) || startAt.equals(endAt)) {
            validationErrorCounter.increment();
            throw new WrongReservationTimeException(startAt, endAt);
        }
        log.debug("Reservation time is valid: startAt={}, endAt={}", startAt, endAt);
    }
}
