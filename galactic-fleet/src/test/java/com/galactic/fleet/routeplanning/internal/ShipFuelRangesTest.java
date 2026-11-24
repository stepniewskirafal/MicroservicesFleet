package com.galactic.fleet.routeplanning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Fuel range per ship class is route-planning knowledge (ADR-0043) — it used to sit in the caller. */
@Execution(ExecutionMode.CONCURRENT)
class ShipFuelRangesTest {

    @ParameterizedTest(name = "[{index}] {0} flies {1} LY")
    @CsvSource({
        "SCOUT,     15.0",
        "FREIGHTER, 25.0",
        "CRUISER,   40.0",
        "UNKNOWN,    5.0",
        "DESTROYER,  5.0", // a class the planner has never heard of
    })
    void default_ranges(String shipClass, double expectedLY) {
        assertThat(new ShipFuelRanges().rangeLY(shipClass)).isEqualTo(expectedLY);
    }

    @Test
    void lookup_ignores_case_and_surrounding_whitespace() {
        assertThat(new ShipFuelRanges().rangeLY("  scout ")).isEqualTo(15.0);
    }

    @Test
    void a_missing_ship_class_gets_the_fallback_range() {
        assertThat(new ShipFuelRanges().rangeLY(null)).isEqualTo(5.0);
    }

    @Test
    void ranges_can_be_supplied() {
        ShipFuelRanges ranges = new ShipFuelRanges(Map.of("TUG", 0.5), 2.0);

        assertThat(ranges.rangeLY("TUG")).isEqualTo(0.5);
        assertThat(ranges.rangeLY("SCOUT")).isEqualTo(2.0);
    }
}
