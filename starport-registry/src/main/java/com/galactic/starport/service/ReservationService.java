package com.galactic.starport.service;

import com.galactic.starport.repository.DockingBayEntity;
import com.galactic.starport.repository.DockingBayRepository;
import com.galactic.starport.repository.StarportRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {
    private final HoldReservationService persistenceService;
    private final StarportRepository starportRepository;
    private final DockingBayRepository dockingBayRepository;

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        validateStarportExists(command.starportCode());
        validateStartEndDates(command.startAt(), command.endAt());
        final DockingBayEntity freeDockingBay = getFreeDockingBay(command);

        return persistenceService.allocateHold(command, freeDockingBay);
    }

    private DockingBayEntity getFreeDockingBay(ReserveBayCommand command) {
        return dockingBayRepository
                .findFreeBay(command.starportCode(), command.shipClass().name(), command.startAt(), command.endAt())
                .orElseThrow(() -> new NoDockingBaysAvailableException(
                        command.starportCode(), command.startAt(), command.endAt()));
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
