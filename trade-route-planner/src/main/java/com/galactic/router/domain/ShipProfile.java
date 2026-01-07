package com.galactic.router.domain;

public class ShipProfile {
    private final String shipClass;
    private final double fuelRangeLY;

    public ShipProfile(String shipClass, double fuelRangeLY) {
        this.shipClass = shipClass;
        this.fuelRangeLY = fuelRangeLY;
    }

    public String getShipClass() {
        return shipClass;
    }

    public double getFuelRangeLY() {
        return fuelRangeLY;
    }
}
