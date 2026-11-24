package com.galactic.fleet.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.galactic.fleet.booking.service.feecalculator.FeeCalculator;
import com.galactic.fleet.booking.service.routeplanner.RoutePlanner;
import com.galactic.fleet.booking.service.routeplanner.RouteRejectedException;
import com.galactic.fleet.booking.service.validation.ReserveBayValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The order of a reservation (ADR-0043): validate → fee and route (no transaction) → one confirming
 * transaction. Nothing is written before the last step, so no failure needs compensating.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class ReservationServiceTest {

    private static final BigDecimal FEE = BigDecimal.valueOf(100);

    @Mock
    private ReserveBayValidator reservationValidator;

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private RoutePlanner routePlanner;

    @Mock
    private ReservationConfirmer reservationConfirmer;

    private SimpleMeterRegistry meterRegistry;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        reservationService = new ReservationService(
                reservationValidator,
                feeCalculator,
                routePlanner,
                reservationConfirmer,
                meterRegistry,
                starport -> starport);
    }

    @Test
    void should_return_the_confirmed_reservation_with_the_computed_fee_and_route() {
        ReserveBayCommand cmd = aCommand();
        Route route = Route.builder().routeCode("ROUTE-1").build();
        Reservation expected = Reservation.builder()
                .id(42L)
                .status(Reservation.ReservationStatus.CONFIRMED)
                .build();
        given(feeCalculator.calculateFee(cmd)).willReturn(FEE);
        given(routePlanner.calculateRoute(cmd)).willReturn(route);
        given(reservationConfirmer.confirm(cmd, FEE, route)).willReturn(expected);

        Optional<Reservation> result = reservationService.reserveBay(cmd);

        assertThat(result).contains(expected);
        assertThat(outcomeCount("confirmed")).isEqualTo(1.0);
    }

    @Test
    void should_validate_then_compute_then_confirm_in_that_order() {
        ReserveBayCommand cmd = aCommand();
        given(feeCalculator.calculateFee(cmd)).willReturn(FEE);
        given(reservationConfirmer.confirm(any(), any(), any()))
                .willReturn(Reservation.builder().id(1L).build());

        reservationService.reserveBay(cmd);

        InOrder order = inOrder(reservationValidator, feeCalculator, routePlanner, reservationConfirmer);
        order.verify(reservationValidator).validate(cmd);
        order.verify(feeCalculator).calculateFee(cmd);
        order.verify(routePlanner).calculateRoute(cmd);
        order.verify(reservationConfirmer).confirm(cmd, FEE, null);
    }

    @Test
    void should_not_compute_or_confirm_when_validation_fails() {
        ReserveBayCommand cmd = aCommand();
        willThrow(new InvalidReservationTimeException(cmd.startAt(), cmd.endAt()))
                .given(reservationValidator)
                .validate(cmd);

        assertThatThrownBy(() -> reservationService.reserveBay(cmd))
                .isInstanceOf(InvalidReservationTimeException.class);

        then(feeCalculator).should(never()).calculateFee(any());
        then(routePlanner).should(never()).calculateRoute(any());
        then(reservationConfirmer).should(never()).confirm(any(), any(), any());
    }

    @Test
    void should_propagate_starport_not_found_exception() {
        ReserveBayCommand cmd = aCommand();
        willThrow(new StarportNotFoundException("DEF"))
                .given(reservationValidator)
                .validate(cmd);

        assertThatThrownBy(() -> reservationService.reserveBay(cmd)).isInstanceOf(StarportNotFoundException.class);
    }

    @Test
    void should_write_nothing_when_the_route_is_rejected() {
        ReserveBayCommand cmd = aCommand();
        given(feeCalculator.calculateFee(cmd)).willReturn(FEE);
        given(routePlanner.calculateRoute(cmd)).willThrow(new RouteRejectedException("ALPHA-BASE", "DEF"));

        assertThatThrownBy(() -> reservationService.reserveBay(cmd)).isInstanceOf(RouteRejectedException.class);

        // The transaction never opened, so there is no reservation to compensate.
        then(reservationConfirmer).should(never()).confirm(any(), any(), any());
        assertThat(outcomeCount("route_rejected")).isEqualTo(1.0);
        assertThat(outcomeCount("confirmed")).isZero();
    }

    @Test
    void should_count_no_capacity_when_no_bay_is_free() {
        ReserveBayCommand cmd = aCommand();
        given(feeCalculator.calculateFee(cmd)).willReturn(FEE);
        given(reservationConfirmer.confirm(cmd, FEE, null))
                .willThrow(new NoDockingBaysAvailableException("DEF", "SCOUT", cmd.startAt(), cmd.endAt()));

        assertThatThrownBy(() -> reservationService.reserveBay(cmd))
                .isInstanceOf(NoDockingBaysAvailableException.class);

        assertThat(outcomeCount("no_capacity")).isEqualTo(1.0);
        assertThat(outcomeCount("confirmed")).isZero();
    }

    private double outcomeCount(String outcome) {
        var counter = meterRegistry.find("reservations").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static ReserveBayCommand aCommand() {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2004-01-01T00:00:00Z"))
                .endAt(Instant.parse("2004-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();
    }
}
