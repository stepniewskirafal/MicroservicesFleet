package com.galactic.router.adapters.rest;

public class ShipProfileDto {
    private String shipClass;
    private double fuelRangeLY;

    public String getShipClass() {
        return shipClass;
    }

    public void setShipClass(String shipClass) {
        this.shipClass = shipClass;
    }

    public double getFuelRangeLY() {
        return fuelRangeLY;
    }

    public void setFuelRangeLY(double fuelRangeLY) {
        this.fuelRangeLY = fuelRangeLY;
    }
}
