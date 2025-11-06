package com.galactic.starport.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.galactic.starport.repository.StarportEntity;
import com.galactic.starport.repository.StarportRepository;
import com.galactic.starport.service.StarportNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationServiceMetricsTest {

    @Mock
    private HoldReservationService holdReservationService;

    @Mock
    private ValidateReservationCommandService validateReservationCommandService;

    @Mock
    private FeeCalculatorService feeCalculatorService;

    @Mock
    private RoutePlannerService routePlannerService;

    @Mock
    private StarportRepository starportRepository;

    private SimpleMeterRegistry meterRegistry;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        reservationService = new ReservationService(
                holdReservationService,
                validateReservationCommandService,
                feeCalculatorService,
                routePlannerService,
                starportRepository,
                meterRegistry);
        reservationService.initMetrics();
    }

    @Test
    void recordsSuccessAndTimingWhenReservationCompletes() {
        ReserveBayCommand command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .customerCode("CUST")
                .shipCode("SHIP")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.now())
                .endAt(Instant.now().plusSeconds(3600))
                .requestRoute(true)
                .build();

        StarportEntity starport = new StarportEntity();
        Reservation reservation = Reservation.builder()
                .id(1L)
                .ship(Ship.builder().shipClass(Ship.ShipClass.SCOUT).build())
                .startAt(command.startAt())
                .endAt(command.endAt())
                .status(Reservation.ReservationStatus.HOLD)
                .build();

        when(starportRepository.findByCode(command.destinationStarportCode()))
                .thenReturn(Optional.of(starport));
        when(holdReservationService.allocateHold(eq(command), eq(starport))).thenReturn(reservation);
        when(feeCalculatorService.calculateFee(reservation)).thenReturn(BigDecimal.TEN);
        when(routePlannerService.addRoute(command, reservation, starport)).thenReturn(Optional.of(reservation));

        reservationService.reserveBay(command);

        assertEquals(1.0, meterRegistry.get("reservations.reserve.success").counter().count());
        assertEquals(1, meterRegistry.get("reservations.reserve.duration").timer().count());
        assertEquals(0.0, meterRegistry.get("reservations.reserve.errors").counter().count());
    }

    @Test
    void recordsErrorWhenReservationFails() {
        ReserveBayCommand command = ReserveBayCommand.builder()
                .startStarportCode("START")
                .destinationStarportCode("DEST")
                .customerCode("CUST")
                .shipCode("SHIP")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.now())
                .endAt(Instant.now().plusSeconds(3600))
                .requestRoute(false)
                .build();

        when(starportRepository.findByCode(command.destinationStarportCode())).thenReturn(Optional.empty());

        assertThrows(StarportNotFoundException.class, () -> reservationService.reserveBay(command));

        assertEquals(1.0, meterRegistry.get("reservations.reserve.errors").counter().count());
        assertEquals(0.0, meterRegistry.get("reservations.reserve.success").counter().count());
        assertEquals(1, meterRegistry.get("reservations.reserve.duration").timer().count());
    }
}
