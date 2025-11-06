package com.galactic.starport.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.galactic.starport.repository.StarportRepository;
import com.galactic.starport.service.StarportNotFoundException;
import com.galactic.starport.service.WrongReservationTimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ValidateReservationCommandServiceMetricsTest {

    @Mock private StarportRepository starportRepository;

    private SimpleMeterRegistry meterRegistry;
    private ValidateReservationCommandService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ValidateReservationCommandService(starportRepository, meterRegistry);
        service.initMetrics();
    }

    @Test
    void recordsSuccessfulValidation() {
        ReserveBayCommand command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .startAt(Instant.parse("2025-01-01T10:00:00Z"))
                .endAt(Instant.parse("2025-01-01T11:00:00Z"))
                .requestRoute(true)
                .build();

        when(starportRepository.existsByCode("START")).thenReturn(true);
        when(starportRepository.existsByCode("DEST")).thenReturn(true);

        service.validate(command);

        assertEquals(1.0, meterRegistry.get("reservations.validation.attempts").counter().count());
        assertEquals(1.0, meterRegistry.get("reservations.validation.success").counter().count());
        assertEquals(0.0, meterRegistry.get("reservations.validation.errors").counter().count());
        assertEquals(1, meterRegistry.get("reservations.validation.duration").timer().count());
    }

    @Test
    void recordsStarportNotFoundError() {
        ReserveBayCommand command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .startAt(Instant.parse("2025-01-01T10:00:00Z"))
                .endAt(Instant.parse("2025-01-01T11:00:00Z"))
                .requestRoute(true)
                .build();

        when(starportRepository.existsByCode("START")).thenReturn(false);

        assertThrows(StarportNotFoundException.class, () -> service.validate(command));

        assertEquals(1.0, meterRegistry.get("reservations.validation.attempts").counter().count());
        assertEquals(0.0, meterRegistry.get("reservations.validation.success").counter().count());
        assertEquals(1.0, meterRegistry.get("reservations.validation.errors").counter().count());
        assertEquals(1, meterRegistry.get("reservations.validation.duration").timer().count());
    }

    @Test
    void recordsWrongTimeError() {
        ReserveBayCommand command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .startAt(Instant.parse("2025-01-01T12:00:00Z"))
                .endAt(Instant.parse("2025-01-01T11:00:00Z"))
                .requestRoute(false)
                .build();

        when(starportRepository.existsByCode(anyString())).thenReturn(true);

        assertThrows(WrongReservationTimeException.class, () -> service.validate(command));

        assertEquals(1.0, meterRegistry.get("reservations.validation.attempts").counter().count());
        assertEquals(0.0, meterRegistry.get("reservations.validation.success").counter().count());
        assertEquals(1.0, meterRegistry.get("reservations.validation.errors").counter().count());
        assertEquals(1, meterRegistry.get("reservations.validation.duration").timer().count());
    }
}
