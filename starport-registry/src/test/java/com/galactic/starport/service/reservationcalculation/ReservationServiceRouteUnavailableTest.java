package com.galactic.starport.service.reservationcalculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.galactic.starport.service.ReservationService;
import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.confirmreservation.ConfirmReservationFacade;
import com.galactic.starport.service.feecalculator.FeeCalculator;
import com.galactic.starport.service.holdreservation.HoldReservationFacade;
import com.galactic.starport.service.routeplanner.RoutePlanner;
import com.galactic.starport.service.routeplanner.RouteUnavailableException;
import com.galactic.starport.service.validation.ReserveBayValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * End-to-end regression for CRITICAL-1: a domain exception thrown inside the route-planning
 * {@code CompletableFuture} must surface with its ORIGINAL type, so {@link ReservationService}'s
 * typed {@code catch (RouteUnavailableException)} fires (→ HTTP 409, not 500) and the
 * {@code reservations{outcome=route_unavailable}} metric is recorded.
 *
 * <p>Unlike {@code ReservationServiceTest} / {@code ReservationServiceMetricsTest}, which mock the
 * {@code ReservationCalculationFacade}, this wires the REAL {@link ReservationCalculationService}
 * (real {@code CompletableFuture} orchestration) into {@link ReservationService}; only the leaf
 * collaborators are mocked. A facade-mock test cannot catch this regression because it never runs a
 * real future, so {@code CompletableFuture#join()} never wraps the cause in a {@code
 * CompletionException}.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class ReservationServiceRouteUnavailableTest {

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private RoutePlanner routePlanner;

    @Mock
    private HoldReservationFacade holdReservationFacade;

    @Mock
    private ConfirmReservationFacade confirmReservationFacade;

    @Mock
    private ReserveBayValidator reservationValidator;

    private SimpleMeterRegistry meterRegistry;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // REAL calculation service: orchestrates fee + route on real CompletableFutures.
        ReservationCalculationService calculation = new ReservationCalculationService(feeCalculator, routePlanner);
        reservationService = new ReservationService(
                holdReservationFacade,
                confirmReservationFacade,
                reservationValidator,
                calculation,
                meterRegistry,
                Tracer.NOOP,
                starport -> starport);
    }

    @Test
    void route_planner_failure_surfaces_typed_exception_and_records_metric() {
        ReserveBayCommand cmd = aCommand();
        given(holdReservationFacade.createHoldReservation(cmd)).willReturn(1L);
        given(feeCalculator.calculateFee(cmd)).willReturn(BigDecimal.valueOf(100));
        // Thrown INSIDE CompletableFuture.supplyAsync → join() wraps the cause in CompletionException.
        willThrow(new RouteUnavailableException("ALPHA-BASE", "DEF"))
                .given(routePlanner)
                .calculateRoute(cmd);

        // Without CompletionException unwrapping this surfaces as CompletionException, slips past the
        // typed catch, and is mapped by the catch-all handler to HTTP 500 instead of 409.
        assertThatThrownBy(() -> reservationService.reserveBay(cmd)).isInstanceOf(RouteUnavailableException.class);

        double routeUnavailable = meterRegistry
                .get("reservations")
                .tag("outcome", "route_unavailable")
                .counter()
                .count();
        assertThat(routeUnavailable).isEqualTo(1.0);
    }

    private static ReserveBayCommand aCommand() {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2004-01-01T00:00:00Z"))
                .endAt(Instant.parse("2004-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();
    }
}
