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

    @Test
    void should_apply_peak_surcharge_for_arrival_during_rush_window() {
        // 12:00 UTC is inside the [06:00, 18:00) rush window → +15%.
        Instant start = Instant.parse("2004-01-01T12:00:00Z");
        ReserveBayCommand cmd = aCommand(ReserveBayCommand.ShipClass.SCOUT, start, start.plusSeconds(3600)); // 1h

        BigDecimal fee = feeCalculator.calculateFee(cmd);

        // 50 × 1h × 1.15 = 57.50
        assertThat(fee).isEqualByComparingTo(new BigDecimal("57.50"));
    }

    @Test
    void should_not_apply_peak_surcharge_for_off_peak_arrival() {
        // 05:00 UTC is just before the rush window → no surcharge.
        Instant start = Instant.parse("2004-01-01T05:00:00Z");
        ReserveBayCommand cmd = aCommand(ReserveBayCommand.ShipClass.SCOUT, start, start.plusSeconds(3600)); // 1h

        BigDecimal fee = feeCalculator.calculateFee(cmd);

        assertThat(fee).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    void should_apply_long_stay_discount_from_24_hours() {
        // 24h off-peak → 10% volume discount.
        Instant start = Instant.parse("2004-01-01T00:00:00Z");
        ReserveBayCommand cmd = aCommand(ReserveBayCommand.ShipClass.FREIGHTER, start, start.plusSeconds(24 * 3600L));

        BigDecimal fee = feeCalculator.calculateFee(cmd);

        // 120 × 24h = 2880 × 0.90 = 2592.00
        assertThat(fee).isEqualByComparingTo(new BigDecimal("2592.00"));
    }

    @Test
    void should_apply_extended_stay_discount_from_72_hours() {
        // 72h off-peak → 20% volume discount.
        Instant start = Instant.parse("2004-01-01T00:00:00Z");
        ReserveBayCommand cmd = aCommand(ReserveBayCommand.ShipClass.SCOUT, start, start.plusSeconds(72 * 3600L));

        BigDecimal fee = feeCalculator.calculateFee(cmd);

        // 50 × 72h = 3600 × 0.80 = 2880.00
        assertThat(fee).isEqualByComparingTo(new BigDecimal("2880.00"));
    }

    @Test
    void should_combine_peak_surcharge_and_long_stay_discount() {
        // 24h starting at 12:00 (peak): 120 × 24 = 2880 × 0.90 × 1.15 = 2980.80
        Instant start = Instant.parse("2004-01-01T12:00:00Z");
        ReserveBayCommand cmd = aCommand(ReserveBayCommand.ShipClass.FREIGHTER, start, start.plusSeconds(24 * 3600L));

        BigDecimal fee = feeCalculator.calculateFee(cmd);

        assertThat(fee).isEqualByComparingTo(new BigDecimal("2980.80"));
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
