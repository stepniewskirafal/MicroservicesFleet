package com.galactic.starport.service;

import com.galactic.starport.repository.StarportRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ValidateReservationCommandService {
    private final StarportRepository starportRepository;

    public void validate(ReserveBayCommand command) {
        if (command.requestRoute()) {
            validateStarportExists(command.startStarportCode());
        }
        validateStarportExists(command.destinationStarportCode());
        validateStartEndDates(command.startAt(), command.endAt());
    }

    private void validateStarportExists(String starportCode) {
        final boolean starportExists = starportRepository.existsByCode(starportCode);
        if (!starportExists) {
            throw new StarportNotFoundException(starportCode);
        }
        log.debug("Starport {} exists.", starportCode);
    }

    private void validateStartEndDates(Instant startAt, Instant endAt) {
        if (startAt.isAfter(endAt) || startAt.equals(endAt)) {
            throw new WrongReservationTimeException(startAt, endAt);
        }
        log.debug("Reservation time is valid: startAt={}, endAt={}", startAt, endAt);
    }
}
