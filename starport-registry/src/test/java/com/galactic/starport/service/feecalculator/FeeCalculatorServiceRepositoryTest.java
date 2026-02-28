package com.galactic.starport.service.feecalculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.galactic.starport.BaseAcceptanceTest;
import com.galactic.starport.service.InvalidReservationTimeException;
import com.galactic.starport.service.ReserveBayCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

@Execution(ExecutionMode.CONCURRENT)
class FeeCalculatorServiceRepositoryTest extends BaseAcceptanceTest {

    private static final String DEST = "DEF";

    @Autowired
    FeeCalculator feeCalculator;

    static Stream<Arguments> shipClassAndExpectedFee() {
        return Stream.of(
                Arguments.of(ReserveBayCommand.ShipClass.SCOUT, 1L, BigDecimal.valueOf(50)),
                Arguments.of(ReserveBayCommand.ShipClass.FREIGHTER, 2L, BigDecimal.valueOf(240)));
    }

    @ParameterizedTest(name = "{0} x {1}h => {2} PLN")
    @MethodSource("shipClassAndExpectedFee")
    void calculateFeeComputesCorrectFeeForDifferentShipClasses(
            ReserveBayCommand.ShipClass shipClass, long hours, BigDecimal expectedFee) {
        Instant start = Instant.parse("2004-01-01T00:00:00Z");
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(shipClass)
                .startAt(start)
                .endAt(start.plusSeconds(hours * 3600L))
                .requestRoute(true)
                .build();

        BigDecimal fee = feeCalculator.calculateFee(cmd);

        assertEquals(expectedFee, fee);
    }

    @Test
    void calculateFeeThrowsWhenEndTimeIsBeforeStartTime() {
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2004-01-01T01:00:00Z"))
                .endAt(Instant.parse("2004-01-01T00:00:00Z"))
                .requestRoute(true)
                .build();

        assertThrows(InvalidReservationTimeException.class, () -> feeCalculator.calculateFee(cmd));
    }
}
