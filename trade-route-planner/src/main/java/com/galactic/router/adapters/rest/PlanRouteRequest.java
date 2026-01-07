package com.galactic.router.adapters.rest;

public class PlanRouteRequest {
    private String originPortId;
    private String destinationPortId;
    private ShipProfileDto shipProfile;

    public String getOriginPortId() {
        return originPortId;
    }

    public void setOriginPortId(String originPortId) {
        this.originPortId = originPortId;
    }

    public String getDestinationPortId() {
        return destinationPortId;
    }

    public void setDestinationPortId(String destinationPortId) {
        this.destinationPortId = destinationPortId;
    }

    public ShipProfileDto getShipProfile() {
        return shipProfile;
    }

    public void setShipProfile(ShipProfileDto shipProfile) {
        this.shipProfile = shipProfile;
    }
}
