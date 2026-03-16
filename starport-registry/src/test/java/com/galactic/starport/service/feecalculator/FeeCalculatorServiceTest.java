package com.galactic.starport.service.feecalculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.galactic.starport.service.InvalidReservationTimeException;
import com.galactic.starport.service.ReserveBayCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Execution(ExecutionMode.CONCURRENT)
class FeeCalculatorServiceTest {

    private FeeCalculatorService feeCalculator;

    @BeforeEach
    void setUp() {
        feeCalculator = new FeeCalculatorService(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    static Stream<Arguments> shipClassAndExpectedFee() {
        return Stream.of(
                Arguments.of(ReserveBayCommand.ShipClass.SCOUT, 1L, BigDecimal.valueOf(50)),
                Arguments.of(ReserveBayCommand.ShipClass.SCOUT, 3L, BigDecimal.valueOf(150)),
                Arguments.of(ReserveBayCommand.ShipClass.FREIGHTER, 2L, BigDecimal.valueOf(240)),
                Arguments.of(ReserveBayCommand.ShipClass.CRUISER, 1L, BigDecimal.valueOf(250)),
                Arguments.of(ReserveBayCommand.ShipClass.UNKNOWN, 1L, BigDecimal.valueOf(1000)));
    }

    @ParameterizedTest(name = "{0} x {1}h => {2} PLN")
    @MethodSource("shipClassAndExpectedFee")
    void should_compute_correct_fee_when_ship_class_and_hours_given(
            ReserveBayCommand.ShipClass shipClass, long hours, BigDecimal expectedFee) {
        Instant start = Instant.parse("2004-01-01T00:00:00Z");
        ReserveBayCommand cmd = aCommand(shipClass, start, start.plusSeconds(hours * 3600L));

        BigDecimal fee = feeCalculator.calculateFee(cmd);

        assertThat(fee).isEqualByComparingTo(expectedFee);
    }

    @Test
    void should_charge_minimum_one_hour_when_duration_is_less_than_one_hour() {
        Instant start = Instant.parse("2004-01-01T00:00:00Z");
        Instant end = start.plusSeconds(1800); // 30 minutes
        ReserveBayCommand cmd = aCommand(ReserveBayCommand.ShipClass.SCOUT, start, end);

        BigDecimal fee = feeCalculator.calculateFee(cmd);

        assertThat(fee).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    void should_charge_minimum_one_hour_when_start_equals_end() {

        Instant start = Instant.parse("2004-01-01T00:00:00Z");
        ReserveBayCommand cmd = aCommand(ReserveBayCommand.ShipClass.SCOUT, start, start);

        BigDecimal fee = feeCalculator.calculateFee(cmd);

        assertThat(fee).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    void should_throw_when_end_time_is_before_start_time() {
        Instant start = Instant.parse("2004-01-01T01:00:00Z");
        Instant end = Instant.parse("2004-01-01T00:00:00Z");
        ReserveBayCommand cmd = aCommand(ReserveBayCommand.ShipClass.SCOUT, start, end);

        assertThatThrownBy(() -> feeCalculator.calculateFee(cmd)).isInstanceOf(InvalidReservationTimeException.class);
    }

    private static ReserveBayCommand aCommand(ReserveBayCommand.ShipClass shipClass, Instant start, Instant end) {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(shipClass)
                .startAt(start)
                .endAt(end)
                .requestRoute(true)
                .build();
    }
}
