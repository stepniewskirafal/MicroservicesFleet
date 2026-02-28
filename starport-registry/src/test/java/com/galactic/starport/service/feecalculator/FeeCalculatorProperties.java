package com.galactic.starport.service.feecalculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.starport.service.ReserveBayCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class FeeCalculatorProperties {

    private final FeeCalculatorService feeCalculator =
            new FeeCalculatorService(new SimpleMeterRegistry(), ObservationRegistry.NOOP);

    @Property
    void fee_is_always_positive_for_valid_reservation(
            @ForAll("validShipClasses") ReserveBayCommand.ShipClass shipClass,
            @ForAll @IntRange(min = 1, max = 720) int hours) {
        // given
        Instant start = Instant.parse("2004-01-01T00:00:00Z");
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(shipClass)
                .startAt(start)
                .endAt(start.plusSeconds(hours * 3600L))
                .requestRoute(false)
                .build();

        // when
        BigDecimal fee = feeCalculator.calculateFee(cmd);

        // then
        assertThat(fee).isPositive();
    }

    @Property
    void fee_scales_linearly_with_hours(
            @ForAll("validShipClasses") ReserveBayCommand.ShipClass shipClass,
            @ForAll @IntRange(min = 1, max = 360) int hours) {
        // given
        Instant start = Instant.parse("2004-01-01T00:00:00Z");
        ReserveBayCommand singleHourCmd = ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(shipClass)
                .startAt(start)
                .endAt(start.plusSeconds(3600L))
                .requestRoute(false)
                .build();

        ReserveBayCommand multiHourCmd = ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(shipClass)
                .startAt(start)
                .endAt(start.plusSeconds(hours * 3600L))
                .requestRoute(false)
                .build();

        // when
        BigDecimal singleHourFee = feeCalculator.calculateFee(singleHourCmd);
        BigDecimal multiHourFee = feeCalculator.calculateFee(multiHourCmd);

        // then — fee(N hours) == fee(1 hour) * N
        assertThat(multiHourFee).isEqualByComparingTo(singleHourFee.multiply(BigDecimal.valueOf(hours)));
    }

    @Property
    void higher_ship_class_is_never_cheaper_than_scout(@ForAll @IntRange(min = 1, max = 100) int hours) {
        // given
        Instant start = Instant.parse("2004-01-01T00:00:00Z");
        Instant end = start.plusSeconds(hours * 3600L);

        BigDecimal scoutFee = calculateFeeFor(ReserveBayCommand.ShipClass.SCOUT, start, end);
        BigDecimal freighterFee = calculateFeeFor(ReserveBayCommand.ShipClass.FREIGHTER, start, end);
        BigDecimal cruiserFee = calculateFeeFor(ReserveBayCommand.ShipClass.CRUISER, start, end);

        // then
        assertThat(freighterFee).isGreaterThanOrEqualTo(scoutFee);
        assertThat(cruiserFee).isGreaterThanOrEqualTo(freighterFee);
    }

    @Provide
    Arbitrary<ReserveBayCommand.ShipClass> validShipClasses() {
        return Arbitraries.of(ReserveBayCommand.ShipClass.values());
    }

    private BigDecimal calculateFeeFor(ReserveBayCommand.ShipClass shipClass, Instant start, Instant end) {
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(shipClass)
                .startAt(start)
                .endAt(end)
                .requestRoute(false)
                .build();
        return feeCalculator.calculateFee(cmd);
    }
}
