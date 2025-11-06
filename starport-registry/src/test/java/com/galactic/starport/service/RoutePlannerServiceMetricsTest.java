package com.galactic.starport.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.galactic.starport.repository.ReservationEntity;
import com.galactic.starport.repository.ReservationRepository;
import com.galactic.starport.repository.StarportEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RoutePlannerServiceMetricsTest {

    @Mock private ReservationRepository reservationRepository;
    @Mock private OutboxWriter outboxWriter;

    private SimpleMeterRegistry meterRegistry;
    private RoutePlannerService routePlannerService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        routePlannerService = new RoutePlannerService(reservationRepository, outboxWriter, meterRegistry);
        routePlannerService.initMetrics();
        ReflectionTestUtils.setField(routePlannerService, "reservationsBinding", "reservations-out");
    }

    @Test
    void recordsSuccessfulRoutePlanningAndConfirmation() {
        ReserveBayCommand command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(true)
                .build();

        Reservation reservation = Reservation.builder()
                .id(1L)
                .feeCharged(BigDecimal.valueOf(150))
                .startAt(Instant.parse("2025-01-01T10:00:00Z"))
                .endAt(Instant.parse("2025-01-01T12:00:00Z"))
                .ship(Ship.builder().shipClass(Ship.ShipClass.SCOUT).build())
                .status(Reservation.ReservationStatus.HOLD)
                .build();

        StarportEntity starportEntity = new StarportEntity();
        ReservationEntity persisted = org.mockito.Mockito.mock(ReservationEntity.class);
        when(reservationRepository.save(any(ReservationEntity.class))).thenReturn(persisted);
        doNothing().when(outboxWriter)
                .append(any(), any(), any(), any(), any());

        Optional<Reservation> result = routePlannerService.addRoute(command, reservation, starportEntity);

        assertTrue(result.isPresent());
        assertEquals(1.0, meterRegistry.get("reservations.route.confirmation.success").counter().count());
        assertEquals(0.0, meterRegistry.get("reservations.route.confirmation.errors").counter().count());
        assertEquals(1, meterRegistry.get("reservations.route.confirmation.duration").timer().count());
        assertEquals(1.0, meterRegistry.get("reservations.route.planning.success").counter().count());
        assertEquals(0.0, meterRegistry.get("reservations.route.planning.errors").counter().count());
        assertEquals(1, meterRegistry.get("reservations.route.planning.duration").timer().count());
    }

    @Test
    void recordsConfirmationErrorsAndReleasesHold() {
        ReserveBayCommand command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(false)
                .build();

        Reservation reservation = Reservation.builder()
                .id(99L)
                .feeCharged(BigDecimal.valueOf(200))
                .startAt(Instant.parse("2025-01-01T10:00:00Z"))
                .endAt(Instant.parse("2025-01-01T12:00:00Z"))
                .ship(Ship.builder().shipClass(Ship.ShipClass.SCOUT).build())
                .status(Reservation.ReservationStatus.HOLD)
                .build();

        StarportEntity starportEntity = new StarportEntity();
        ReservationEntity persisted = org.mockito.Mockito.mock(ReservationEntity.class);
        when(reservationRepository.save(any(ReservationEntity.class)))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(persisted);
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(persisted));

        Optional<Reservation> result = routePlannerService.addRoute(command, reservation, starportEntity);

        assertTrue(result.isEmpty());
        assertEquals(1.0, meterRegistry.get("reservations.route.confirmation.errors").counter().count());
        assertEquals(0.0, meterRegistry.get("reservations.route.confirmation.success").counter().count());
        assertEquals(1, meterRegistry.get("reservations.route.confirmation.duration").timer().count());
    }

    @Test
    void recordsPlanRouteMetrics() {
        ReserveBayCommand command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .requestRoute(true)
                .build();

        routePlannerService.planRoute(command);

        assertEquals(1, meterRegistry.get("reservations.route.planning.duration").timer().count());
        assertEquals(1.0, meterRegistry.get("reservations.route.planning.success").counter().count());
        assertEquals(0.0, meterRegistry.get("reservations.route.planning.errors").counter().count());
    }
}
