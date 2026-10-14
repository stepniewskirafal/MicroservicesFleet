package com.galactic.fleet.routeplanning.internal;

import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * How far each ship class can fly on one tank, in light-years. Route planning owns this knowledge:
 * callers name the ship class and the planner decides whether the journey is within range.
 */
@Component
class ShipFuelRanges {

    private static final Map<String, Double> DEFAULT_RANGES_LY = Map.of(
            "SCOUT", 15.0,
            "FREIGHTER", 25.0,
            "CRUISER", 40.0);
    private static final double DEFAULT_UNKNOWN_CLASS_RANGE_LY = 5.0;

    private final Map<String, Double> rangesLY;
    private final double unknownClassRangeLY;

    @Autowired
    ShipFuelRanges() {
        this(DEFAULT_RANGES_LY, DEFAULT_UNKNOWN_CLASS_RANGE_LY);
    }

    ShipFuelRanges(Map<String, Double> rangesLY, double unknownClassRangeLY) {
        this.rangesLY = Map.copyOf(Objects.requireNonNull(rangesLY, "rangesLY must not be null"));
        this.unknownClassRangeLY = unknownClassRangeLY;
    }

    double rangeLY(String shipClass) {
        if (shipClass == null) {
            return unknownClassRangeLY;
        }
        return rangesLY.getOrDefault(shipClass.trim().toUpperCase(), unknownClassRangeLY);
    }
}
