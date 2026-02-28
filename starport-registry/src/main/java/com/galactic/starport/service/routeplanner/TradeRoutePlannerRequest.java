package com.galactic.starport.service.routeplanner;

import com.fasterxml.jackson.annotation.JsonProperty;

record TradeRoutePlannerRequest(String originPortId, String destinationPortId, ShipProfileDto shipProfile) {

    record ShipProfileDto(@JsonProperty("class") String shipClass, double fuelRangeLY) {}
}
