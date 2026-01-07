package com.galactic.router.domain;

public class PlanRouteCommand {
    private final String originPortId;
    private final String destinationPortId;
    private final ShipProfile shipProfile;

    public PlanRouteCommand(String originPortId, String destinationPortId, ShipProfile shipProfile) {
        this.originPortId = originPortId;
        this.destinationPortId = destinationPortId;
        this.shipProfile = shipProfile;
    }

    public String getOriginPortId() {
        return originPortId;
    }

    public String getDestinationPortId() {
        return destinationPortId;
    }

    public ShipProfile getShipProfile() {
        return shipProfile;
    }
}
