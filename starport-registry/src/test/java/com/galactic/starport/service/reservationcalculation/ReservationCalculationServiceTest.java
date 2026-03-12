package com.galactic.starport.service.reservationcalculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import com.galactic.starport.service.feecalculator.FeeCalculator;
import com.galactic.starport.service.routeplanner.RoutePlanner;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class ReservationCalculationServiceTest {

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private RoutePlanner routePlanner;

    private ReservationCalculationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationCalculationService(feeCalculator, routePlanner);
    }

    @Test
    void should_return_calculation_with_fee_and_route() {

        Long reservationId = 42L;
        ReserveBayCommand cmd = aCommand(true);
        Route route = Route.builder().routeCode("RT-1").build();

        given(feeCalculator.calculateFee(cmd)).willReturn(BigDecimal.valueOf(100));
        given(routePlanner.calculateRoute(cmd)).willReturn(route);

        ReservationCalculation result = service.calculate(reservationId, cmd);

        assertThat(result.reservationId()).isEqualTo(42L);
        assertThat(result.calculatedFee()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(result.route()).isEqualTo(route);
    }

    @Test
    void should_return_calculation_with_null_route_when_not_requested() {

        Long reservationId = 10L;
        ReserveBayCommand cmd = aCommand(false);

        given(feeCalculator.calculateFee(cmd)).willReturn(BigDecimal.valueOf(50));
        given(routePlanner.calculateRoute(cmd)).willReturn(null);

        ReservationCalculation result = service.calculate(reservationId, cmd);

        assertThat(result.reservationId()).isEqualTo(10L);
        assertThat(result.calculatedFee()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(result.route()).isNull();
    }

    private static ReserveBayCommand aCommand(boolean requestRoute) {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2004-01-01T00:00:00Z"))
                .endAt(Instant.parse("2004-01-01T01:00:00Z"))
                .requestRoute(requestRoute)
                .build();
    }
}
